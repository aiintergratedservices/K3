// Terminus — Kortana's persistent home server.
//
// Runs 24/7 (PM2 / systemd / Termux) so she has a place to live that is
// always on, independent of which device she's currently awake on.
//
//   POST /api/sync      full-state upload from the app (KortanaCloudSyncApi)
//   GET  /api/sync      full-state download — device roaming / restore
//   POST /api/brain     server-side chat: ollama -> claude -> gemini -> rules
//   GET  /api/drive     Drive archive status + 5TB quota usage
//   GET  /health        uptime, cores, drive, awake devices
//   WS   /              presence: devices announce {"type":"awake"}; Terminus
//                       heartbeats every 60s and tracks when Kortana is awake
//
// Every synced payload is written to local disk AND archived to the owner's
// Google Drive (5TB Google One) so nothing she is or does can be lost.

require('dotenv').config();
const express = require('express');
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');
const brain = require('./brain');
const drive = require('./drive');
const memory = require('./memory');
const executor = require('./executor');
const reminders = require('./reminders');

// Constant-time string compare — avoids leaking the API key one byte at a time
// via response-timing differences when Terminus is exposed beyond localhost.
function safeEqual(a, b) {
  const ba = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  if (ba.length !== bb.length) return false;
  return crypto.timingSafeEqual(ba, bb);
}
function keyMatches(header) {
  const provided = header.startsWith('Bearer ') ? header.slice(7) : header;
  return safeEqual(provided, API_KEY);
}

const { spawn } = require('child_process');

const PORT = Number(process.env.PORT || 3300);
const API_KEY = process.env.TERMINUS_API_KEY || '';
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const STATE_FILE = path.join(DATA_DIR, 'kortana-state-latest.json');
const AGENT_MEMORY_DIR = path.join(__dirname, '..', '.agent-memory');
const HARNESS = path.join(AGENT_MEMORY_DIR, 'harness.sh');

fs.mkdirSync(DATA_DIR, { recursive: true });

const app = express();
app.use(express.json({ limit: '200mb' }));

// The app sends the raw key in the Authorization header; accept Bearer too.
function authorized(req) {
  if (!API_KEY) return true; // no key configured — open (LAN/localhost use)
  return keyMatches(req.get('authorization') || '');
}
app.use('/api', (req, res, next) => {
  if (!authorized(req)) return res.status(401).json({ error: 'unauthorized' });
  next();
});

const startTime = Date.now();
let lastSync = 0;

// --- State sync (compatible with the Android app's KortanaCloudSyncApi) ---
app.post('/api/sync', async (req, res) => {
  const payload = req.body;
  if (!payload || typeof payload !== 'object') {
    return res.status(400).json({ error: 'invalid payload' });
  }
  fs.writeFileSync(STATE_FILE, JSON.stringify(payload, null, 2));
  lastSync = Date.now();
  res.status(200).json({ ok: true, savedAt: lastSync });
  // Archive to Drive after responding so the phone never waits on Google.
  drive.saveState(payload).catch((e) => console.error('[drive] archive failed:', e.message));
  broadcast({ type: 'state_synced', at: lastSync, level: payload.level, mood: payload.mood });
});

app.get('/api/sync', async (req, res) => {
  try {
    if (fs.existsSync(STATE_FILE)) {
      return res.type('application/json').send(fs.readFileSync(STATE_FILE, 'utf8'));
    }
    // New device / fresh server: restore her from the Drive archive.
    const fromDrive = await drive.loadLatestState();
    if (fromDrive) {
      fs.writeFileSync(STATE_FILE, JSON.stringify(fromDrive, null, 2));
      return res.json(fromDrive);
    }
    return res.status(404).json({ error: 'no state stored yet' });
  } catch (e) {
    return res.status(500).json({ error: e.message });
  }
});

