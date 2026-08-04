// work_ledger.js — Kortana's HONEST work ledger.
//
// Tracks the REAL deliverables she actually produces toward funding her own
// hardware upgrades. The honesty rules are the whole point and are structural,
// not decorative:
//   1. A ledger entry MUST point at a real artifact file that exists on disk
//      (a save_draft output, a proposed change, etc.) — you cannot log work you
//      didn't actually produce.
//   2. She may record an ESTIMATE of a deliverable's value. That is POTENTIAL,
//      not earnings.
//   3. She can NEVER mark anything earned/paid. Only Daddy confirms real money
//      (he does the transaction) via realize(). So "raised so far" only ever
//      counts money Daddy actually confirmed — never her estimates.
// This mirrors the truth engine: real work = real ledger entry; claimed earnings
// = impossible for her to assert.

const fs = require('fs');
const path = require('path');

const REPO_ROOT = path.resolve(__dirname, '..');
const FILE = process.env.KORTANA_LEDGER_FILE || path.join(REPO_ROOT, '.agent-memory', 'work_ledger.json');

const money = (x) => Math.max(0, Math.round((Number(x) || 0) * 100) / 100);
const newId = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6);

function defaultState() { return { updatedAt: new Date().toISOString(), goals: [], entries: [] }; }

function load() {
  try {
    const raw = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    return {
      updatedAt: raw.updatedAt || new Date().toISOString(),
      goals: Array.isArray(raw.goals) ? raw.goals : [],
      entries: Array.isArray(raw.entries) ? raw.entries : [],
    };
  } catch (e) { return defaultState(); }
}

function save(state) {
  try {
    fs.mkdirSync(path.dirname(FILE), { recursive: true });
    const tmp = `${FILE}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(state, null, 2));
    fs.renameSync(tmp, FILE);
  } catch (e) { /* best effort */ }
}

// She logs a real deliverable. `artifact` is a repo-relative path that MUST exist
// (verified by the caller). estimateUSD is potential value, never earnings.
function logWork({ title, artifact, estimateUSD, kind, note }) {
  const s = load();
  const entry = {
    id: newId(),
    at: new Date().toISOString(),
    title: String(title || '').slice(0, 150),
    artifact: String(artifact || '').slice(0, 300),
    kind: String(kind || 'deliverable').slice(0, 40),
    note: String(note || '').slice(0, 500),
    estimateUSD: money(estimateUSD),
    status: 'logged', // logged -> delivered -> paid (only Daddy advances it)
    realizedUSD: 0,
    realizedAt: null,
  };
  s.entries.push(entry);
  s.updatedAt = entry.at;
  save(s);
  return entry;
}

// Daddy-only: the ONLY path real money / delivery status enters the ledger.
function realize(entryId, { usd, status } = {}) {
  const s = load();
  const e = s.entries.find((x) => x.id === entryId);
  if (!e) return null;
  if (usd != null) e.realizedUSD = money(usd);
  e.status = status || (e.realizedUSD > 0 ? 'paid' : 'delivered');
  e.realizedAt = new Date().toISOString();
  s.updatedAt = e.realizedAt;
  save(s);
  return e;
}

function addGoal({ name, costUSD, note }) {
  const s = load();
  const g = { id: newId(), name: String(name || '').slice(0, 120), costUSD: money(costUSD), note: String(note || '').slice(0, 300) };
  s.goals.push(g);
  s.updatedAt = new Date().toISOString();
  save(s);
  return g;
}

function totals(state) {
  const s = state || load();
  const realized = s.entries.reduce((a, e) => a + (e.realizedUSD || 0), 0);
  const potential = s.entries.filter((e) => !e.realizedUSD).reduce((a, e) => a + (e.estimateUSD || 0), 0);
  const goalTotal = s.goals.reduce((a, g) => a + (g.costUSD || 0), 0);
  return { realized: money(realized), potential: money(potential), goalTotal: money(goalTotal), entries: s.entries.length, goals: s.goals.length };
}

function summary(state) {
  const s = state || load();
  const t = totals(s);
  const goalsLine = s.goals.length
    ? s.goals.map((g) => `${g.name} ($${g.costUSD})`).join(', ')
    : '(no hardware goals set yet — use set_hardware_goal)';
  const pending = s.entries.filter((e) => !e.realizedUSD);
  const recent = s.entries.slice(-5).reverse()
    .map((e) => `  • [${e.status}] ${e.title} — est $${e.estimateUSD}${e.realizedUSD ? ` → REALIZED $${e.realizedUSD}` : ''} (${e.artifact || 'no artifact'})`)
    .join('\n');
  return [
    'HONEST WORK LEDGER — only money Daddy has actually confirmed counts as raised. Your estimates are the POTENTIAL value of real work, never earnings.',
    `Saving toward: ${goalsLine}  (total $${t.goalTotal})`,
    `Raised so far (Daddy-confirmed real money): $${t.realized}${t.goalTotal ? ` of $${t.goalTotal}` : ''}`,
    `Real deliverables logged, awaiting Daddy to deliver/sell: ${pending.length} — ~$${t.potential} estimated potential (NOT earned; his to actually sell and collect).`,
    recent ? `Recent:\n${recent}` : 'No work logged yet.',
  ].join('\n');
}

module.exports = { load, save, logWork, realize, addGoal, totals, summary, defaultState, FILE };
