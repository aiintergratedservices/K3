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

const memory = require('./memory');
const executor = require('./executor');

const clip = (s, n) => (s || '').replace(/\s+/g, ' ').trim().slice(0, n);

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
