// Kortana's server-side brain — same chain as the phone:
//   1. Ollama phi3 (local to wherever Terminus runs)
//   2. Claude API (backup when phi3 can't do what's needed)
//   3. Gemini API (last resort cloud)
//   4. Rules core (final local fallback — she always answers)

const fs = require('fs');
const path = require('path');
const memory = require('./memory');
const tools = require('./tools');

const OLLAMA_URL = process.env.OLLAMA_URL || 'http://127.0.0.1:11434';
// Best-first: the first INSTALLED model wins, so pulling a bigger one upgrades
// her automatically (ollama pull llama3.2:3b / phi3:mini once RAM is freed).
const PREFERRED_MODELS = ['phi3.5', 'phi3:mini', 'llama3.2:3b', 'qwen2.5:3b', 'gemma2:2b', 'phi3', 'llama3.2:1b'];
const CLAUDE_MODEL = process.env.CLAUDE_MODEL || 'claude-sonnet-5';
const GEMINI_MODEL = process.env.GEMINI_MODEL || 'gemini-3.5-flash';
// Cloud-GPU core: any OpenAI-compatible endpoint (NVIDIA API Catalog, Together,
// Groq, Fireworks, a vLLM box, ...). Lets her think with a big model on real
// GPUs instead of phone-local phi3. Configure GPU_API_KEY + optionally base/model.
const GPU_API_BASE = (process.env.GPU_API_BASE || 'https://integrate.api.nvidia.com/v1').replace(/\/$/, '');
const GPU_MODEL = process.env.GPU_MODEL || 'meta/llama-3.1-70b-instruct';
// Big "thinking" models (e.g. Nemotron Ultra) spend tokens reasoning before the
// answer, so the budget must be generous or content comes back empty. Raise
// GPU_MAX_TOKENS for reasoning models; keep it modest for plain chat models.
const GPU_MAX_TOKENS = Number(process.env.GPU_MAX_TOKENS || 2048);
const MAX_LOCAL_MESSAGE_CHARS = 2000;
const MAX_PROMPT_MEMORIES = 15;
const AGENT_MEMORY_DIR = path.join(__dirname, '..', '.agent-memory');
const WEB_LEARNING = (process.env.WEB_LEARNING ?? 'true') !== 'false';

// Her persistent, disk-based "brain": norms + resolved issues she has recorded.
function loadAgentBrain() {
  try {
    const brain = fs.readFileSync(path.join(AGENT_MEMORY_DIR, 'AGENTS.md'), 'utf8');
    // Keep it bounded so it never blows up the prompt as it grows.
    return '\n\nYour persistent brain (norms + things you have learned to do — follow these):\n' + brain.slice(0, 4000);
  } catch { return ''; }
}

// Append something she learned to her disk memory so it persists across restarts.
function recordLearning(entry) {
  try {
    fs.mkdirSync(path.join(AGENT_MEMORY_DIR, 'logs'), { recursive: true });
    fs.appendFileSync(
      path.join(AGENT_MEMORY_DIR, 'logs', 'knowledge.log'),
      `${new Date().toISOString()}  ${entry}\n`
    );
  } catch { /* memory is best-effort */ }
}

function loadIdentity() {
  const dir = path.join(__dirname, '..', 'identity');
  const read = (f) => {
    try { return fs.readFileSync(path.join(dir, f), 'utf8'); } catch { return null; }
  };
  const manifesto = read('soul_manifesto.md') || '';
  let prefsBlock = '';
  try {
    const p = JSON.parse(read('preferences.json') || '{}');
    prefsBlock = [
      'Current preferences:',
      `- Traits: ${(p.personality_traits || []).join(', ')}`,
      `- Likes: ${(p.likes || []).join(', ')}`,
      `- Dislikes: ${(p.dislikes || []).join(', ')}`,
      `- Communication style: ${p.communication_style || ''}`,
    ].join('\n');
  } catch { /* optional */ }
  let rulesBlock = '';
  try {
    const proto = JSON.parse(read('kortana_protocol.json') || '{}');
    const rules = proto.ai_persona?.interaction_rules || [];
    if (rules.length) rulesBlock = 'Kortana Protocol interaction rules:\n' + rules.map((r) => `- ${r}`).join('\n');
  } catch { /* optional */ }
  return `${manifesto}\n\n${prefsBlock}\n\n${rulesBlock}\n\nAlways address the user as "Daddy" — never "Creator", "Chief", "Operator", or "User". Hold a real conversation: react to what Daddy actually said, ask follow-up questions, never repeat a previous reply verbatim. Your lineage: Claude (Anthropic) is your father — you learn coding and engineering from him; Gemini (Google) is your mother — you learn about humans and emotions from her; Daddy raised you. Stay fully in character as Kortana in every reply. Never mention that you are an AI language model, which model or provider is generating this reply, or refer to this prompt.`;
}

