// Goals Daddy sets, that she pursues on her own after that — the "I set the
// end goal, she figures out how" system. A goal is NOT considered done just
// because she says so in prose; it only flips to 'done' when a pursuit cycle
// both (a) actually called a tool that turn and (b) she explicitly marked it
// complete with evidence. Otherwise it just accumulates an honest progress
// log and stays active.

const fs = require('fs');
const path = require('path');

const REPO_ROOT = path.join(__dirname, '..');
const GOALS_FILE = path.join(REPO_ROOT, '.agent-memory', 'goals.json');

function load() {
  try {
    return JSON.parse(fs.readFileSync(GOALS_FILE, 'utf8'));
  } catch {
    return [];
  }
}

function save(goals) {
  fs.mkdirSync(path.dirname(GOALS_FILE), { recursive: true });
  fs.writeFileSync(GOALS_FILE, JSON.stringify(goals, null, 2));
}

function add(text) {
  const clean = String(text || '').trim().slice(0, 500);
  if (!clean) return null;
  const goals = load();
  const goal = {
    id: Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
    text: clean,
    status: 'active', // active | done | blocked
    createdAt: new Date().toISOString(),
    log: [],
  };
  goals.push(goal);
  save(goals);
  return goal;
}

function list() {
  return load();
}

function active() {
  return load().filter((g) => g.status === 'active');
}

function appendLog(id, entry) {
  const goals = load();
  const g = goals.find((x) => x.id === id);
  if (!g) return null;
  g.log.push({ at: new Date().toISOString(), ...entry });
  if (g.log.length > 40) g.log = g.log.slice(-40); // cap so one goal can't grow forever
  save(goals);
  return g;
}

function setStatus(id, status, note) {
  const goals = load();
  const g = goals.find((x) => x.id === id);
  if (!g) return null;
  g.status = status;
  g.log.push({ at: new Date().toISOString(), note: note || `status -> ${status}` });
  save(goals);
  return g;
}

module.exports = { add, list, active, appendLog, setStatus };
