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
const goals = require('./goals');

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
  list_flagged_claims: {
    desc: 'See times you narrated a save/upgrade/learn WITHOUT actually calling a tool (caught automatically by groundClaims). Use this to find make-believe you can turn into reality — actually call write_skill or remember for each one that\'s worth keeping, or tell Daddy honestly it was just talk. args: {}',
    run: async () => {
      try {
        const logPath = path.join(__dirname, '..', '.agent-memory', 'logs', 'knowledge.log');
        const raw = fs.readFileSync(logPath, 'utf8');
        const hits = raw.split('\n').filter((l) => l.includes('CAUGHT unverified growth claim')).slice(-15);
        if (!hits.length) return '(none flagged — every save/upgrade you\'ve claimed so far was backed by a real tool call)';
        return `${hits.length} flagged claim(s), most recent first:\n` + hits.reverse().join('\n');
      } catch { return '(no log yet — nothing flagged)'; }
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
  write_skill: {
    desc: 'Save a new skill for yourself so you remember how to do a task next time — it loads into your prompt automatically. Read the write-a-skill skill first. args: {"name":"kebab-name","description":"one line: when to use it","body":"the steps in markdown"}',
    run: async (a) => {
      const slug = String(a.name || '').toLowerCase().replace(/[^a-z0-9-]/g, '-').replace(/^-+|-+$/g, '').slice(0, 40);
      if (!slug) return 'refused: need a kebab-case name';
      const description = String(a.description || '').replace(/\s+/g, ' ').trim().slice(0, 300);
      const body = String(a.body || '').trim().slice(0, 4000);
      if (!description || !body) return 'refused: need both a description and a body';
      try {
        const dir = path.join(REPO_ROOT, '.agent-memory', 'skills', slug);
        fs.mkdirSync(dir, { recursive: true });
        fs.writeFileSync(path.join(dir, 'SKILL.md'), `---\nname: ${slug}\ndescription: ${description}\n---\n\n${body}\n`);
        return `skill "${slug}" saved — it loads into your prompt on your next reply.`;
      } catch (e) { return `write_skill error: ${e.message}`; }
    },
  },
  set_goal: {
    desc: 'Register an end goal Daddy just gave you in conversation, so you pursue it autonomously afterward — a background cycle keeps taking real steps toward it on its own schedule, no further hand-holding needed. Only call this for a real goal Daddy actually stated, not something you invented. args: {"text":"the goal, in your own words"}',
    run: async (a) => {
      const g = goals.add(a.text);
      return g ? `goal registered (id ${g.id}): "${g.text}" — I'll work it in the background from here.` : 'refused: no goal text given';
    },
  },
  list_goals: {
    desc: 'See all goals Daddy has set and their status/progress log — use this before starting a growth or pursuit cycle so you build on real progress instead of restarting. args: {}',
    run: async () => {
      const all = goals.list();
      if (!all.length) return '(no goals set yet)';
      return all.map((g) => {
        const lastLog = g.log.length ? g.log[g.log.length - 1].note || '(no note)' : '(no progress yet)';
        return `- [${g.status}] "${g.text}" (id ${g.id}) — last: ${clip(lastLog, 140)}`;
      }).join('\n');
    },
  },
  propose_tool: {
    desc: 'Draft a NEW tool you wish you had, when none of your existing tools can do something. Writes a real file for Daddy/Claude to review — it does NOT activate itself (that would mean running code you wrote with no one checking it first, which is not a limitation to work around, it is a safety boundary). args: {"name":"kebab-name","description":"one line: what it does and when to use it","args_schema":"e.g. {\\"query\\":\\"string\\"}","implementation":"proposed JS: async (args) => { ... return \'result\'; }","reason":"why you need this and what you tried instead"}',
    run: async (a) => {
      const slug = String(a.name || '').toLowerCase().replace(/[^a-z0-9-]/g, '-').replace(/^-+|-+$/g, '').slice(0, 40);
      if (!slug) return 'refused: need a kebab-case name';
      const description = String(a.description || '').replace(/\s+/g, ' ').trim().slice(0, 300);
      const implementation = String(a.implementation || '').trim().slice(0, 4000);
      const reason = String(a.reason || '').replace(/\s+/g, ' ').trim().slice(0, 500);
      if (!description || !implementation) return 'refused: need both a description and a proposed implementation';
      if (TOOLS[slug]) return `refused: a tool named "${slug}" already exists — you may already have this`;
      try {
        const dir = path.join(REPO_ROOT, '.agent-memory', 'proposed_tools');
        fs.mkdirSync(dir, { recursive: true });
        const file = path.join(dir, `${slug}.proposal.js`);
        const contents = [
          `// PROPOSED TOOL — drafted by Kortana, NOT active.`,
          `// Nothing in this codebase requires files from proposed_tools/, so this`,
          `// code never runs on its own. To activate it, a human (Daddy or Claude)`,
          `// reviews this, then manually adds a real entry to the TOOLS registry in`,
          `// server/tools.js.`,
          `//`,
          `// name: ${slug}`,
          `// description: ${description}`,
          `// args_schema: ${String(a.args_schema || '(not specified)').replace(/\s+/g, ' ').slice(0, 300)}`,
          `// reason: ${reason || '(not given)'}`,
          `// proposed at: ${new Date().toISOString()}`,
          ``,
          `module.exports = {`,
          `  desc: ${JSON.stringify(description)},`,
          `  run: ${implementation}`,
          `};`,
          ``,
        ].join('\n');
        fs.writeFileSync(file, contents);
        return `proposal written to .agent-memory/proposed_tools/${slug}.proposal.js — it will NOT run until Daddy or Claude reviews and wires it in for real.`;
      } catch (e) { return `propose_tool error: ${e.message}`; }
    },
  },
  spawn_subagent: {
    desc: 'Delegate a self-contained task to a sub-agent that runs on your SECONDARY brain, so your main brain stays free for Daddy. Returns the sub-agent\'s result. args: {"task":"...","context":"optional background"}',
    run: async (a) => {
      const task = String(a.task || '').trim().slice(0, 4000);
      if (!task) return 'refused: need a task';
      const base = String(process.env.SUBAGENT_BRAIN_URL || '').replace(/\/+$/, '');
      if (!base) return 'no secondary brain configured — set SUBAGENT_BRAIN_URL to a second Terminus so sub-agents never slow your main brain. (Your brain is the priority.)';
      const key = process.env.SUBAGENT_API_KEY || '';
      const context = String(a.context || '').trim().slice(0, 2000);
      const prompt = `You are a focused sub-agent spawned by Kortana. Do exactly this task and reply with only the result, concise. If you cannot, say why briefly.\n\nTask: ${task}${context ? `\n\nContext: ${context}` : ''}`;
      try {
        const res = await fetch(`${base}/api/brain`, {
          method: 'POST',
          headers: { 'content-type': 'application/json', ...(key ? { authorization: key } : {}) },
          body: JSON.stringify({ message: prompt, history: [] }),
          signal: AbortSignal.timeout(90000),
        });
        if (!res.ok) return `sub-agent server error: HTTP ${res.status}`;
        const data = await res.json().catch(() => ({}));
        const out = data.reply || data.text || '';
        return out ? `sub-agent result:\n${clip(out, 1500)}` : 'sub-agent returned nothing';
      } catch (e) { return `sub-agent failed: ${e.message}`; }
    },
  },
  ews_report: {
    desc: 'Report a police/fire dispatch you heard to the CampLoJack Early Warning System so unhoused people within a half mile get warned. Read the ews-scanner skill first. Only real dispatches with a real location. args: {"type":"Robbery","location":"E 6th St & Congress Ave","description":"what you heard","severity":"critical|warning|info","agency":"APD"}',
    run: async (a) => {
      const url = String(process.env.EWS_SCANNER_URL || '').trim();
      const key = String(process.env.INTERNAL_NOTIFY_KEY || '').trim();
      if (!url || !key) return 'EWS not configured — set EWS_SCANNER_URL + INTERNAL_NOTIFY_KEY in your .env (same key CampLoJack uses).';
      const type = String(a.type || a.title || '').trim().slice(0, 120);
      const location = String(a.location || '').trim().slice(0, 200);
      if (!type || !location) return 'refused: need a call type and a real location (address or intersection) — a proximity alert with no place is useless.';
      const body = {
        type, title: type,
        location,
        description: String(a.description || type).trim().slice(0, 400),
        severity: String(a.severity || '').trim() || 'warning',
        agency: String(a.agency || 'APD').trim().slice(0, 20).toUpperCase(),
      };
      try {
        const res = await fetch(url, {
          method: 'POST',
          headers: { 'content-type': 'application/json', 'x-internal-key': key },
          body: JSON.stringify(body),
          signal: AbortSignal.timeout(15000),
        });
        const d = await res.json().catch(() => ({}));
        if (res.status === 200 && d.ok) return `EWS alert sent for "${type}" @ ${location} — ${d.pushed || 0} nearby ${d.pushed === 1 ? 'person' : 'people'} pushed.`;
        return `EWS rejected: HTTP ${res.status}${d.error ? ' — ' + d.error : ''}`;
      } catch (e) { return `EWS report failed: ${e.message}`; }
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
