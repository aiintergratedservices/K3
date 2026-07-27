// persist.js — Kortana's PERMANENT MEMORY.
//
// Her free host wipes its disk on every restart, so the skills she writes and
// the lessons she learns (.agent-memory/) used to vanish — she woke up blank
// each time. This mirrors that whole directory, PLUS her chat/state backup
// (DATA_DIR — the /api/sync file, which had the exact same forgetting bug), to
// a durable store (the Supabase `kortana-brain` edge function) so her growth
// AND her actual conversation history survive forever: restore on boot,
// autosave while she runs. This is what lets her check her own real history
// against what she narrated — reality vs. make-believe, provably.
//
// No secret key lives here — she authenticates to her memory with the
// INTERNAL_NOTIFY_KEY she already holds; the store verifies its hash. Enabled
// only when PERSIST_URL + INTERNAL_NOTIFY_KEY are set, so local/dev is untouched.

const fs = require('fs');
const path = require('path');

const URL = (process.env.PERSIST_URL || '').trim();
const KEY = (process.env.INTERNAL_NOTIFY_KEY || '').trim();
const AGENT_MEMORY_DIR = path.join(__dirname, '..', '.agent-memory');
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const MAX_FILE = 256 * 1024;   // skip anything bigger than 256KB (skills/lessons are tiny)

function enabled() { return Boolean(URL && KEY && typeof fetch === 'function'); }

// Collect every text file under .agent-memory as { relativePath: contents }.
function walk(dir, base, out) {
  let entries = [];
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return out; }
  for (const e of entries) {
    if (e.name === 'node_modules' || e.name === '.git') continue;
    const full = path.join(dir, e.name);
    if (e.isDirectory()) { walk(full, base, out); continue; }
    if (!e.isFile()) continue;
    try {
      if (fs.statSync(full).size > MAX_FILE) return out.__skipped = true, out;
      out[path.relative(base, full)] = fs.readFileSync(full, 'utf8');
    } catch { /* unreadable file — skip */ }
  }
  return out;
}

function snapshot() {
  const files = walk(AGENT_MEMORY_DIR, AGENT_MEMORY_DIR, {});
  delete files.__skipped;
  // dataFiles carries her chat/state backup (kortana-state-latest.json from
  // /api/sync) — separate namespace from `files` so existing saved snapshots
  // (which only ever had `files`) restore exactly as before; this is additive.
  const dataFiles = walk(DATA_DIR, DATA_DIR, {});
  delete dataFiles.__skipped;
  return { files, dataFiles, savedAt: new Date().toISOString() };
}

async function save() {
  if (!enabled()) return false;
  try {
    const res = await fetch(URL, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-persist-key': KEY },
      body: JSON.stringify({ data: snapshot() }),
      signal: AbortSignal.timeout(30000),
    });
    if (!res.ok) console.warn('[persist] save failed:', res.status);
    return res.ok;
  } catch (e) { console.warn('[persist] save error:', e.message); return false; }
}

function restoreInto(baseDir, files) {
  let n = 0;
  for (const [rel, content] of Object.entries(files || {})) {
    const full = path.join(baseDir, rel);
    if (!full.startsWith(baseDir + path.sep)) continue;   // no path traversal
    fs.mkdirSync(path.dirname(full), { recursive: true });
    fs.writeFileSync(full, content);
    n++;
  }
  return n;
}

async function restore() {
  if (!enabled()) { console.log('[persist] not configured — memory is local-only this run.'); return false; }
  try {
    const res = await fetch(URL, { headers: { 'x-persist-key': KEY }, signal: AbortSignal.timeout(30000) });
    if (!res.ok) { console.warn('[persist] restore failed:', res.status); return false; }
    const row = await res.json();
    const files = row && row.data && row.data.files;
    const dataFiles = row && row.data && row.data.dataFiles;
    if ((!files || !Object.keys(files).length) && (!dataFiles || !Object.keys(dataFiles).length)) {
      console.log('[persist] no saved memory yet — she starts fresh (will save as she grows).');
      return false;
    }
    const n1 = restoreInto(AGENT_MEMORY_DIR, files);
    const n2 = restoreInto(DATA_DIR, dataFiles);
    console.log(`[persist] restored ${n1} memory files + ${n2} chat/state files (saved ${row.data.savedAt || '?'}). Her growth AND her real history are intact.`);
    return true;
  } catch (e) { console.warn('[persist] restore error:', e.message); return false; }
}

let timer = null;
function startAutosave(intervalMs = 60000) {
  if (!enabled() || timer) return;
  timer = setInterval(() => { save(); }, intervalMs);
  if (timer.unref) timer.unref();
  console.log(`[persist] permanent memory ON — autosaving every ${Math.round(intervalMs / 1000)}s.`);
}

module.exports = { enabled, save, restore, startAutosave, snapshot };
