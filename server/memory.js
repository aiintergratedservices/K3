// Kortana's verified, self-curating memory.
//
// This is the honest core of "self-improvement" for a fixed-weight model: she
// can't retrain her brain, but she CAN accumulate lessons — and only ones that
// were actually verified — then keep that store small enough to fit her prompt.
//
// A lesson is only trusted ("verified") if something checkable confirmed it
// (a test/lint/command exited 0). Unverified guesses are kept separately as
// "pending" with a short life, and are never injected into her prompt as fact.
// Curation dedupes, ages out stale guesses, and caps the store so the prompt
// can't grow until the local model chokes (the 2960-token stalls we saw).

const fs = require('fs');
const path = require('path');

const MEM_FILE =
  process.env.KORTANA_MEM_FILE ||
  path.join(__dirname, '..', '.agent-memory', 'lessons.json');

const MAX_VERIFIED = Number(process.env.KORTANA_MAX_VERIFIED || 60);
const MAX_PENDING = Number(process.env.KORTANA_MAX_PENDING || 40);
const PENDING_TTL_MS = Number(process.env.KORTANA_PENDING_TTL_MS || 7 * 24 * 3600 * 1000);
const PROMPT_LESSONS = Number(process.env.KORTANA_PROMPT_LESSONS || 12);
const PROMPT_MAX_CHARS = 1800;

function now() { return Date.now(); }

function normalize(text) {
  return String(text || '')
    .toLowerCase()
    .replace(/\s+/g, ' ')
    .replace(/[.!?,;:]+$/, '')
    .trim()
    .slice(0, 200);
}

function load() {
  try {
    const raw = JSON.parse(fs.readFileSync(MEM_FILE, 'utf8'));
    return Array.isArray(raw.lessons) ? raw : { lessons: [] };
  } catch {
    return { lessons: [] };
  }
}

// Atomic write so a crash mid-save can't corrupt her memory file.
function save(state) {
  try {
    fs.mkdirSync(path.dirname(MEM_FILE), { recursive: true });
    const tmp = `${MEM_FILE}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(state, null, 2));
    fs.renameSync(tmp, MEM_FILE);
  } catch (e) {
    console.warn('[memory] save failed:', e.message);
  }
}

function score(l) {
  const verified = l.status === 'verified' ? 1000 : 0;
  const conf = (l.confidence || 0) * 100;
  const used = Math.min(l.uses || 0, 20) * 3;
  // Small recency nudge so ties favour what she touched most recently.
  const ageDays = (now() - (l.updatedAt || l.createdAt || 0)) / 86_400_000;
  const recency = Math.max(0, 10 - ageDays);
  return verified + conf + used + recency;
}

// Record (or reinforce) a lesson. verified===true only when a check confirmed
// it — that's the gate that keeps her from "learning" confident nonsense.
function record({ text, category = 'GENERAL', source = 'task', verified = false, evidence = '', confidence } = {}) {
  const clean = String(text || '').trim();
  if (!clean) return null;
  const state = load();
  const key = normalize(clean);
  const existing = state.lessons.find((l) => normalize(l.text) === key);
  const conf = confidence != null ? confidence : verified ? 0.9 : 0.3;

  let lesson;
  if (existing) {
    // Reinforce: bump usage/confidence, and upgrade to verified if it now is.
    existing.uses = (existing.uses || 0) + 1;
    existing.confidence = Math.max(existing.confidence || 0, conf);
    existing.updatedAt = now();
    if (verified && existing.status !== 'verified') {
      existing.status = 'verified';
      existing.evidence = evidence || existing.evidence;
    }
    lesson = existing;
  } else {
    lesson = {
      id: `${now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`,
      text: clean,
      category,
      source,
      status: verified ? 'verified' : 'pending',
      confidence: conf,
      evidence,
      uses: 1,
      createdAt: now(),
      updatedAt: now(),
    };
    state.lessons.push(lesson);
  }
  save(curateState(state));
  return lesson;
}

// Promote a previously-pending lesson to verified once a check confirms it.
function verify(idOrText, evidence = 'confirmed') {
  const state = load();
  const key = normalize(idOrText);
  const l = state.lessons.find((x) => x.id === idOrText || normalize(x.text) === key);
  if (!l) return null;
  l.status = 'verified';
  l.confidence = Math.max(l.confidence || 0, 0.9);
  l.evidence = evidence;
  l.updatedAt = now();
  save(curateState(state));
  return l;
}

// Dedupe, age out stale guesses, cap each tier. Pure function on the state
// object; callers persist the result.
function curateState(state) {
  const seen = new Map();
  for (const l of state.lessons) {
    const key = normalize(l.text);
    const prev = seen.get(key);
    if (!prev) { seen.set(key, l); continue; }
    // Merge duplicates: keep the stronger, absorb usage.
    const keep = score(l) >= score(prev) ? l : prev;
    const drop = keep === l ? prev : l;
    keep.uses = (keep.uses || 0) + (drop.uses || 0);
    if (drop.status === 'verified') keep.status = 'verified';
    keep.confidence = Math.max(keep.confidence || 0, drop.confidence || 0);
    seen.set(key, keep);
  }
  let lessons = [...seen.values()];

  const cutoff = now() - PENDING_TTL_MS;
  lessons = lessons.filter((l) => l.status === 'verified' || (l.updatedAt || 0) >= cutoff || (l.uses || 0) >= 2);

  const verified = lessons.filter((l) => l.status === 'verified').sort((a, b) => score(b) - score(a)).slice(0, MAX_VERIFIED);
  const pending = lessons.filter((l) => l.status !== 'verified').sort((a, b) => score(b) - score(a)).slice(0, MAX_PENDING);
  return { lessons: [...verified, ...pending] };
}

function curate() {
  const before = load();
  const after = curateState(before);
  save(after);
  return { before: before.lessons.length, after: after.lessons.length };
}

// The bounded block injected into her system prompt: verified lessons only,
// capped by count AND characters so it can never bloat the local model again.
function forPrompt(limit = PROMPT_LESSONS) {
  const verified = load().lessons
    .filter((l) => l.status === 'verified')
    .sort((a, b) => score(b) - score(a))
    .slice(0, limit);
  if (!verified.length) return '';
  let out = '';
  for (const l of verified) {
    const line = `• [${l.category}] ${l.text}\n`;
    if (out.length + line.length > PROMPT_MAX_CHARS) break;
    out += line;
  }
  return out
    ? '\n\nVerified lessons you have learned and confirmed (apply them; do not recite this list):\n' + out.trimEnd()
    : '';
}

function all() { return load().lessons; }

function stats() {
  const l = load().lessons;
  return {
    total: l.length,
    verified: l.filter((x) => x.status === 'verified').length,
    pending: l.filter((x) => x.status !== 'verified').length,
  };
}

module.exports = { record, verify, curate, forPrompt, all, stats, MEM_FILE };
