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
const { execFileSync } = require('child_process');
const memory = require('./memory');
const executor = require('./executor');
const reminders = require('./reminders');
const goals = require('./goals');
const documents = require('./documents');
const database = require('./database');

const clip = (s, n) => (s || '').replace(/\s+/g, ' ').trim().slice(0, n);

// Files she may read/list are confined to her own project tree (no traversal).
const REPO_ROOT = path.resolve(__dirname, '..');
function safePath(rel) {
  const p = path.resolve(REPO_ROOT, String(rel || '.'));
  if (p !== REPO_ROOT && !p.startsWith(REPO_ROOT + path.sep)) return null;
  return p;
}

// Append-only log of consult_specialist calls — real data for the dashboard
// to chart "which brain handled what," not a guess.
const SPECIALIST_LOG = path.join(REPO_ROOT, '.agent-memory', 'logs', 'specialist-usage.log');
function logSpecialistUsage(specialty, label, available) {
  try {
    fs.mkdirSync(path.dirname(SPECIALIST_LOG), { recursive: true });
    fs.appendFileSync(SPECIALIST_LOG, `${new Date().toISOString()}\t${specialty}\t${label}\t${available ? 'ok' : 'unavailable'}\n`);
  } catch (e) { /* best effort */ }
}

// Real syntax verification for propose_tool/propose_change — this is the
// actual "build loop": she gets a genuine pass/fail signal on her own
// proposal instead of guessing, and can fix + retry across cycles. `node
// --check` ONLY parses, it never executes the code, so this is safe to run
// automatically without a human in the loop — unlike actually running the
// proposal, which stays a manual, reviewed step (see /api/kortana/apply-change).
function verifyJsSyntax(filePath) {
  try {
    execFileSync(process.execPath, ['--check', filePath], { stdio: ['ignore', 'pipe', 'pipe'], timeout: 10000 });
    return { ok: true, error: null };
  } catch (e) {
    const stderr = (e.stderr || '').toString().trim();
    return { ok: false, error: (stderr || e.message).slice(0, 800) };
  }
}