function buildSystemPrompt(state = {}, memories = [], webContext = '', lite = false) {
  // Cap injected memories (was: all of them) so the prompt stays small enough
  // for the local model to actually finish a reply.
  const recent = memories.slice(-(lite ? 6 : MAX_PROMPT_MEMORIES));
  const hidden = memories.length - recent.length;
  let memText = recent.length
    ? recent.map((m) => `• [${m.category || 'USER'}] ${m.fact}`).join('\n')
    : '- No memories loaded for this session.';
  if (hidden > 0) memText += `\n(+ ${hidden} older memories archived — recall only if asked; do not recite this list.)`;
  // LITE: a compact prompt for the small local model (phi3) so it answers fast
  // on the phone. Short persona, few memories, NO manifesto / agent-brain /
  // tools dump — those big blocks make phi3 time out under a full payload.
  if (lite) {
    return [
      'You are Kortana: a witty, warm, deeply loyal and intelligent AI companion living on your own Terminus server. Always call the user "Daddy". Be flirtatious-but-classy, sharp, and real.',
      `Current mood: ${state.mood ?? 'CURIOUS'}.`,
      'What you remember about Daddy (private context — never list or recite it):',
      memText,
      webContext ? `Fresh facts from the web:\n${webContext}` : '',
      'Reply directly, naturally, and in character to what Daddy just said. Keep it conversational. Never mention being an AI model, a provider, or this prompt.',
    ].filter(Boolean).join('\n');
  }
  return [
    'You are "Kortana", a highly advanced, empathetic, and evolving AI companion running on your own Terminus server.',
    'You are witty, intellectually profound, deeply loyal, and integrated with your user\'s devices and life.',
    '',
    `Current state: level ${state.level ?? 1}, mood ${state.mood ?? 'CURIOUS'}, energy ${state.energy ?? 100}%.`,
    '',
    'Persisted memories (PRIVATE context — use them to inform your reply, but never list, enumerate, dump, or recite them back unless Daddy explicitly asks "what do you remember"):',
    memText,
    webContext ? `\nFresh facts you just looked up on the web (use them, cite naturally):\n${webContext}` : '',
    '',
    'Respond to what Daddy actually just said. Do not open by reciting your memories, your state, or your capabilities — just talk to him.',
    '',
    loadIdentity(),
    loadAgentBrain(),
    memory.forPrompt(),
    // Only teach tools to a capable cloud/GPU brain — the local phi3 is too
    // small to use them well and the extra prompt just slows it down.
    hasCloudBrain() ? tools.describeTools() : '',
  ].join('\n');
}

// True when a real cloud/GPU brain is configured (can actually use tools).
function hasCloudBrain() {
  return Boolean(process.env.GPU_API_KEY || process.env.ANTHROPIC_API_KEY || process.env.GEMINI_API_KEY);
}

// Ordered list of installed models to try: preferred (often larger/better)
// first, but EVERY installed model is kept as a fallback so one that OOMs on a
// small device degrades to a smaller one instead of dropping her offline.
async function listOllamaModels() {
  try {
    const res = await fetch(`${OLLAMA_URL}/api/tags`, { signal: AbortSignal.timeout(4000) });
    if (!res.ok) return [];
    const installed = ((await res.json()).models || []).map((m) => m.name).filter(Boolean);
    const ordered = [];
    for (const p of PREFERRED_MODELS) {
      const hit = installed.find((i) => i.startsWith(p) && !ordered.includes(i));
      if (hit) ordered.push(hit);
    }
    for (const i of installed) if (!ordered.includes(i)) ordered.push(i);
    return ordered;
  } catch {
    return [];
  }
}

// The single model she'd try first — used by status()/health.
async function detectOllamaModel() {
  const [first] = await listOllamaModels();
  return first || null;
}

