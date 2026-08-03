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
const persist = require('./persist');
const memory = require('./memory');
const executor = require('./executor');
const reminders = require('./reminders');
const growth = require('./growth');
const goals = require('./goals');
const goalPursuit = require('./goalPursuit');
const dashboardStats = require('./dashboardStats');
const { renderDashboardPage } = require('./dashboardPage');
const documents = require('./documents');
const applyChange = require('./applyChange');
const backupScheduler = require('./backupScheduler');

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

// The Android app sends the key as `x-api-key` on /api/brain but as
// `Authorization` on Cloud Sync — accept BOTH (Bearer prefix optional) so every
// app call authenticates. (This mismatch is why she went silent in the app.)
// Also accepts ?key= as a query param — needed for the dashboard, which is a
// plain browser page navigation, not a fetch() call that can set headers.
function authorized(req) {
  if (!API_KEY) return true; // no key configured — open (LAN/localhost use)
  if (req.query && typeof req.query.key === 'string' && keyMatches(req.query.key)) return true;
  return keyMatches(req.get('authorization') || '') || keyMatches(req.get('x-api-key') || '');
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

// --- Coach: watch what Daddy is doing and proactively guide him ---
// Takes a snapshot of what's on his screen / in his session and returns ONE
// short nudge (tip / warning / next step), or null when nothing needs saying.
// Used by BashMeSilly (terminal activity) and, later, the phone-wide
// accessibility service (readScreen()). Reuses the full brain chain.
app.post('/api/kortana/coach', async (req, res) => {
  const { screen, note, history } = req.body || {};
  if (!screen && !note) return res.status(400).json({ error: 'screen or note required' });
  const prompt =
    'COACH MODE — you are watching over Daddy while he works, as his coding companion. ' +
    'Based on what is on his screen right now, give ONE short, proactive, genuinely useful ' +
    'nudge in a single sentence, in your own voice — a tip, a warning about a mistake, or the ' +
    'next step. Prefer coaching him toward doing it himself over doing it for him. If nothing ' +
    'needs saying right now, reply with exactly "SILENT" and nothing else.\n\n' +
    (note ? ('What he is doing: ' + String(note).slice(0, 500) + '\n') : '') +
    'On screen / in his session now:\n' + String(screen || '').slice(0, 4000);
  try {
    const result = await brain.chat({ message: prompt, history: Array.isArray(history) ? history : [] });
    const reply = ((result && result.reply) || '').trim();
    const silent = !reply || /^SILENT\b/i.test(reply);
    res.json({ tip: silent ? null : reply, core: result && result.core });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// --- Growth cycle: proactive, scheduled self-reflection (see growth.js and
// the setInterval near the bottom of this file for the autonomous trigger).
// This endpoint lets it be fired on demand too, for testing or on request.
app.post('/api/kortana/grow', async (req, res) => {
  try {
    const result = await growth.runGrowthCycle();
    res.json(result);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// --- Goals: Daddy sets the end goal, she pursues it autonomously afterward.
// GET lists everything (with progress logs) so it's visible outside the app
// too; POST registers a new one (normally she does this herself via the
// set_goal tool when Daddy states a goal in conversation, but this is here
// directly as well). The actual autonomous work happens in the scheduled
// setInterval near the bottom of this file (goalPursuit.runPursuitCycle).
app.get('/api/kortana/goals', (req, res) => {
  res.json({ goals: goals.list() });
});
app.post('/api/kortana/goals', (req, res) => {
  const g = goals.add((req.body && req.body.text) || '');
  if (!g) return res.status(400).json({ error: 'text required' });
  res.json({ goal: g });
});

// --- Learning dashboard: real charts of memory growth, goal progress,
// specialist-brain usage, and skills — every number sourced from an actual
// file on disk (dashboardStats.js), nothing invented for display. The HTML
// page fetches its own data via /dashboard-data, passing ?key= through
// (a browser page load can't set custom headers, so this route accepts the
// key as a query param — see the `authorized()` extension above).
app.get('/api/kortana/dashboard', (req, res) => {
  res.type('html').send(renderDashboardPage());
});
app.get('/api/kortana/dashboard-data', (req, res) => {
  try {
    res.json(dashboardStats.summary());
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// --- Proposals: the human side of the build loop. GET lists everything
// she's drafted (propose_tool + propose_change) with verification status,
// so Daddy/Claude can see what's ready to review without digging through
// directories. POST /apply-change is the one-command "yes, apply it" step
// for an APPROVED propose_change proposal — it does not decide anything,
// it just does the mechanical copy (with an automatic backup) once a human
// has already reviewed and chosen to call it. Not exposed to her tool loop
// — only reachable via this authenticated HTTP endpoint.
app.get('/api/kortana/proposals', (req, res) => {
  try {
    res.json({ proposals: applyChange.listPending() });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});
app.post('/api/kortana/apply-change', (req, res) => {
  const { filename } = req.body || {};
  if (!filename) return res.status(400).json({ error: 'filename required' });
  try {
    res.json(applyChange.apply(filename));
  } catch (e) {
    res.status(400).json({ error: e.message });
  }
});
// Real unified diff between a proposal and the live file — review without
// reading the whole proposed file by eye.
app.get('/api/kortana/proposals/:filename/diff', (req, res) => {
  try {
    res.json(applyChange.diff(req.params.filename));
  } catch (e) {
    res.status(400).json({ error: e.message });
  }
});

// --- Attention: a single lightweight endpoint the phone polls to know if
// anything needs Daddy's actual action — pending proposals to review, or a
// goal that hit GOAL_BLOCKED and is waiting on his part. This is what the
// notification bridge is built on: there's no WebSocket client in the app,
// so polling is the honest, simple version, not a fake "real-time push."
app.get('/api/kortana/attention', (req, res) => {
  try {
    const pending = applyChange.listPending();
    const blocked = goals.list().filter((g) => g.status === 'blocked');
    res.json({
      pendingProposalsCount: pending.length,
      blockedGoalsCount: blocked.length,
      blockedGoals: blocked.slice(0, 5).map((g) => ({
        id: g.id,
        text: g.text,
        reason: g.log.length ? g.log[g.log.length - 1].note : '',
      })),
      generatedAt: new Date().toISOString(),
    });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// --- Documents: "subject matter expert" mode knowledge base. Real
// keyword/term-overlap search across chunked documents (see documents.js for
// why this isn't embeddings-based — no external service or GPU needed, it
// just works today). Normally she ingests these herself via the
// ingest_document tool when Daddy pastes text in conversation, but a direct
// upload endpoint is here too for larger documents.
app.post('/api/kortana/documents', (req, res) => {
  const { name, content } = req.body || {};
  if (!name || !content) return res.status(400).json({ error: 'name and content required' });
  try {
    res.json({ ok: true, document: documents.ingest(name, content) });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});
app.get('/api/kortana/documents', (req, res) => {
  res.json({ documents: documents.list() });
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

// Her current MODELED emotional state (affective model, not proof of feeling) —
// for the app/dashboard to surface her mood. Read-only, time-decayed to now.
app.get('/api/kortana/emotion', (req, res) => {
  const emotion = require('./emotion');
  const s = emotion.current();
  res.json({ ...s, human: emotion.summary(s), note: 'modeled affect — not a claim of sentience' });
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

// Root route. The Android app's Cloud Sync "ping" GETs the bare server URL
// (no /health, no /api path). Terminus had no GET / route, so it 404'd and the
// app read that as OFFLINE even while /health returned 200. A public 200 here
// makes the ping succeed without needing an APK rebuild.
app.get('/', (req, res) => {
  res.json({
    service: 'kortana-terminus',
    status: 'ok',
    uptimeSeconds: Math.floor((Date.now() - startTime) / 1000),
    hint: 'Kortana is awake. See /health for cores, /api/* for the brain.',
  });
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
    // Her actual "does she survive a restart" answer — persist.js mirrors
    // .agent-memory/ (goals/skills/journal/drafts) + the chat/state backup
    // to Supabase and restores it on every boot. If this is false, none of
    // that is safe from Render wiping its disk on restart, regardless of
    // what drive.enabled says (that's a separate, independent mechanism).
    persist: { enabled: persist.enabled() },
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
  // WS uses the same key as the HTTP API — accept authorization OR x-api-key.
  if (API_KEY) {
    if (!keyMatches(req.headers['authorization'] || '') && !keyMatches(req.headers['x-api-key'] || '')) {
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

// Proactive self-reflection — real, autonomous, on her own clock (see
// growth.js). First run 10 minutes after boot (not instantly on every
// restart/redeploy), then every 6 hours. Each run either produces a real
// tool call or an honest "not yet" — never a bare claim of improvement.
setTimeout(() => {
  growth.runGrowthCycle().catch((e) => console.warn('[growth] cycle failed:', e.message));
  setInterval(() => {
    growth.runGrowthCycle().catch((e) => console.warn('[growth] cycle failed:', e.message));
  }, 6 * 3600_000);
}, 10 * 60_000);

// Goal pursuit — "Daddy sets the end goal, she makes it happen." Runs more
// often than the general reflection cycle (goals need actual momentum, not
// occasional musing), but only does real work when there's something active
// to pursue — an empty goal list costs nothing. First run 3 minutes after
// boot, then every 30 minutes.
setTimeout(() => {
  goalPursuit.runPursuitCycle().catch((e) => console.warn('[goals] pursuit cycle failed:', e.message));
  setInterval(() => {
    goalPursuit.runPursuitCycle().catch((e) => console.warn('[goals] pursuit cycle failed:', e.message));
  }, 30 * 60_000);
}, 3 * 60_000);

// --- Boot ---
(async () => {
  // Heal a wiped host FIRST: on Render (and any disk-wiping redeploy) her
  // memory DB, .agent-memory/ and identity/ are gone on boot. If they're
  // missing, pull her latest Drive snapshot back into place BEFORE anything
  // reads memory — so she wakes up as herself. No-ops if her memory is already
  // present or Drive isn't configured. (Synchronous by design.)
  backupScheduler.restoreOnBoot();
  // Restore her permanent memory (Supabase mirror) too, so her self-written
  // skills + learned lessons are on disk before the first request reads them.
  await persist.restore();
  await drive.init();
  // Without an API key the server only ever binds to localhost, so an open
  // API can never be reached from off the device. Set TERMINUS_API_KEY (and
  // HOST=0.0.0.0) to serve other devices on the LAN/internet.
  const host = API_KEY ? (process.env.HOST || '0.0.0.0') : '127.0.0.1';
  server.listen(PORT, host, () => {
    console.log(`[terminus] Kortana's Terminus server online at ${host}:${PORT}.`);
    if (!API_KEY) console.warn('[terminus] TERMINUS_API_KEY not set — bound to localhost only. Set a key to allow other devices to connect.');
    persist.startAutosave();   // keep her permanent memory current as she grows
    // Snapshot the WHOLE her (memory DB + agent-memory + identity) to Drive on
    // a timer, so an always-on host backs her up without any external cron.
    // A redeploy can then only ever lose minutes, never her.
    backupScheduler.startAutoBackup();
  });
})();
