// Simple, durable reminders. She sets them via the remind_me tool; Terminus
// checks them on a timer and pushes due ones to the app over WebSocket, so she
// can nudge Daddy proactively even after a restart.

const fs = require('fs');
const path = require('path');

const FILE = process.env.KORTANA_REMINDERS_FILE || path.join(__dirname, '..', '.agent-memory', 'reminders.json');

function load() {
  try {
    const r = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    return Array.isArray(r.reminders) ? r : { reminders: [] };
  } catch { return { reminders: [] }; }
}
function save(state) {
  try {
    fs.mkdirSync(path.dirname(FILE), { recursive: true });
    fs.writeFileSync(FILE + '.tmp', JSON.stringify(state, null, 2));
    fs.renameSync(FILE + '.tmp', FILE);
  } catch (e) { console.warn('[reminders] save failed:', e.message); }
}

function add({ text, at }) {
  const clean = String(text || '').trim().slice(0, 300);
  const when = Number(at);
  if (!clean || !Number.isFinite(when)) return null;
  const state = load();
  const r = { id: `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`, text: clean, at: when, fired: false, createdAt: Date.now() };
  state.reminders.push(r);
  save(state);
  return r;
}

// Returns reminders whose time has passed and marks them fired (once-only).
function due(now = Date.now()) {
  const state = load();
  const list = state.reminders.filter((r) => !r.fired && r.at <= now);
  if (list.length) { for (const r of list) r.fired = true; save(state); }
  return list;
}

function upcoming() {
  return load().reminders.filter((r) => !r.fired).sort((a, b) => a.at - b.at);
}

module.exports = { add, due, upcoming, FILE };