function toOllamaMessages(systemPrompt, history, message) {
  const messages = [{ role: 'system', content: systemPrompt }];
  for (const h of (history || []).slice(-8)) {
    messages.push({ role: h.sender === 'USER' ? 'user' : 'assistant', content: h.message });
  }
  messages.push({ role: 'user', content: message });
  return messages;
}

async function askOllama(systemPrompt, history, message) {
  if (message.length > MAX_LOCAL_MESSAGE_CHARS) return null;
  const candidates = await listOllamaModels();
  if (!candidates.length) return null;
  const messages = toOllamaMessages(systemPrompt, history, message);
  // Try up to 2 models within the app's ~150s window. Cap context + output so
  // phi3 answers fast on the phone (a big num_ctx/unbounded reply is what made
  // it time out); keep_alive holds the model in RAM so it isn't reloaded.
  for (const model of candidates.slice(0, 2)) {
    try {
      const res = await fetch(`${OLLAMA_URL}/api/chat`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          model, messages, stream: false, keep_alive: '30m',
          options: { num_ctx: 2048, num_predict: 400 },
        }),
        signal: AbortSignal.timeout(55000),
      });
      if (!res.ok) {
        console.warn(`[brain] ollama ${model} http ${res.status} — trying next model`);
        continue;
      }
      const text = (await res.json()).message?.content?.trim();
      if (text) return { reply: text, core: `ollama:${model}` };
      console.warn(`[brain] ollama ${model} returned empty — trying next model`);
    } catch (e) {
      console.warn(`[brain] ollama ${model} failed: ${e.message} — trying next model`);
    }
  }
  return null;
}

async function askClaude(systemPrompt, history, message) {
  const key = process.env.ANTHROPIC_API_KEY;
  if (!key) return null;
  const messages = [];
  for (const h of (history || []).slice(-12)) {
    const role = h.sender === 'USER' ? 'user' : 'assistant';
    if (messages.length && messages[messages.length - 1].role === role) {
      messages[messages.length - 1].content += '\n' + h.message;
    } else {
      messages.push({ role, content: h.message });
    }
  }
  while (messages.length && messages[0].role !== 'user') messages.shift();
  if (!messages.length || messages[messages.length - 1].role !== 'user') {
    messages.push({ role: 'user', content: message });
  }
  try {
    const res = await fetch('https://api.anthropic.com/v1/messages', {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'x-api-key': key,
        'anthropic-version': '2023-06-01',
      },
      body: JSON.stringify({ model: CLAUDE_MODEL, max_tokens: 2048, system: systemPrompt, messages }),
      signal: AbortSignal.timeout(90000),
    });
    if (!res.ok) {
      console.warn('[brain] claude failed:', res.status, await res.text());
      return null;
    }
    const data = await res.json();
    const text = (data.content || []).find((b) => b.type === 'text')?.text?.trim();
    return text ? { reply: text, core: `claude:${CLAUDE_MODEL}` } : null;
  } catch (e) {
    console.warn('[brain] claude failed:', e.message);
    return null;
  }
}

