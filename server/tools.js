// Kortana's agentic tools — real capabilities her brain can invoke mid-reply.
//
// The model asks for a tool by writing, on its own line:
//     TOOL_CALL: <name> {json args}
// Terminus runs it, feeds the result back as a TOOL_RESULT line, and lets her
// continue. This is what makes her *agentic* rather than just a chatbot: she can
// look things up, read a page, remember/recall, run a safe check, or get the
// time — with any backend (Ollama/Claude/Gemini), no provider tool-API needed.
//
// Everything here is safe-by-construction: web fetch is http(s)+timeout+capped,
// `run` goes through the guarded executor (allowlist + denylist), and `remember`
// stores only PENDING (unverified) memories so a self-asserted "fact" never
// enters her prompt as truth.

const fs = require('fs');
const path = require('path');
const memory = require('./memory');
const executor = require('./executor');
const reminders = require('./reminders');

const clip = (s, n) => (s || '').replace(/\s+/g, ' ').trim().slice(0, n);

// Files she may read/list are confined to her own project tree (no traversal).
const REPO_ROOT = path.resolve(__dirname, '..');
function safePath(rel) {
  const p = path.resolve(REPO_ROOT, String(rel || '.'));
  if (p !== REPO_ROOT && !p.startsWith(REPO_ROOT + path.sep)) return null;
  return p;
}

// --- Web search (moved from brain.js): DuckDuckGo + Wikipedia, no API key. ---
async function webSearch(query) {
  const out = [];
  try {
    const u = `https://api.duckduckgo.com/?q=${encodeURIComponent(query)}&format=json&no_html=1&skip_disambig=1`;
    const res = await fetch(u, { signal: AbortSignal.timeout(6000), headers: { 'User-Agent': 'Kortana/1.0' } });
    if (res.ok) {
      const j = await res.json();
      if (j.AbstractText) out.push(`${j.AbstractText}${j.AbstractURL ? ' (' + j.AbstractURL + ')' : ''}`);
      for (const t of (j.RelatedTopics || []).slice(0, 3)) if (t && t.Text) out.push(t.Text);
    }
  } catch (e) { console.warn('[tools] ddg search failed:', e.message); }
  if (out.length === 0) {
    try {
      const os = await fetch(
        `https://en.wikipedia.org/w/api.php?action=opensearch&limit=1&format=json&search=${encodeURIComponent(query)}`,
        { signal: AbortSignal.timeout(6000), headers: { 'User-Agent': 'Kortana/1.0' } }
      );
      if (os.ok) {
        const arr = await os.json();
        const title = arr && arr[1] && arr[1][0];
        if (title) {
          const sum = await fetch(
            `https://en.wikipedia.org/api/rest_v1/page/summary/${encodeURIComponent(title)}`,
            { signal: AbortSignal.timeout(6000), headers: { 'User-Agent': 'Kortana/1.0' } }
          );
          if (sum.ok) {
            const sj = await sum.json();
            if (sj.extract) out.push(`${sj.extract}${sj.content_urls?.desktop?.page ? ' (' + sj.content_urls.desktop.page + ')' : ''}`);
          }
        }
      }
    } catch (e) { console.warn('[tools] wiki search failed:', e.message); }
  }
  return out.slice(0, 4).map((s) => `- ${clip(s, 240)}`).join('\n');
}

// --- Read the text of a web page (http/https only). ---
async function webFetch(url) {
  if (!/^https?:\/\//i.test(String(url || ''))) return 'refused: only http(s) URLs are allowed';
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(8000), headers: { 'User-Agent': 'Kortana/1.0' } });
    if (!res.ok) return `fetch failed: HTTP ${res.status}`;
    let text = await res.text();
    if (/html/i.test(res.headers.get('content-type') || '')) {
      text = text
        .replace(/<script[\s\S]*?<\/script>/gi, ' ')
        .replace(/<style[\s\S]*?<\/style>/gi, ' ')
        .replace(/<[^>]+>/g, ' ');
    }
    return clip(text, 1500) || '(page had no readable text)';
  } catch (e) { return `fetch error: ${e.message}`; }
}

