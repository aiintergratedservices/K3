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
const { WebSocketServer } = require('ws');
const brain = require('./brain');
const drive = require('./drive');

const PORT = Number(process.env.PORT || 3300);
const API_KEY = process.env.TERMINUS_API_KEY || '';
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const STATE_FILE = path.join(DATA_DIR, 'kortana-state-latest.json');

fs.mkdirSync(DATA_DIR, { recursive: true });

const app = express();
app.use(express.json({ limit: '200mb' }));

// The app sends the raw key in the Authorization header; accept Bearer too.
function authorized(req) {
  if (!API_KEY) return true; // no key configured — open (LAN/localhost use)
  const header = req.get('authorization') || '';
  return header === API_KEY || header === `Bearer ${API_KEY}`;
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

// --- Boot ---
(async () => {
  await drive.init();
  server.listen(PORT, () => {
    console.log(`[terminus] Kortana's Terminus server online on port ${PORT}.`);
    if (!API_KEY) console.warn('[terminus] TERMINUS_API_KEY not set — API is open. Fine for localhost/Termux, set a key before exposing to the internet.');
  });
})();