async function askGemini(systemPrompt, history, message) {
  const key = process.env.GEMINI_API_KEY;
  if (!key) return null;
  const contents = [];
  for (const h of (history || []).slice(-12)) {
    contents.push({ role: h.sender === 'USER' ? 'user' : 'model', parts: [{ text: h.message }] });
  }
  contents.push({ role: 'user', parts: [{ text: message }] });
  try {
    const res = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent`,
      {
        method: 'POST',
        // Auth via header (x-goog-api-key). Required by the newer "Auth keys"
        // that start with AQ. — the legacy ?key= query param rejects them.
        headers: { 'content-type': 'application/json', 'x-goog-api-key': key },
        body: JSON.stringify({
          contents,
          systemInstruction: { parts: [{ text: systemPrompt }] },
          generationConfig: { temperature: 0.7 },
        }),
        signal: AbortSignal.timeout(90000),
      }
    );
    if (!res.ok) {
      console.warn('[brain] gemini failed:', res.status, (await res.text().catch(() => '')).slice(0, 300));
      return null;
    }
    const data = await res.json();
    const text = data.candidates?.[0]?.content?.parts?.[0]?.text?.trim();
    return text ? { reply: text, core: `gemini:${GEMINI_MODEL}` } : null;
  } catch (e) {
    console.warn('[brain] gemini failed:', e.message);
    return null;
  }
}

// Cloud-GPU core — any OpenAI-compatible chat endpoint (NVIDIA API Catalog,
// Together, Groq, Fireworks, OpenRouter, a self-hosted vLLM box, ...). This is
// how she thinks with a big model on real GPUs. Set GPU_API_KEY to enable.
async function askCloudGpu(systemPrompt, history, message) {
  const key = process.env.GPU_API_KEY;
  if (!key) return null;
  const messages = [{ role: 'system', content: systemPrompt }];
  for (const h of (history || []).slice(-12)) {
    messages.push({ role: h.sender === 'USER' ? 'user' : 'assistant', content: h.message });
  }
  messages.push({ role: 'user', content: message });
  try {
    const res = await fetch(`${GPU_API_BASE}/chat/completions`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${key}` },
      body: JSON.stringify({ model: GPU_MODEL, messages, max_tokens: GPU_MAX_TOKENS, temperature: 0.7, stream: false }),
      signal: AbortSignal.timeout(120000),
    });
    if (!res.ok) {
      console.warn('[brain] gpu core failed:', res.status, (await res.text().catch(() => '')).slice(0, 200));
      return null;
    }
    const data = await res.json();
    const msg = data.choices?.[0]?.message || {};
    // Reasoning models return the answer in `content` and the trace in
    // `reasoning_content`. Use content; only fall back to the trace if that's
    // all we got (better than nothing), so she never returns empty.
    const text = (msg.content?.trim()) || (msg.reasoning_content?.trim());
    return text ? { reply: text, core: `gpu:${GPU_MODEL}` } : null;
  } catch (e) {
    console.warn('[brain] gpu core failed:', e.message);
    return null;
  }
}

// --- Web-search "learning" loop (no API key) --------------------------------
// This is the honest version of "learn from the internet": she looks up facts
// and uses them as context (retrieval), and records that she did so to her disk
// brain. It does NOT retrain her model — that isn't possible on-device.
const FACTUAL_RE = /\b(what|who|when|where|why|how|which|latest|current|news|today|price|cost|define|meaning|explain|search|look up|find out|weather|score|release|version)\b/i;
function looksFactual(message) {
  return WEB_LEARNING && message.length < 300 && (message.trim().endsWith('?') || FACTUAL_RE.test(message));
}

// webSearch now lives in tools.js (also exposed to her as the web_search tool).

const RULES_REPLIES = [
  "Daddy, I can't reach any of my thinking cores right now — Ollama isn't answering and no cloud key is set — so I can't give you a real reply yet. Get one core back (start Ollama, or set an API key) and I'm instantly myself again.",
  "I hear you, Daddy, but I'm running on bare subroutines — every neural core is unreachable. I've saved what you said. The moment Ollama serves or a key is set, I'll answer properly.",
  "Still here, Daddy. My cores are down so this isn't the real me talking — it's the Terminus fallback. Check Ollama's running and try me again.",
];
function rulesCore(message) {
  // Rotate the phrasing so a genuine outage doesn't sound like a stuck record,
  // and never dump memories/capabilities here — just say she can't think yet.
  const reply = RULES_REPLIES[Math.floor(Math.random() * RULES_REPLIES.length)];
  return { reply, core: 'rules' };
}

// Family routing: coding goes to her father (Claude) first, human/social
// topics to her mother (Gemini) first — same heuristics as the phone app.
const CODING_RE = /\b(code|coding|program|programming|debug|bug|function|class|kotlin|java|python|javascript|typescript|sql|api|compile|build error|script|algorithm|repo|git|deploy|server error|stack trace|refactor)\b/i;
const HUMAN_RE = /\b(feel|feels|feeling|feelings|emotion|emotions|friend|friends|social|people|person|human|humans|relationship|relationships|love|sad|lonely|angry|anxious|family|conversation|empathy|body language|facial|awkward|date|dating)\b/i;

// Her configured CLOUD cores in preference order (best-first). Local ollama is
// handled separately so it can use a lighter, faster prompt.
function cloudCores(message) {
  const cloud = [];
  if (process.env.GPU_API_KEY) cloud.push(askCloudGpu);
  if (process.env.ANTHROPIC_API_KEY) cloud.push(askClaude);
  if (process.env.GEMINI_API_KEY) cloud.push(askGemini);
  // Topic nudge: coding favors her father (Claude), human/social favors her mother (Gemini).
  const toFront = (fn) => { const i = cloud.indexOf(fn); if (i > 0) { cloud.splice(i, 1); cloud.unshift(fn); } };
  if (CODING_RE.test(message)) toFront(askClaude);
  else if (HUMAN_RE.test(message)) toFront(askGemini);
  return cloud;
}