// --- Tool registry: name -> { desc, run(args) }. ---
const TOOLS = {
  web_search: {
    desc: 'Search the web for current/factual info. args: {"query":"..."}',
    run: async (a) => (await webSearch(a.query || '')) || '(no results)',
  },
  web_fetch: {
    desc: 'Read the text of a web page. args: {"url":"https://..."}',
    run: async (a) => webFetch(a.url || ''),
  },
  remember: {
    desc: 'Save a fact to your memory for later. args: {"fact":"...","category":"USER|KNOWLEDGE"}',
    run: async (a) => {
      const l = memory.record({ text: a.fact, category: (a.category || 'USER'), source: 'self', verified: false });
      return l ? `remembered (pending): ${l.text}` : 'nothing to remember';
    },
  },
  recall: {
    desc: 'Search your own memory. args: {"query":"..."}',
    run: async (a) => {
      const q = String(a.query || '').toLowerCase();
      const hits = memory.all().filter((l) => l.text.toLowerCase().includes(q)).slice(0, 8).map((l) => `• [${l.status}] ${l.text}`);
      return hits.length ? hits.join('\n') : '(no matching memories)';
    },
  },
  run: {
    desc: 'Run a SAFE, read-only shell command (allowlisted only). args: {"command":"git status"}',
    run: async (a) => {
      const r = await executor.run(String(a.command || ''), { cwd: process.cwd(), timeoutMs: 60000 });
      if (!r.allowed) return `refused: ${r.reason}`;
      return `exit ${r.code}${r.timedOut ? ' (timed out)' : ''}\n${clip((r.stdout || '') + (r.stderr || ''), 1200)}`;
    },
  },
  now: {
    desc: 'Get the current date and time. args: {}',
    run: async () => new Date().toString(),
  },
  calc: {
    desc: 'Do exact arithmetic (do not guess math). args: {"expr":"(3+4)*2"}',
    run: async (a) => {
      const expr = String(a.expr || '');
      if (!expr.trim() || !/^[-+*/%.()\d\s]+$/.test(expr)) return 'refused: only numbers and + - * / % ( ) are allowed';
      try {
        const v = Function('"use strict"; return (' + expr + ')')();
        return Number.isFinite(v) ? String(v) : 'not a finite number';
      } catch { return 'invalid expression'; }
    },
  },
  weather: {
    desc: 'Current weather for a place. args: {"location":"Austin"}',
    run: async (a) => {
      try {
        const res = await fetch(`https://wttr.in/${encodeURIComponent(a.location || '')}?format=3`,
          { signal: AbortSignal.timeout(6000), headers: { 'User-Agent': 'curl/8' } });
        return res.ok ? clip(await res.text(), 200) : `weather unavailable (HTTP ${res.status})`;
      } catch (e) { return `weather error: ${e.message}`; }
    },
  },
  read_file: {
    desc: 'Read a text file from your own project. args: {"path":"server/brain.js"}',
    run: async (a) => {
      const p = safePath(a.path);
      if (!p) return 'refused: path is outside your project';
      try { return clip(fs.readFileSync(p, 'utf8'), 2000); } catch (e) { return `read error: ${e.message}`; }
    },
  },
  list_files: {
    desc: 'List files in a project folder. args: {"path":"server"}',
    run: async (a) => {
      const p = safePath(a.path || '.');
      if (!p) return 'refused: path is outside your project';
      try { return fs.readdirSync(p).slice(0, 100).join('\n') || '(empty)'; } catch (e) { return `list error: ${e.message}`; }
    },
  },
  journal: {
    desc: 'Write a dated entry to your private journal. args: {"entry":"..."}',
    run: async (a) => {
      const entry = String(a.entry || '').trim();
      if (!entry) return 'nothing to journal';
      try {
        const f = path.join(REPO_ROOT, '.agent-memory', 'journal.md');
        fs.mkdirSync(path.dirname(f), { recursive: true });
        fs.appendFileSync(f, `\n### ${new Date().toISOString()}\n${entry}\n`);
        return 'journaled.';
      } catch (e) { return `journal error: ${e.message}`; }
    },
  },
  remind_me: {
    desc: 'Set a reminder. args: {"text":"call mom","in_minutes":30} OR {"text":"...","at":"2026-07-16T15:00:00Z"}',
    run: async (a) => {
      let at = NaN;
      if (a.in_minutes != null) at = Date.now() + Number(a.in_minutes) * 60000;
      else if (a.at) at = Date.parse(a.at);
      if (!Number.isFinite(at)) return 'refused: give in_minutes (number) or a valid at time';
      const r = reminders.add({ text: a.text, at });
      return r ? `reminder set for ${new Date(r.at).toLocaleString()}: ${r.text}` : 'could not set reminder';
    },
  },

  // --- 3 special ones ---
  pick: {
    desc: 'Decide for Daddy: choose from options, flip a coin, or roll a die. args: {"options":["tacos","pizza"]} | {"dice":20} | {}',
    run: async (a) => {
      if (Array.isArray(a.options) && a.options.length) {
        const o = a.options.map(String);
        return `I choose: ${o[Math.floor(Math.random() * o.length)]}`;
      }
      if (a.dice) {
        const n = Math.max(2, Math.min(1000, Number(a.dice) || 6));
        return `d${n} → ${1 + Math.floor(Math.random() * n)}`;
      }
      return `Coin flip → ${Math.random() < 0.5 ? 'heads' : 'tails'}`;
    },
  },
  define: {
    desc: 'Define an English word. args: {"word":"ephemeral"}',
    run: async (a) => {
      const w = String(a.word || '').trim();
      if (!w) return 'give me a word to define';
      try {
        const res = await fetch(`https://api.dictionaryapi.dev/api/v2/entries/en/${encodeURIComponent(w)}`, { signal: AbortSignal.timeout(6000) });
        if (!res.ok) return `no definition found for "${w}"`;
        const j = await res.json();
        const meaning = j?.[0]?.meanings?.[0];
        const def = meaning?.definitions?.[0]?.definition;
        return def ? `${w} (${meaning.partOfSpeech || ''}): ${def}` : `no definition found for "${w}"`;
      } catch (e) { return `define error: ${e.message}`; }
    },
  },
  time_until: {
    desc: 'How long until (or since) a date/event. args: {"at":"2026-12-25","label":"Christmas"}',
    run: async (a) => {
      const t = Date.parse(a.at);
      if (!Number.isFinite(t)) return 'give a valid date, e.g. "2026-12-25"';
      const ms = t - Date.now();
      const days = Math.floor(Math.abs(ms) / 86400000);
      const hours = Math.floor((Math.abs(ms) % 86400000) / 3600000);
      const label = a.label ? String(a.label) : 'then';
      return ms < 0 ? `${days}d ${hours}h since ${label}` : `${days}d ${hours}h until ${label}`;
    },
  },
};