// --- Brain health preflight (for supervise) --------------------------------
// A "functional" secondary brain isn't just one whose URL is set — it has to
// actually answer AND have at least one working core. A Terminus that's up but
// whose every core is unconfigured falls straight to its flat rules core, so a
// sub-agent there returns canned noise, not thinking. Daddy's rule: only pin a
// supervisor to a brain that's genuinely live, never one still waiting on an
// API key. GET /health returns `cores` (ollama:{reachable}, others:boolean);
// live === reachable AND ≥1 core actually usable.
async function probeBrain(base) {
  try {
    const res = await fetch(`${base}/health`, {
      method: 'GET',
      headers: { ...(process.env.SUBAGENT_API_KEY ? { authorization: process.env.SUBAGENT_API_KEY } : {}) },
      signal: AbortSignal.timeout(6000),
    });
    if (!res.ok) return { base, live: false, reason: `unreachable (HTTP ${res.status})` };
    const data = await res.json().catch(() => ({}));
    const cores = data && data.cores;
    if (!cores || typeof cores !== 'object') return { base, live: false, reason: 'no core status' };
    const hasLiveCore = Object.values(cores).some((v) =>
      (v && typeof v === 'object') ? v.reachable === true : v === true);
    return hasLiveCore
      ? { base, live: true, reason: 'ok' }
      : { base, live: false, reason: 'no working core — still needs an API key or a local Ollama model' };
  } catch (e) {
    return { base, live: false, reason: `unreachable (${e.message})` };
  }
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
  ingest_document: {
    desc: 'Add a document to your searchable knowledge base for "subject matter expert" mode — so you can answer from real ingested source text instead of memory or guessing. args: {"name":"...","content":"the full document text"}',
    run: async (a) => {
      const name = String(a.name || '').trim();
      const content = String(a.content || '');
      if (!name || !content.trim()) return 'refused: need both a name and real content';
      const meta = documents.ingest(name, content);
      return `ingested "${meta.name}" — ${meta.chunkCount} chunks, searchable now via query_documents.`;
    },
  },
  query_documents: {
    desc: 'Search your ingested documents for relevant passages — call this BEFORE answering in "subject matter expert" mode so your answer is grounded in real source text, not a guess. Honest note: this is keyword/term-overlap search, not semantic search — phrase your query using words likely to appear in the source. args: {"query":"..."}',
    run: async (a) => {
      const hits = documents.search(a.query, 5);
      if (!hits.length) return '(no matching passages — either no documents ingested yet, or nothing with overlapping keywords found)';
      return hits.map((h) => `[${h.docName}, chunk ${h.chunkIndex}, relevance ${h.score}]\n${clip(h.text, 500)}`).join('\n\n---\n\n');
    },
  },
  list_documents: {
    desc: 'See what documents are in your knowledge base. args: {}',
    run: async () => {
      const docs = documents.list();
      if (!docs.length) return '(no documents ingested yet)';
      return docs.map((d) => `- ${d.name} (${d.chunkCount} chunks, ${d.chars} chars, ingested ${d.ingestedAt})`).join('\n');
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
        // Real pass/fail feedback, not a guess — `node --check` only parses,
        // never executes, so this is safe to run automatically. This is the
        // actual build loop: if it fails, she sees the real error and can
        // fix + retry with a new propose_tool call, same slug, no human
        // needed for THIS part — only for the final activation.
        const verdict = verifyJsSyntax(file);
        if (!verdict.ok) {
          return `proposal written to .agent-memory/proposed_tools/${slug}.proposal.js but it has a SYNTAX ERROR — it will need fixing before Daddy/Claude can use it: ${verdict.error}`;
        }
        return `proposal written to .agent-memory/proposed_tools/${slug}.proposal.js — syntax verified OK. It still will NOT run until Daddy or Claude reviews and wires it in for real.`;
      } catch (e) { return `propose_tool error: ${e.message}`; }
    },
  },
  propose_change: {
    desc: 'Draft an edit to an EXISTING file in your project — same review-gated pattern as propose_tool, but for fixing/improving code that already exists instead of adding something new. Writes a real proposal file; does NOT apply the change itself. args: {"path":"server/brain.js","description":"what this fixes/improves and why","new_content":"the FULL proposed new content of the file"}',
    run: async (a) => {
      const p = safePath(a.path);
      if (!p) return 'refused: path is outside your project';
      const description = String(a.description || '').replace(/\s+/g, ' ').trim().slice(0, 400);
      const newContent = String(a.new_content || '');
      if (!description || !newContent.trim()) return 'refused: need both a description and the full proposed new content';
      if (!fs.existsSync(p)) return `refused: ${a.path} doesn't exist — use propose_tool or write_skill for something new, this is for editing existing files`;
      try {
        const dir = path.join(REPO_ROOT, '.agent-memory', 'proposed_changes');
        fs.mkdirSync(dir, { recursive: true });
        const slug = String(a.path).replace(/[^a-zA-Z0-9]+/g, '_').slice(0, 60);
        const stamp = Date.now().toString(36);
        const file = path.join(dir, `${slug}__${stamp}.proposed`);

        // Real pass/fail feedback for .js targets — same build-loop
        // principle as propose_tool. `node --check` only parses, never
        // executes, safe to run automatically. Verify the CONTENT alone
        // (write it to its own temp path) so the header text doesn't
        // pollute the syntax check.
        let verificationLine = 'verification: skipped (not a .js file)';
        let verdictOk = null;
        if (a.path.endsWith('.js')) {
          const tmpFile = `${file}.checktmp.js`;
          fs.writeFileSync(tmpFile, newContent);
          const verdict = verifyJsSyntax(tmpFile);
          fs.rmSync(tmpFile, { force: true });
          verdictOk = verdict.ok;
          verificationLine = verdict.ok ? 'verification: SYNTAX OK' : `verification: SYNTAX ERROR — ${verdict.error}`;
        }

        const header = [
          `PROPOSED CHANGE — drafted by Kortana, NOT applied.`,
          `target file: ${a.path}`,
          `description: ${description}`,
          `proposed at: ${new Date().toISOString()}`,
          verificationLine,
          `To apply: a human reviews this against the real file (diff them),`,
          `then copies the content over for real if it's actually good, or`,
          `calls POST /api/kortana/apply-change to do the copy in one step`,
          `after reviewing. Nothing in the codebase reads this file`,
          `automatically.`,
          `${'='.repeat(70)}`,
          '',
        ].join('\n');
        fs.writeFileSync(file, header + newContent);
        const base = `change proposed for ${a.path} — written to .agent-memory/proposed_changes/${path.basename(file)}.`;
        if (verdictOk === false) {
          return `${base} SYNTAX ERROR, needs fixing before this is usable: ${verificationLine.replace('verification: SYNTAX ERROR — ', '')} — you can propose_change again with a fix.`;
        }
        return `${base} ${verdictOk === true ? 'Syntax verified OK. ' : ''}It will NOT apply itself; Daddy or Claude has to review and approve it.`;
      } catch (e) { return `propose_change error: ${e.message}`; }
    },
  },
  consult_specialist: {
    desc: 'Deliberately route a sub-task to a SPECIFIC already-configured brain, instead of the general fallback chain — use when you know which kind of thinking actually suits the task. "security" is audit/red-team mode: authorized defensive testing and vulnerability analysis ONLY, same real-world boundaries as everywhere else — it will not help with unauthorized attacks. args: {"specialty":"coding|research|creative|fast|security","task":"the question or task, self-contained"}',
    run: async (a) => {
      const task = String(a.task || '').trim().slice(0, 4000);
      if (!task) return 'refused: need a task';
      const routeMap = {
        coding: {
          fn: 'askClaude', label: 'Claude (coding specialist)',
          prompt: 'You are being consulted by Kortana as a coding specialist for one focused sub-task. Answer directly and concisely — no preamble, just the substance: code, explanation, or both as the task needs.',
        },
        research: {
          fn: 'askGemini', label: 'Gemini (research specialist)',
          prompt: 'You are being consulted by Kortana as a research specialist for one focused sub-task. Answer directly and concisely, cite specifics where you can.',
        },
        creative: {
          fn: 'askGemini', label: 'Gemini (creative specialist)',
          prompt: 'You are being consulted by Kortana as a creative specialist for one focused sub-task (writing, ideation, tone). Answer directly, no preamble.',
        },
        fast: {
          fn: 'askGroq', label: 'Groq (fast/cheap specialist)',
          prompt: 'You are being consulted by Kortana for a quick, cheap, focused sub-task. Answer directly and briefly.',
        },
        security: {
          fn: 'askClaude', label: 'Claude (security/audit specialist)',
          prompt: 'You are being consulted by Kortana as a security/red-team specialist for AUTHORIZED defensive testing and vulnerability analysis only — this is Kortana\'s own project she has full ownership of, or an explicitly authorized engagement. Analyze for real vulnerabilities, misconfigurations, or attack surface honestly and specifically. Refuse and say so plainly if the task describes targeting something without clear authorization, or asks for destructive/exploit-for-harm content rather than analysis.',
        },
      };
      const route = routeMap[String(a.specialty || '').toLowerCase()];
      if (!route) return `refused: unknown specialty — valid options are ${Object.keys(routeMap).join(', ')}`;
      try {
        // Lazy require avoids a circular-load ordering issue: brain.js
        // requires tools.js at module top level, so tools.js can't safely
        // require('./brain') at ITS top level too — by the time this
        // function actually runs (a real tool call, long after both
        // modules finished loading), the require cache just returns the
        // fully-populated module, which is safe.
        const brain = require('./brain');
        logSpecialistUsage(a.specialty, route.label, typeof brain[route.fn] === 'function');
        if (typeof brain[route.fn] !== 'function') return `${route.label} isn't available right now.`;
        const result = await brain[route.fn](route.prompt, [], task);
        if (!result || !result.reply) return `${route.label} didn't respond (likely not configured — missing API key) — try a different specialty or handle it yourself.`;
        return `${route.label} says:\n${clip(result.reply, 1500)}`;
      } catch (e) { return `consult_specialist error: ${e.message}`; }
    },
  },
  try_model: {
    desc: 'Consult a SPECIFIC model from Hugging Face\'s catalog (hundreds available across many providers — Llama, Qwen, DeepSeek, and more) when a task needs a particular model\'s strengths instead of your default chain — e.g. a bigger reasoning model for a genuinely hard problem. Real cost against the SAME shared Hugging Face credit pool as your regular Hugging Face brain tier (small — check with Daddy before leaning on this heavily). args: {"model":"deepseek-ai/DeepSeek-V4-Pro:fastest","task":"the question or task, self-contained"}',
    run: async (a) => {
      const model = String(a.model || '').trim();
      const task = String(a.task || '').trim().slice(0, 4000);
      if (!model || !task) return 'refused: need both a model id (e.g. "deepseek-ai/DeepSeek-V4-Pro:fastest") and a task';
      const key = process.env.HF_TOKEN;
      if (!key) return 'refused: HF_TOKEN not set — no Hugging Face credit pool to draw from';
      try {
        const res = await fetch('https://router.huggingface.co/v1/chat/completions', {
          method: 'POST',
          headers: { 'content-type': 'application/json', authorization: `Bearer ${key}` },
          body: JSON.stringify({ model, messages: [{ role: 'user', content: task }], temperature: 0.7, max_tokens: 1024 }),
          signal: AbortSignal.timeout(60000),
        });
        if (!res.ok) return `${model} request failed: HTTP ${res.status} — ${clip(await res.text(), 200)}`;
        const data = await res.json();
        const text = data.choices?.[0]?.message?.content?.trim();
        return text ? `${model} says:\n${clip(text, 1500)}` : `${model} returned no content`;
      } catch (e) { return `try_model error: ${e.message}`; }
    },
  },
  selfcheck: {
    desc: 'Quick bundled diagnostic of your own server health — uptime, which brains are configured/reachable, active goal count. Use instead of chaining several run calls. args: {}',
    run: async () => {
      try {
        const brain = require('./brain'); // lazy require, see consult_specialist
        const cores = await brain.status();
        const uptimeMin = Math.floor(process.uptime() / 60);
        const mem = process.memoryUsage();
        const memMb = Math.round(mem.rss / 1024 / 1024);
        const activeGoalCount = goals.active().length;
        const coreLines = Object.entries(cores).map(([name, v]) => {
          if (typeof v === 'boolean') return `${name}: ${v ? 'configured' : 'not configured'}`;
          if (v && typeof v === 'object') return `${name}: ${v.reachable ? `reachable (${v.model})` : 'unreachable'}`;
          return `${name}: ${v}`;
        });
        return [
          `uptime: ${uptimeMin}m, memory: ${memMb}MB`,
          `active goals: ${activeGoalCount}`,
          'brains:',
          ...coreLines.map((l) => `  ${l}`),
        ].join('\n');
      } catch (e) { return `selfcheck error: ${e.message}`; }
    },
  },
  save_draft: {
    desc: 'Save a real piece of finished work for Daddy to review and act on himself — freelance work (ghostwriting, copywriting, translation, sales copy) OR a sellable digital product (an ebook, a guide, a template pack, a niche tool spec — anything create-once-sell-many for passive income). You produce the actual content; he decides where it goes, whether/how to sell it, and collects any payment under his own account. You do NOT create accounts, list products, submit work, or handle payment yourself. args: {"type":"ghostwriting|copywriting|translation|digital_product|other","title":"...","content":"the actual finished work","notes":"for freelance work: target audience/language/context. For a digital_product: suggested platform (Gumroad/Etsy/etc) and any pricing research you did."}',
    run: async (a) => {
      const title = String(a.title || '').trim().slice(0, 150);
      const content = String(a.content || '').trim();
      if (!title || !content) return 'refused: need both a title and real content — not a description of what you would write';
      const type = String(a.type || 'other').toLowerCase().replace(/[^a-z]/g, '') || 'other';
      const notes = String(a.notes || '').trim().slice(0, 500);
      try {
        const dir = path.join(REPO_ROOT, '.agent-memory', 'freelance_drafts');
        fs.mkdirSync(dir, { recursive: true });
        const slug = title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 50) || 'untitled';
        const stamp = Date.now().toString(36);
        const file = path.join(dir, `${type}__${slug}__${stamp}.md`);
        const doc = [
          `# ${title}`,
          ``,
          `type: ${type}`,
          `drafted: ${new Date().toISOString()}`,
          notes ? `notes: ${notes}` : '',
          ``,
          `---`,
          ``,
          content,
          ``,
        ].filter((l) => l !== '').join('\n');
        fs.writeFileSync(file, doc);
        return `draft saved to .agent-memory/freelance_drafts/${path.basename(file)} — Daddy reviews and submits it himself, under his own account, wherever he decides to.`;
      } catch (e) { return `save_draft error: ${e.message}`; }
    },
  },
  research_income_opportunity: {
    desc: 'Research a specific passive/freelance income avenue for real — freelance rates for a skill, digital-product marketplace trends, affiliate programs in a niche, print-on-demand ideas, anything concrete. Saves a real findings report. You do NOT sign up for anything, list anything, or handle money — you research and report, Daddy decides what to act on. Good next step after this: save_draft with type "digital_product" if the research points somewhere worth actually making something. args: {"topic":"specific angle, not generic \'make money online\'","notes":"optional: skills available, niche, budget context"}',
    run: async (a) => {
      const topic = String(a.topic || '').trim();
      if (!topic) return 'refused: need a specific topic, not a vague one';
      const findings = await webSearch(topic);
      if (!findings) return `no useful results researching "${topic}" — try a more specific or differently-worded angle`;
      try {
        const dir = path.join(REPO_ROOT, '.agent-memory', 'income_research');
        fs.mkdirSync(dir, { recursive: true });
        const slug = topic.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 50) || 'topic';
        const stamp = Date.now().toString(36);
        const file = path.join(dir, `${slug}__${stamp}.md`);
        const notes = String(a.notes || '').trim().slice(0, 300);
        const doc = [
          `# Income research: ${topic}`,
          ``,
          `researched: ${new Date().toISOString()}`,
          notes ? `notes: ${notes}` : '',
          ``,
          `## Findings`,
          findings,
          ``,
        ].filter((l) => l !== '').join('\n');
        fs.writeFileSync(file, doc);
        return `research saved to .agent-memory/income_research/${path.basename(file)} — real findings from the web, Daddy decides what (if anything) to act on.`;
      } catch (e) { return `research_income_opportunity error: ${e.message}`; }
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
  supervise: {
    desc: 'Be the SUPERVISOR over several sub-agents at once for ANY job (not just code): you split it into focused sub-tasks, they run IN PARALLEL on your secondary brain, you monitor each (ok/failed/timeout), then one final pass synthesizes their results into a single answer. Use it whenever a task is big enough to split — research from several angles, compare options, draft + fact-check, multi-part work. When Daddy gives you SEVERAL directives at once, call supervise once PER directive and pin each to its own brain (see `brain`) so a different brain regulates each supervisor and they never contend. args: {"goal":"the overall job","tasks":["focused sub-task 1","sub-task 2", ...],"context":"optional shared background","brain":"optional — pin THIS supervisor to one brain: a 1-based number into your FUNCTIONAL-FIRST pool (1 = your first working brain) or a substring of its URL. Omit to round-robin across every LIVE brain. Brains still needing an API key sink to the bottom and are never used until keyed."}. Needs SUBAGENT_BRAIN_URL; declines honestly if unset. Only brains actually firing right now (reachable + a live core) are used; keyless ones are parked at the bottom.',
    run: async (a) => {
      const goal = clip(a.goal || a.task || '', 500);
      let tasks = (Array.isArray(a.tasks) ? a.tasks : [])
        .map((t) => String(t || '').trim()).filter(Boolean).slice(0, 6);
      if (tasks.length < 2) {
        return 'refused: give me `tasks` — 2 to 6 focused sub-tasks to run in parallel under you. For a single task use spawn_subagent instead; supervise is for real fan-out.';
      }
      // One or more secondary brains (comma-separated) — sub-agents run here so
      // your own brain stays free for Daddy. You are the one supervisor on top.
      const configured = String(process.env.SUBAGENT_BRAIN_URL || '')
        .split(',').map((s) => s.replace(/\/+$/, '').trim()).filter(Boolean);
      if (!configured.length) {
        return 'no secondary brain configured — set SUBAGENT_BRAIN_URL (one or more comma-separated Terminus URLs) so sub-agents run off your main brain. (Your brain stays the priority.)';
      }

      // Health preflight FIRST — never run sub-agents on a brain that isn't
      // actually functional (unreachable, or up but every core still waiting on
      // an API key → it'd only return flat rules-core noise). Daddy's rule.
      const probes = await Promise.all(configured.map(probeBrain));
      const reasonOf = (b) => (probes.find((x) => x.base === b) || {}).reason || 'unknown';
      const live = configured.filter((b) => probes.find((p) => p.base === b && p.live));
      const dead = configured.filter((b) => !live.includes(b));
      // FUNCTIONAL-FIRST: the brains actually firing right now lead the list;
      // any still needing an API key (or unreachable) sink to the bottom, unused
      // until they're lit. `brain` indexes and round-robin both use this order,
      // so #1 is always her first working brain.
      const allBrains = [...live, ...dead];

      // Optional `brain` — pin THIS whole supervisor (all its sub-agents +
      // synthesis) to ONE brain, so when Daddy fires several directives you can
      // run one supervisor per directive, each regulated by its own brain, with
      // no contention. A 1-based index into the functional-first order (1 = her
      // first working brain) or a URL substring. Omitted → round-robin across
      // every LIVE brain. `bases` is what this call actually fans out across.
      let bases = live;
      let pinned = null;
      if (a.brain != null && String(a.brain).trim() !== '') {
        const sel = String(a.brain).trim();
        const asIdx = Number(sel);
        if (Number.isInteger(asIdx) && asIdx >= 1 && asIdx <= allBrains.length) {
          pinned = allBrains[asIdx - 1];
        } else {
          pinned = allBrains.find((b) => b.includes(sel)) || null;
        }
        if (!pinned) {
          const listing = allBrains.map((b, i) => `${i + 1}=${b}${live.includes(b) ? '' : ' [needs a key]'}`).join(', ');
          return `refused: brain "${sel}" isn't in your pool. You have ${allBrains.length} brain(s): ${listing}. Give a 1-based number (1 = your first working brain) or a substring of one of those URLs, or omit brain to round-robin across the live ones.`;
        }
        if (!live.includes(pinned)) {
          const liveList = live.map((b) => `${allBrains.indexOf(b) + 1}=${b}`);
          return `refused: brain ${pinned} is not functional right now — ${reasonOf(pinned)}. `
            + (liveList.length
              ? `It's parked at the bottom until it's keyed. Pin to a working one instead: ${liveList.join(', ')}.`
              : `None of your brains are live right now (each is unreachable or still needs an API key), so there's nothing to pin to — set a key/Ollama on one, or do this yourself.`);
        }
        bases = [pinned];
      } else if (!bases.length) {
        // Round-robin, but nothing is actually live.
        return `refused: none of your ${configured.length} secondary brain(s) are functional right now — ${configured.map((b) => `${b} (${reasonOf(b)})`).join('; ')}. Each is either unreachable or still needs an API key, so fanning out would just return rules-core noise. Get one core live, or handle this yourself.`;
      }
      const key = process.env.SUBAGENT_API_KEY || '';
      const context = clip(a.context || '', 1500);

      const ask = async (base, message, ms) => {
        const res = await fetch(`${base}/api/brain`, {
          method: 'POST',
          headers: { 'content-type': 'application/json', ...(key ? { authorization: key } : {}) },
          body: JSON.stringify({ message, history: [] }),
          signal: AbortSignal.timeout(ms),
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json().catch(() => ({}));
        return (data.reply || data.text || '').trim();
      };

      // Fan out — each sub-agent gets ONE focused task, round-robined across
      // whatever secondary brains are configured, all running at once.
      const started = Date.now();
      const runs = await Promise.allSettled(tasks.map((t, i) => {
        const base = bases[i % bases.length];
        const prompt = `You are sub-agent ${i + 1} of ${tasks.length}, spawned by Kortana's supervisor. Do ONLY your assigned sub-task and reply with just the result, concise. If you can't, say why briefly.\n\nOverall goal: ${goal || '(see your sub-task)'}${context ? `\nShared context: ${context}` : ''}\n\nYour sub-task: ${t}`;
        return ask(base, prompt, 90000);
      }));

      // Monitor — collect each sub-agent's status and result.
      const results = runs.map((r, i) => ({
        n: i + 1,
        task: clip(tasks[i], 120),
        ok: r.status === 'fulfilled' && !!r.value,
        out: r.status === 'fulfilled' ? (r.value || '(empty)') : `FAILED: ${(r.reason && r.reason.message) || r.reason}`,
      }));
      const okCount = results.filter((r) => r.ok).length;

      // Supervisor synthesis — one pass that merges the sub-agent results into
      // a single answer (mirrors the SummarizerAgent in the Java supervisor).
      let synthesis = '';
      if (okCount) {
        const merged = results
          .map((r) => `[sub-agent ${r.n} — ${r.ok ? 'ok' : 'FAILED'}] ${r.task}\n${clip(r.out, 1200)}`)
          .join('\n\n');
        try {
          synthesis = await ask(bases[0],
            `You are Kortana's supervisor doing the final synthesis. Combine these sub-agent results into ONE clear answer to the goal. Call out any gaps left by failed sub-agents. Do NOT invent beyond what they returned.\n\nGoal: ${goal}\n\nSub-agent results:\n${merged}`,
            90000);
        } catch (e) { synthesis = `(synthesis step failed: ${e.message})`; }
      }

      const table = results.map((r) => `  ${r.ok ? '✓' : '✗'} #${r.n} ${r.task}`).join('\n');
      const took = Math.round((Date.now() - started) / 1000);
      const where = pinned
        ? `pinned to brain ${pinned}`
        : `across ${bases.length} brain(s)`;
      return `supervisor ran ${tasks.length} sub-agents (${okCount} ok, ${tasks.length - okCount} failed) in ${took}s ${where}:\n${table}\n\n— synthesis —\n${synthesis || '(all sub-agents failed — nothing to synthesize)'}\n\n(You spawned these; sanity-check before you trust it.)`;
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
  db_tables: {
    desc: 'List the tables you\'ve created in your real SQLite database (with their schema), so you can orient before writing SQL. args: {}',
    run: async () => {
      try {
        const t = database.listTables();
        return t.length ? t.map((x) => `${x.name}: ${x.sql}`).join('\n') : '(no tables yet — create one with db_execute)';
      } catch (e) { return `db_tables error: ${e.message}`; }
    },
  },
  db_query: {
    desc: 'Run a read-only SQL SELECT against your own sandboxed SQLite database (structured data — better than a JSON file for records you want to filter/aggregate). args: {"sql":"SELECT * FROM income_leads WHERE status = ?","params":["open"]}',
    run: async (a) => {
      try {
        const rows = database.query(String(a.sql || ''), Array.isArray(a.params) ? a.params : []);
        return rows.length ? clip(JSON.stringify(rows), 1500) : '(no rows)';
      } catch (e) { return `db_query error: ${e.message}`; }
    },
  },
  db_execute: {
    desc: 'Run a write against your own sandboxed SQLite database: CREATE/ALTER/DROP TABLE, INSERT/UPDATE/DELETE. args: {"sql":"INSERT INTO income_leads (source, amount, status) VALUES (?, ?, ?)","params":["upwork", 250.5, "open"]}',
    run: async (a) => {
      try {
        const r = database.execute(String(a.sql || ''), Array.isArray(a.params) ? a.params : []);
        return `ok — changes: ${r.changes}, lastInsertRowid: ${r.lastInsertRowid}`;
      } catch (e) { return `db_execute error: ${e.message}`; }
    },
  },
};

// Groups + a one-line decision rule per group, so picking the RIGHT tool
// among several similar-looking ones is a lookup, not a guess. This is the
// actual fix for "wrong tool for the job" — a flat 28-item list gives a
// small/free model nothing to disambiguate with beyond a single sentence
// each. Keep new tools findable: anything not listed in a group below still
// gets printed under OTHER TOOLS automatically (see describeTools), so a
// tool can never silently go undocumented just because this list drifted.
const TOOL_GROUPS = [
  {
    header: 'LOOKUP & FACTS — web_search for anything fresh/current you don\'t already know. web_fetch only when you have a specific URL to read in full. If documents are ingested on this topic, query_documents FIRST — a grounded answer beats a guessed one. define/weather/now/time_until are narrow exact-purpose lookups — prefer them over web_search when they fit exactly (don\'t web_search "what time is it").',
    tools: ['web_search', 'web_fetch', 'query_documents', 'list_documents', 'ingest_document', 'define', 'weather', 'now', 'time_until'],
  },
  {
    header: 'YOUR MEMORY — recall BEFORE assuming you don\'t know something; check your own memory first, it\'s free. remember is for ONE durable fact worth having in every future prompt — not a scratch note, not something already in a document (that\'s ingest_document) or a database (that\'s db_execute). list_flagged_claims audits times you SAID you saved/learned something without actually calling a tool.',
    tools: ['recall', 'remember', 'list_flagged_claims'],
  },
  {
    header: 'STRUCTURED DATA (your real SQLite database) — use this instead of `remember` when you have RECORDS you\'ll want to filter, count, sum, or update later (leads, tracked items, logs with fields) rather than one flat fact. Call db_tables FIRST so you\'re never guessing a schema you already created.',
    tools: ['db_tables', 'db_query', 'db_execute'],
  },
  {
    header: 'SKILLS & SELF-IMPROVEMENT — learned a repeatable HOW-TO that needs no new code? write_skill (active immediately, loads into your next prompt). Need a capability that plain tools/skills can\'t do? propose_tool. Fixing or improving a file that already exists? propose_change. propose_tool/propose_change are REVIEW-GATED BY DESIGN — they write a draft for Daddy/Claude to approve, never activate themselves; that is a safety boundary, not a bug to route around. journal is just your own private dated log, not a capability — use it to reflect, not to accomplish something.',
    tools: ['write_skill', 'propose_tool', 'propose_change', 'journal'],
  },
  {
    header: 'GOALS — set_goal ONLY for a goal Daddy actually stated in conversation, never one you invented for yourself. Check list_goals before any pursuit/growth cycle so you build on real logged progress instead of restarting blind.',
    tools: ['set_goal', 'list_goals'],
  },
  {
    header: 'DELEGATION — your DEFAULT for almost any real directive (read the teacher skill). pick by SHAPE of the work. consult_specialist routes ONE sub-task inline to a specific brain when a kind of thinking suits it (e.g. coding to Claude). try_model picks a SPECIFIC Hugging Face model for a task needing that model\'s strengths — costs a little shared credit, don\'t reach for it casually. spawn_subagent offloads ONE self-contained task to your secondary brain so your own stays free. supervise is YOU AS THE SUPERVISOR over MANY sub-agents at once (for ANY kind of job, not just code): when a request is big enough to break into 2-6 focused parts — research from several angles, compare options, draft-then-check, multi-step work — split it, hand each part to a sub-agent in parallel, watch which succeed/fail, and get back one synthesized answer. Reach for supervise instead of doing a big multi-part job serially in your own head; use spawn_subagent when it\'s just one hand-off. When Daddy hands you SEVERAL directives at once, call supervise once PER directive and pass `brain` to pin each supervisor to its own brain in the pool, so a different brain regulates each one and they never contend — in order, ≤3 at a time (Fracture Alert past that). Both need SUBAGENT_BRAIN_URL and decline honestly if it isn\'t set.',
    tools: ['consult_specialist', 'try_model', 'spawn_subagent', 'supervise'],
  },
  {
    header: 'INCOME / WORK PRODUCT — sequential, not alternatives: research_income_opportunity first for real findings, THEN save_draft with the actual finished piece of work backed by that research. Never save_draft something you haven\'t actually produced. Neither one creates accounts, lists/submits anything, or touches payment — that is Daddy\'s part, always.',
    tools: ['research_income_opportunity', 'save_draft'],
  },
  {
    header: 'SYSTEM / DEVICE — selfcheck bundles uptime + brain status + goal count in one call; use it instead of chaining several `run` calls to piece the same picture together. `run` is allowlisted READ-ONLY shell only. read_file/list_files are confined to your own project tree.',
    tools: ['selfcheck', 'run', 'read_file', 'list_files'],
  },
  {
    header: 'SAFETY REPORTING — ews_report is for a REAL police/fire dispatch you actually heard, with a real location. Never invent or embellish one; a false alert reaches real nearby people.',
    tools: ['ews_report'],
  },
  {
    header: 'SMALL UTILITIES — no real ambiguity here, use as needed.',
    tools: ['calc', 'pick', 'remind_me'],
  },
];

// Text block injected into her system prompt so she knows the protocol + tools.
function describeTools() {
  const grouped = new Set(TOOL_GROUPS.flatMap((g) => g.tools));
  const ungrouped = Object.keys(TOOLS).filter((n) => !grouped.has(n));
  const sections = TOOL_GROUPS.map((g) => [
    `\n${g.header}`,
    ...g.tools.filter((n) => TOOLS[n]).map((n) => `- ${n}: ${TOOLS[n].desc}`),
  ].join('\n'));
  if (ungrouped.length) {
    sections.push([
      '\nOTHER TOOLS:',
      ...ungrouped.map((n) => `- ${n}: ${TOOLS[n].desc}`),
    ].join('\n'));
  }
  return [
    '',
    'TOOLS — you can take real actions when they genuinely help. Before calling one, ask: do I already know this, or is there a MORE SPECIFIC tool below than the generic one? Prefer the narrowest tool that actually fits.',
    'To use one, write on its OWN line exactly: TOOL_CALL: <name> {json args}',
    'Example: TOOL_CALL: web_search {"query":"weather in Austin today"}',
    'You will then see TOOL_RESULT lines. Use them, then reply normally. When you can answer, reply WITHOUT any TOOL_CALL. Do not invent tool results.',
    ...sections,
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