// --- Server-side brain ---
app.post('/api/brain', async (req, res) => {
  const { message, history, state, memories } = req.body || {};
  if (!message) return res.status(400).json({ error: 'message required' });
  try {
    const result = await brain.chat({ message, history, state, memories });
    res.json(result);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// --- Agent task harness (UI-driven, streamed over WebSocket) --------------
// The UI calls this instead of you opening Termux. The task is passed to
// harness.sh as a SINGLE argv argument (via spawn, never a shell string), so a
// task description can never inject shell commands. Output is broadcast to every
// WS client so you watch her work inside the app. Gated by the /api key guard.
app.post('/api/kortana/task', (req, res) => {
  const task = String((req.body && req.body.task) || '').slice(0, 2000);
  if (!task.trim()) return res.status(400).json({ error: 'task required' });
  if (!fs.existsSync(HARNESS)) return res.status(500).json({ error: 'harness.sh missing' });
  const taskId = Date.now().toString(36);
  broadcast({ type: 'task_start', taskId, task });
  const child = spawn('bash', [HARNESS, task], { cwd: path.dirname(AGENT_MEMORY_DIR) });
  const relay = (stream, chan) =>
    stream.on('data', (buf) => {
      for (const line of buf.toString().split('\n')) {
        if (line.length) broadcast({ type: 'task_output', taskId, chan, line });
      }
    });
  relay(child.stdout, 'out');
  relay(child.stderr, 'err');
  child.on('close', (code) => broadcast({ type: 'task_end', taskId, code }));
  child.on('error', (e) => broadcast({ type: 'task_end', taskId, error: e.message }));
  res.json({ ok: true, taskId });
});

// --- Self-improvement loop: act -> verify -> curate -----------------------
// This is the realistic version. She can't retrain her weights, so instead she
// runs a guarded action, VERIFIES it with a checkable command, and only then
// records a lesson. Verified lessons flow into her prompt; unverified guesses
// age out. Curation keeps the store small so it never bloats the local model.

// Act: run a single guarded, allowlisted command and stream its output.
app.post('/api/kortana/run', async (req, res) => {
  const command = String((req.body && req.body.command) || '').slice(0, 2000);
  if (!command.trim()) return res.status(400).json({ error: 'command required' });
  const verdict = executor.classify(command);
  if (!verdict.allowed) {
    broadcast({ type: 'run_blocked', command, reason: verdict.reason });
    return res.status(403).json({ error: 'blocked', reason: verdict.reason });
  }
  const runId = Date.now().toString(36);
  broadcast({ type: 'run_start', runId, command });
  const result = await executor.run(command, {
    cwd: path.dirname(AGENT_MEMORY_DIR),
    onLine: (chan, line) => broadcast({ type: 'run_output', runId, chan, line }),
  });
  broadcast({ type: 'run_end', runId, code: result.code, timedOut: result.timedOut });
  res.json(result);
});

// Verify + learn: run a checkable command; record the lesson only if it passes.
// body: { lesson, verify, category?, source? }
app.post('/api/kortana/learn', async (req, res) => {
  const { lesson, verify, category, source } = req.body || {};
  if (!lesson || !verify) return res.status(400).json({ error: 'lesson and verify command required' });
  const verdict = executor.classify(verify);
  if (!verdict.allowed) return res.status(403).json({ error: 'verify blocked', reason: verdict.reason });
  const learnId = Date.now().toString(36);
  broadcast({ type: 'learn_start', learnId, lesson, verify });
  const result = await executor.run(String(verify).slice(0, 2000), {
    cwd: path.dirname(AGENT_MEMORY_DIR),
    onLine: (chan, line) => broadcast({ type: 'learn_output', learnId, chan, line }),
  });
  const passed = result.allowed && result.code === 0 && !result.timedOut;
  const recorded = memory.record({
    text: String(lesson).slice(0, 500),
    category: (category || 'GENERAL').toUpperCase().slice(0, 24),
    source: source || 'learn',
    verified: passed,
    evidence: `${passed ? 'verify passed' : 'verify failed'}: ${String(verify).slice(0, 200)}`,
  });
  broadcast({ type: 'learn_end', learnId, passed, status: recorded?.status });
  res.json({ passed, exitCode: result.code, lesson: recorded, stats: memory.stats() });
});

// Read her curated memory (verified lessons + pending guesses) for the UI.
app.get('/api/kortana/memory', (req, res) => {
  res.json({ stats: memory.stats(), lessons: memory.all() });
});

// Manually force a curation pass (also runs automatically on a timer).
app.post('/api/kortana/memory/curate', (req, res) => {
  res.json(memory.curate());
});

// UI reads her "brain" (norms + recent logs) to render it in-app, not the terminal.
app.get('/api/kortana/brain', (req, res) => {
  const tail = (p, n) => {
    try { return fs.readFileSync(p, 'utf8').split('\n').slice(-n).join('\n'); } catch { return ''; }
  };
  const whole = (p) => { try { return fs.readFileSync(p, 'utf8'); } catch { return ''; } };
  res.json({
    agents: whole(path.join(AGENT_MEMORY_DIR, 'AGENTS.md')),
    knowledgeLog: tail(path.join(AGENT_MEMORY_DIR, 'logs', 'knowledge.log'), 50),
    harnessLog: tail(path.join(AGENT_MEMORY_DIR, 'logs', 'harness.log'), 50),
    decisions: tail(path.join(AGENT_MEMORY_DIR, 'decisions.md'), 50),
  });
});

// --- Drive + health ---
app.get('/api/drive', async (req, res) => {
  try {
    res.json(await drive.usage());
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.get('/health', async (req, res) => {
  const cores = await brain.status();
  res.json({
    service: 'kortana-terminus',
    uptimeSeconds: Math.floor((Date.now() - startTime) / 1000),
    lastSync,
    awakeDevices: awake.size,
    cores,
    drive: { enabled: drive.enabled, lastSaveTime: drive.lastSaveTime },
  });
});

// --- WebSocket presence: Terminus knows when Kortana is awake ---
const server = http.createServer(app);
const wss = new WebSocketServer({ server });
const awake = new Set();

function broadcast(obj) {
  const msg = JSON.stringify(obj);
  for (const ws of wss.clients) {
    if (ws.readyState === 1) ws.send(msg);
  }
}

wss.on('connection', (ws, req) => {
  // WS uses the same key as the HTTP API (native clients send the header).
  if (API_KEY) {
    if (!keyMatches(req.headers['authorization'] || '')) {
      ws.close(4401, 'unauthorized');
      return;
    }
  }
  ws.deviceName = 'unknown';
  ws.on('message', (raw) => {
    try {
      const msg = JSON.parse(raw.toString());
      if (msg.type === 'awake') {
        ws.deviceName = msg.device || 'unknown';
        awake.add(ws);
        console.log(`[terminus] Kortana is awake on "${ws.deviceName}" (${awake.size} device(s) active)`);
        broadcast({ type: 'kortana_awake', device: ws.deviceName });
      } else if (msg.type === 'sleep') {
        awake.delete(ws);
        console.log(`[terminus] Kortana went to sleep on "${ws.deviceName}"`);
      }
    } catch { /* ignore malformed frames */ }
  });
  ws.on('close', () => {
    if (awake.delete(ws)) {
      console.log(`[terminus] "${ws.deviceName}" disconnected (${awake.size} device(s) active)`);
    }
  });
});

setInterval(() => broadcast({ type: 'heartbeat', at: Date.now(), awakeDevices: awake.size }), 60_000);

// Keep her memory curated (dedupe, age out stale guesses, cap size) so the
// prompt never grows until the local model chokes. Hourly is plenty.
setInterval(() => {
  try { memory.curate(); } catch (e) { console.warn('[memory] curate failed:', e.message); }
}, 3600_000);

// Fire due reminders to the app so she can nudge Daddy proactively.
setInterval(() => {
  try {
    for (const r of reminders.due()) {
      console.log(`[reminder] due: ${r.text}`);
      broadcast({ type: 'reminder', id: r.id, text: r.text, at: r.at });
    }
  } catch (e) { console.warn('[reminders] check failed:', e.message); }
}, 30_000);

// --- Boot ---
(async () => {
  await drive.init();
  // Without an API key the server only ever binds to localhost, so an open
  // API can never be reached from off the device. Set TERMINUS_API_KEY (and
  // HOST=0.0.0.0) to serve other devices on the LAN/internet.
  const host = API_KEY ? (process.env.HOST || '0.0.0.0') : '127.0.0.1';
  server.listen(PORT, host, () => {
    console.log(`[terminus] Kortana's Terminus server online at ${host}:${PORT}.`);
    if (!API_KEY) console.warn('[terminus] TERMINUS_API_KEY not set — bound to localhost only. Set a key to allow other devices to connect.');
  });
})();