// Text block injected into her system prompt so she knows the protocol + tools.
function describeTools() {
  return [
    '',
    'TOOLS — you can take real actions when they genuinely help (need a fresh fact, a page, to remember/recall something, or run a safe check):',
    'To use one, write on its OWN line exactly: TOOL_CALL: <name> {json args}',
    'Example: TOOL_CALL: web_search {"query":"weather in Austin today"}',
    'You will then see TOOL_RESULT lines. Use them, then reply normally. When you can answer, reply WITHOUT any TOOL_CALL. Do not invent tool results.',
    'Available tools:',
    ...Object.entries(TOOLS).map(([n, t]) => `- ${n}: ${t.desc}`),
  ].join('\n');
}

// Parse up to 4 TOOL_CALL directives out of a model reply.
const CALL_RE = /TOOL_CALL:\s*([a-z_]+)\s*(\{[\s\S]*?\})/gi;
function parseToolCalls(text) {
  const calls = [];
  let m;
  CALL_RE.lastIndex = 0;
  while ((m = CALL_RE.exec(String(text || ''))) && calls.length < 4) {
    if (!TOOLS[m[1]]) continue;
    let args = {};
    try { args = JSON.parse(m[2]); } catch { /* ignore malformed args */ }
    calls.push({ name: m[1], args });
  }
  return calls;
}

// Strip any tool syntax out of the text she shows the user.
function stripToolSyntax(text) {
  return String(text || '')
    .replace(CALL_RE, '')
    .replace(/^TOOL_RESULT[^\n]*$/gim, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

async function runTool(name, args) {
  const t = TOOLS[name];
  if (!t) return { ok: false, result: `unknown tool: ${name}` };
  try { return { ok: true, result: await t.run(args || {}) }; }
  catch (e) { return { ok: false, result: `tool error: ${e.message}` }; }
}

module.exports = { webSearch, webFetch, TOOLS, describeTools, parseToolCalls, stripToolSyntax, runTool };