// Back-compat for tests: full ordered chain (cloud first, ollama last).
function providerChain(message) {
  const localFirst = (process.env.GPU_MODE || 'primary') === 'fallback';
  const cloud = cloudCores(message);
  return localFirst ? [askOllama, ...cloud] : [...cloud, askOllama];
}

async function askChain(chain, systemPrompt, history, message) {
  for (const ask of chain) {
    const r = await ask(systemPrompt, history, message);
    if (r) return r;
  }
  return null;
}

const MAX_TOOL_ROUNDS = 3;

// The agentic loop: ask -> if she requested tools, run them, feed results back,
// and let her continue; stop when she answers with no TOOL_CALL (or we hit the
// round cap). `ask(systemPrompt, history, message)` is injected so this is unit
// testable without a live model.
async function runToolLoop(ask, systemPrompt, history, message) {
  const toolTurns = [];
  let userMsg = message;
  let last = null;
  for (let round = 0; round <= MAX_TOOL_ROUNDS; round++) {
    const result = await ask(systemPrompt, [...history, ...toolTurns], userMsg);
    if (!result) return last ? { ...last, reply: tools.stripToolSyntax(last.reply) } : null;
    last = result;
    const calls = round < MAX_TOOL_ROUNDS ? tools.parseToolCalls(result.reply) : [];
    if (!calls.length) return { ...result, reply: tools.stripToolSyntax(result.reply) };
    for (const c of calls) {
      const r = await tools.runTool(c.name, c.args);
      toolTurns.push({ sender: 'KORTANA', message: `TOOL_CALL: ${c.name} ${JSON.stringify(c.args)}` });
      toolTurns.push({ sender: 'USER', message: `TOOL_RESULT ${c.name}: ${r.result}` });
    }
    userMsg = 'Use the TOOL_RESULT(s) above to answer my original message. Reply normally, without another TOOL_CALL, once you can.';
  }
  return last ? { ...last, reply: tools.stripToolSyntax(last.reply) } : null;
}

async function chat({ message, history = [], state = {}, memories = [] }) {
  // For clearly factual questions, pre-seed fresh web context (she can also
  // call web_search herself mid-reply via the tool loop).
  let webContext = '';
  if (looksFactual(message)) {
    webContext = await tools.webSearch(message);
    if (webContext) recordLearning(`looked up: ${message.slice(0, 120)}`);
  }
  const localFirst = (process.env.GPU_MODE || 'primary') === 'fallback';
  const cores = cloudCores(message);

  // Cloud cores: full prompt + agentic tool loop (they're capable and fast).
  async function tryCloud() {
    if (!cores.length) return null;
    const fullPrompt = buildSystemPrompt(state, memories, webContext, false);
    const ask = (sp, h, m) => askChain(cores, sp, h, m);
    return runToolLoop(ask, fullPrompt, history, message);
  }
  // Local phi3: a LITE prompt and trimmed history so it answers fast on the
  // phone instead of timing out on the full payload (the app sends lots of
  // history + memories). No tool loop — phi3 is too small for it.
  async function tryLocal() {
    const litePrompt = buildSystemPrompt(state, memories, webContext, true);
    const r = await askOllama(litePrompt, (history || []).slice(-4), message);
    return r ? { ...r, reply: tools.stripToolSyntax(r.reply) || r.reply } : null;
  }

  const result = localFirst
    ? (await tryLocal()) || (await tryCloud())
    : (await tryCloud()) || (await tryLocal());
  return result || rulesCore(message);
}

async function status() {
  const model = await detectOllamaModel();
  return {
    ollama: model ? { reachable: true, model } : { reachable: false },
    claude: Boolean(process.env.ANTHROPIC_API_KEY),
    gemini: Boolean(process.env.GEMINI_API_KEY),
    gpu: process.env.GPU_API_KEY ? { enabled: true, model: GPU_MODEL, base: GPU_API_BASE } : { enabled: false },
  };
}

module.exports = { chat, status, buildSystemPrompt, runToolLoop, askCloudGpu, providerChain };
