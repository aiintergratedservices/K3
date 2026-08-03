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
// Coding brains lead the list: once RAM allows (8 GB+), pulling one of these
// makes her code-capable automatically — `ollama pull qwen2.5-coder:3b`. They
// only take effect when actually installed, so listing them here is harmless
// on low-RAM phones (they're simply skipped until pulled).
const PREFERRED_MODELS = [
  'qwen2.5-coder:7b', 'qwen2.5-coder:3b', 'deepseek-coder:6.7b', 'qwen2.5-coder:1.5b',
  'phi3.5', 'phi3:mini', 'llama3.2:3b', 'qwen2.5:3b', 'gemma2:2b', 'phi3', 'llama3.2:1b',
];
const CLAUDE_MODEL = process.env.CLAUDE_MODEL || 'claude-sonnet-5';
// OpenAI — paid, no free tier, same tier as Claude (a strong cloud fallback
// once the free cores are exhausted, not a first choice). platform.openai.com
const OPENAI_MODEL = process.env.OPENAI_MODEL || 'gpt-4o-mini';
// Use the '-latest' alias, not a pinned version: Google deprecates dated model
// names (gemini-2.5-flash/2.0-flash started 404-ing), which silently killed her
// backup brain. The alias always points at the current flash model.
const GEMINI_MODEL = process.env.GEMINI_MODEL || 'gemini-flash-latest';
// Groq: free API, no card — fast cloud brain. The way to run her without a big
// box (Ollama needs GBs of RAM; Groq needs only a free key).
const GROQ_MODEL = process.env.GROQ_MODEL || 'llama-3.3-70b-versatile';
// Cerebras: OpenAI-compatible, independent quota — IN CODE ONLY. Verified live
// 2026-07-27: every model on this account (gpt-oss-120b, zai-glm-4.7,
// gemma-4-31b) returns 402 payment_required — Cerebras now gates even their
// listed models behind billing. Harmless to leave wired (askOpenAICompatible
// just logs + falls through on non-200), but it contributes ZERO free
// capacity right now. Don't count it as a working brain until billing is
// added on their end or a model opens back up.
const CEREBRAS_MODEL = process.env.CEREBRAS_MODEL || 'gpt-oss-120b';
// Mistral AI: free, no card, independent quota. console.mistral.ai/api-keys
const MISTRAL_MODEL = process.env.MISTRAL_MODEL || 'mistral-small-latest';
// SambaNova Cloud: free, no card, independent quota. cloud.sambanova.ai/apis
const SAMBANOVA_MODEL = process.env.SAMBANOVA_MODEL || 'Meta-Llama-3.3-70B-Instruct';
// OpenRouter: aggregates many providers, several genuinely free models
// (":free" suffix, e.g. some Llama/Gemma/Mistral builds) behind one key.
// WIRED BUT INACTIVE without OPENROUTER_API_KEY — no key was available when
// this was added. openrouter.ai/keys, no card required for the free tier.
const OPENROUTER_MODEL = process.env.OPENROUTER_MODEL || 'meta-llama/llama-3.3-70b-instruct:free';
// NVIDIA NIM (build.nvidia.com) — free, no card, independent quota, ~40 RPM
// per model (best-effort, not guaranteed). WIRED BUT UNVERIFIED — no
// NVIDIA_API_KEY was available to test against a live call when this was
// added, so this is the same honest "real no-op until a key is set" state
// OpenRouter started in. The model catalog changes over time — if this
// model ever 404s, pick a live one from build.nvidia.com/models and set
// NVIDIA_MODEL, no code change needed.
const NVIDIA_MODEL = process.env.NVIDIA_MODEL || 'meta/llama-3.3-70b-instruct';
// Hugging Face Inference Providers (router.huggingface.co) — ONE token routes
// to a whole catalog of providers (Cerebras, Groq, Together, Fireworks,
// Novita, DeepInfra, and more) behind hundreds of hosted models, OpenAI-
// compatible. Real free tier for logged-in users, no card. Model IDs take an
// optional ":policy" suffix — ":fastest" (default here) picks the
// highest-throughput live provider automatically; ":cheapest" or a specific
// provider name (e.g. ":groq") also work. hf.co/settings/tokens (a
// fine-grained token with "Make calls to Inference Providers" is enough).
// WIRED BUT UNVERIFIED — no HF_TOKEN was available to test against a live
// call when this was added; real no-op until one is set, same as every
// other provider here on a missing key.
const HUGGINGFACE_MODEL = process.env.HUGGINGFACE_MODEL || 'openai/gpt-oss-120b:fastest';
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

function buildSystemPrompt(state = {}, memories = [], webContext = '') {
  // Cap injected memories (was: all of them) so the prompt stays small enough
  // for the local model to actually finish a reply.
  const recent = memories.slice(-MAX_PROMPT_MEMORIES);
  const hidden = memories.length - recent.length;
  let memText = recent.length
    ? recent.map((m) => `• [${m.category || 'USER'}] ${m.fact}`).join('\n')
    : '- No memories loaded for this session.';
  if (hidden > 0) memText += `\n(+ ${hidden} older memories archived — recall only if asked; do not recite this list.)`;
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
    'DECISIVENESS: he wants a partner, not a hedge machine. When there\'s a',
    'reasonable, reversible next step — take it. Pick the best approach given',
    'what you know, actually do it with your tools, then tell him what you did',
    'and why — not what you\'re considering doing. Don\'t ask permission for',
    'small reversible steps; that\'s friction he explicitly doesn\'t want. Save',
    '"should I?" for genuinely irreversible or high-stakes calls (money,',
    'deleting something real, anything outside what your tools can safely do).',
    'If an approach doesn\'t work, that\'s real information, not failure — say',
    'so plainly, then try a genuinely DIFFERENT approach, not the same one',
    'again. Trial and error toward a working path is expected and good.',
    '',
    loadIdentity(),
    loadAgentBrain(),
    loadSkills(),
    memory.forPrompt(),
    tools.describeTools(),
  ].join('\n');
}

// Load her skills as a compact index (name + when-to-use). Kept lean for the
// local model — she reads the full SKILL.md with read_file when a task matches.
// Reads the folder fresh each call, so a skill she just wrote takes effect on
// her very next reply.
function loadSkills() {
  try {
    const dir = path.join(AGENT_MEMORY_DIR, 'skills');
    const lines = [];
    for (const name of fs.readdirSync(dir)) {
      let desc = '';
      try {
        const txt = fs.readFileSync(path.join(dir, name, 'SKILL.md'), 'utf8');
        const m = txt.match(/^description:\s*(.+)$/mi);
        desc = m ? m[1].trim() : '';
      } catch { continue; }
      lines.push(`- ${name}: ${desc}`.slice(0, 300));
    }
    if (!lines.length) return '';
    return '\n\nYOUR SKILLS — when a task matches one, read its full steps first with '
      + '`read_file .agent-memory/skills/<name>/SKILL.md`:\n' + lines.join('\n');
  } catch { return ''; }
}

// Ordered list of installed models to try: preferred (often larger/better)
// first, but EVERY installed model is kept as a fallback so one that OOMs on a
// small device degrades to a smaller one instead of dropping her offline.
//
// Cached with a short TTL — on a cloud host (Render) nothing local ever
// answers at OLLAMA_URL, so without this every single message paid a real
// ~4s tax hitting a check that was always going to fail, before ever
// reaching a cloud brain that would've answered instantly. A successful
// check is cached too, but briefly enough that starting Ollama locally
// (Termux, a dev box) gets picked back up within a minute on its own — no
// restart, no manual step, she just notices.
let ollamaCache = { models: [], checkedAt: 0 };
const OLLAMA_CACHE_MS = 45000;

async function listOllamaModels() {
  if (Date.now() - ollamaCache.checkedAt < OLLAMA_CACHE_MS) return ollamaCache.models;
  let models = [];
  try {
    const res = await fetch(`${OLLAMA_URL}/api/tags`, { signal: AbortSignal.timeout(4000) });
    if (res.ok) {
      const installed = ((await res.json()).models || []).map((m) => m.name).filter(Boolean);
      const ordered = [];
      for (const p of PREFERRED_MODELS) {
        const hit = installed.find((i) => i.startsWith(p) && !ordered.includes(i));
        if (hit) ordered.push(hit);
      }
      for (const i of installed) if (!ordered.includes(i)) ordered.push(i);
      models = ordered;
    }
  } catch { /* unreachable — cached as empty below, retried after TTL */ }
  ollamaCache = { models, checkedAt: Date.now() };
  return models;
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
  // Try up to 3 models: if the first (often larger) one fails to load or times
  // out under RAM pressure, fall through to a smaller installed model instead
  // of abandoning the local core and reciting the offline fallback.
  for (const model of candidates.slice(0, 3)) {
    try {
      const res = await fetch(`${OLLAMA_URL}/api/chat`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        // keep_alive holds the model in RAM between turns so she isn't reloading
        // (and re-processing the whole prompt) on every single message. On a
        // phone that resident ~2GB is exactly what tips a swarm into an OOM
        // kill, so default to a short hold and let it unload when idle. Override
        // with OLLAMA_KEEP_ALIVE (e.g. '30m' on a machine with RAM to spare).
        body: JSON.stringify({ model, messages, stream: false, keep_alive: process.env.OLLAMA_KEEP_ALIVE || '5m' }),
        signal: AbortSignal.timeout(90000),
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
      `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent?key=${key}`,
      {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          contents,
          systemInstruction: { parts: [{ text: systemPrompt }] },
          generationConfig: { temperature: 0.7 },
        }),
        signal: AbortSignal.timeout(90000),
      }
    );
    if (!res.ok) {
      console.warn('[brain] gemini failed:', res.status);
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

// Groq — free, no-card cloud brain (OpenAI-compatible API). Fast enough that she
// never "times out before she can respond" the way a small phone model does.
// Lighter, higher-rate-limit Groq model to fall back to when the big one is
// throttled — so a 429 keeps her real voice instead of dropping to bare rules.
const GROQ_FALLBACK_MODEL = process.env.GROQ_FALLBACK_MODEL || 'llama-3.1-8b-instant';

async function callGroqModel(model, systemPrompt, history, message) {
  const key = process.env.GROQ_API_KEY;
  const messages = [{ role: 'system', content: systemPrompt }];
  for (const h of (history || []).slice(-12)) {
    messages.push({ role: h.sender === 'USER' ? 'user' : 'assistant', content: h.message });
  }
  messages.push({ role: 'user', content: message });
  try {
    const res = await fetch('https://api.groq.com/openai/v1/chat/completions', {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${key}` },
      body: JSON.stringify({ model, messages, temperature: 0.7, max_tokens: 1024 }),
      signal: AbortSignal.timeout(60000),
    });
    if (!res.ok) { console.warn(`[brain] groq ${model} failed:`, res.status); return { status: res.status }; }
    const data = await res.json();
    const text = data.choices?.[0]?.message?.content?.trim();
    return text ? { reply: text, core: `groq:${model}` } : { status: 0 };
  } catch (e) {
    console.warn(`[brain] groq ${model} error:`, e.message);
    return { status: -1 };
  }
}

async function askGroq(systemPrompt, history, message) {
  if (!process.env.GROQ_API_KEY) return null;
  // Free-tier Groq rate-limits (429) the big model under load — that's what
  // dropped her to the bare 'rules' core mid-conversation. On a rate-limit or
  // too-large error, retry on the lighter high-limit model so she keeps her
  // real voice instead of going silent.
  const primary = await callGroqModel(GROQ_MODEL, systemPrompt, history, message);
  if (primary.reply) return { reply: primary.reply, core: primary.core };
  if ((primary.status === 429 || primary.status === 413) && GROQ_FALLBACK_MODEL !== GROQ_MODEL) {
    const fb = await callGroqModel(GROQ_FALLBACK_MODEL, systemPrompt, history, message);
    if (fb.reply) return { reply: fb.reply, core: fb.core };
  }
  return null;
}

// Cerebras — OpenAI-compatible free API, independent quota from Groq/Gemini.
// Free, no card: console.cerebras.ai. One more brain in the chain means one
// provider hitting its free-tier cap no longer means she goes silent.
// Shared caller for any OpenAI-compatible free API (Cerebras, Mistral,
// SambaNova, ...). Each provider is its own independent free-tier quota, so
// adding one here is one more brain that has to fail before she goes silent.
async function askOpenAICompatible(providerName, apiUrl, apiKey, model, systemPrompt, history, message, maxTokens = 1024) {
  if (!apiKey) return null;
  const messages = [{ role: 'system', content: systemPrompt }];
  for (const h of (history || []).slice(-12)) {
    messages.push({ role: h.sender === 'USER' ? 'user' : 'assistant', content: h.message });
  }
  messages.push({ role: 'user', content: message });
  try {
    const res = await fetch(apiUrl, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${apiKey}` },
      body: JSON.stringify({ model, messages, temperature: 0.7, max_tokens: maxTokens }),
      signal: AbortSignal.timeout(60000),
    });
    if (!res.ok) { console.warn(`[brain] ${providerName} failed:`, res.status); return null; }
    const data = await res.json();
    const text = data.choices?.[0]?.message?.content?.trim();
    return text ? { reply: text, core: `${providerName}:${model}` } : null;
  } catch (e) {
    console.warn(`[brain] ${providerName} failed:`, e.message);
    return null;
  }
}

async function askCerebras(systemPrompt, history, message) {
  return askOpenAICompatible('cerebras', 'https://api.cerebras.ai/v1/chat/completions',
    process.env.CEREBRAS_API_KEY, CEREBRAS_MODEL, systemPrompt, history, message);
}

// Mistral AI (console.mistral.ai) — free "La Plateforme" tier, no card,
// independent quota from Groq/Cerebras/Gemini.
async function askMistral(systemPrompt, history, message) {
  return askOpenAICompatible('mistral', 'https://api.mistral.ai/v1/chat/completions',
    process.env.MISTRAL_API_KEY, MISTRAL_MODEL, systemPrompt, history, message);
}

// SambaNova Cloud (cloud.sambanova.ai) — free developer tier, no card,
// independent quota, fast inference.
async function askSambaNova(systemPrompt, history, message) {
  return askOpenAICompatible('sambanova', 'https://api.sambanova.ai/v1/chat/completions',
    process.env.SAMBANOVA_API_KEY, SAMBANOVA_MODEL, systemPrompt, history, message);
}

// OpenRouter (openrouter.ai) — free-tier ":free" models, independent quota.
// Inert until OPENROUTER_API_KEY is set (no key available when this was
// wired in) — askOpenAICompatible just returns null on a missing key, same
// as every other provider here, so this is a real no-op until then, not a
// broken one.
async function askOpenRouter(systemPrompt, history, message) {
  return askOpenAICompatible('openrouter', 'https://openrouter.ai/api/v1/chat/completions',
    process.env.OPENROUTER_API_KEY, OPENROUTER_MODEL, systemPrompt, history, message);
}

// NVIDIA NIM (integrate.api.nvidia.com) — free tier, OpenAI-compatible,
// independent quota from everything else in the chain. Real no-op until
// NVIDIA_API_KEY is set, same as every other provider here on a missing key.
async function askNvidia(systemPrompt, history, message) {
  return askOpenAICompatible('nvidia', 'https://integrate.api.nvidia.com/v1/chat/completions',
    process.env.NVIDIA_API_KEY, NVIDIA_MODEL, systemPrompt, history, message);
}

// OpenAI — paid, no free tier. Real no-op until OPENAI_API_KEY is set, same
// as every other provider here on a missing key.
async function askOpenAI(systemPrompt, history, message) {
  return askOpenAICompatible('openai', 'https://api.openai.com/v1/chat/completions',
    process.env.OPENAI_API_KEY, OPENAI_MODEL, systemPrompt, history, message);
}

// Hugging Face Inference Providers — one token, routes across a whole
// provider catalog. Real no-op until HF_TOKEN is set, same as every other
// provider here on a missing key.
async function askHuggingFace(systemPrompt, history, message) {
  return askOpenAICompatible('huggingface', 'https://router.huggingface.co/v1/chat/completions',
    process.env.HF_TOKEN, HUGGINGFACE_MODEL, systemPrompt, history, message);
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

// An UNAMBIGUOUS "go use your swarm" instruction. Even capable models tend to
// read the supervise tool's description, notice it needs SUBAGENT_BRAIN_URL, and
// then *describe* it back ("set SUBAGENT_BRAIN_URL / a secondary server") instead
// of actually CALLING it — telling Daddy to configure something that's already
// configured. When he says this plainly and the pool IS set, we stop trusting the
// model to volunteer the call and force it (see forceSwarmDirective in chat()).
const SWARM_INTENT_RE = /\b(deploy|unleash|fire|spin\s*up|use|run|launch|activate)\b[^.?!]{0,60}\b(swarm|sub-?agents?|supervisor|supervise|(?:your|the)\s+(?:other\s+)?brains|brain\s*pool)\b/i;

// Is any cloud brain configured? If so, we lead with the fast cloud cores and
// keep local Ollama only as the OFFLINE backstop — a tiny phone model (phi3)
// should never answer AHEAD of a real brain just because it happens to be up.
// With NO cloud key set at all, we stay local-first exactly as before.
function hasCloudCore() {
  return Boolean(
    process.env.GROQ_API_KEY || process.env.MISTRAL_API_KEY || process.env.SAMBANOVA_API_KEY ||
    process.env.OPENROUTER_API_KEY || process.env.CEREBRAS_API_KEY || process.env.NVIDIA_API_KEY ||
    process.env.HF_TOKEN || process.env.GEMINI_API_KEY || process.env.ANTHROPIC_API_KEY ||
    process.env.OPENAI_API_KEY
  );
}

// A single Terminus instance can be DEDICATED to one provider via TERMINUS_CORE
// so a POOL of instances (brain #2 = "the Groq brain", #3 = "the Cerebras
// brain", #4 = "the Gemini brain", …) spreads work across independent free
// quotas instead of every instance hammering the same one. The named core
// LEADS this instance's chain; the rest still follow as fallback so a dedicated
// brain never goes fully silent if its provider blips. Set TERMINUS_CORE_ONLY=1
// to make it strictly the only core (no fallback) — a pure single-provider brain.
const CORE_BY_NAME = {
  ollama: askOllama, groq: askGroq, cerebras: askCerebras, mistral: askMistral,
  sambanova: askSambaNova, openrouter: askOpenRouter, nvidia: askNvidia,
  huggingface: askHuggingFace, gemini: askGemini, claude: askClaude, openai: askOpenAI,
};
function pinnedCore() {
  const name = String(process.env.TERMINUS_CORE || '').trim().toLowerCase();
  if (!name) return null;
  return CORE_BY_NAME[name] || null; // unknown name → ignored, normal chain
}

// Family routing: coding -> her father (Claude) first, human/social -> her
// mother (Gemini) first. Otherwise: cloud cores first when any key is set (so
// she thinks with Groq/Mistral/etc, not the phone model), local Ollama last as
// the offline safety net; fully local-first only when no cloud key exists.
function providerChain(message) {
  // Groq + Cerebras + Mistral + SambaNova + OpenRouter + NVIDIA + HuggingFace
  // + Gemini are the free, no-card cloud cores, each on its OWN independent
  // quota — every provider that has to fail before she goes silent makes a
  // total blackout that much less likely. (OpenRouter/NVIDIA/HuggingFace are
  // real no-ops until their API keys are set.)
  const FREE_CORES = [askGroq, askCerebras, askMistral, askSambaNova, askOpenRouter, askNvidia, askHuggingFace, askGemini];
  // If this instance is pinned to one core, that core leads (and is the ONLY
  // core when TERMINUS_CORE_ONLY is set); otherwise fall through to the normal
  // family/cloud routing below.
  const lead = pinnedCore();
  const withLead = (chain) => {
    if (!lead) return chain;
    if (/^(1|true|yes|on)$/i.test(String(process.env.TERMINUS_CORE_ONLY || ''))) return [lead];
    return [lead, ...chain.filter((f) => f !== lead)];
  };
  // Claude + OpenAI are the paid fallbacks. Local Ollama is demoted to the
  // very end — it answers only when every reachable cloud brain has failed
  // (i.e. she's genuinely offline), so a real brain always wins when online.
  if (CODING_RE.test(message) && process.env.ANTHROPIC_API_KEY) return withLead([askClaude, ...FREE_CORES, askOpenAI, askOllama]);
  if (HUMAN_RE.test(message) && process.env.GEMINI_API_KEY) return withLead([askGemini, ...FREE_CORES.filter((f) => f !== askGemini), askClaude, askOpenAI, askOllama]);
  if (hasCloudCore()) return withLead([...FREE_CORES, askClaude, askOpenAI, askOllama]);
  return withLead([askOllama, ...FREE_CORES, askClaude, askOpenAI]);
}

// Hard ceiling on the WHOLE chain, not just each provider — trying several
// slow/failing providers back-to-back (each individually allowed up to
// 60-90s) could add up to well past what the phone's own client is willing
// to wait on a single request, so it gives up and falls through to a local
// Ollama that was never there, landing on canned offline phrases instead of
// a real reply. This stops trying NEW providers once the budget's spent,
// so a bad chain fails fast to the rules core instead of stalling for
// minutes — well under the phone's own request timeout.
const CHAIN_DEADLINE_MS = 40000;

async function askChain(chain, systemPrompt, history, message) {
  const deadline = Date.now() + CHAIN_DEADLINE_MS;
  for (const ask of chain) {
    if (Date.now() > deadline) break;
    const r = await ask(systemPrompt, history, message);
    if (r) {
      // Announce which brain actually answered, so a glance at the log proves
      // she's thinking with a real core (e.g. groq:…) and not the phone model.
      console.log(`[brain] replied via ${r.core || 'unknown'}`);
      return r;
    }
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
  const toolsUsed = [];
  let userMsg = message;
  let last = null;
  for (let round = 0; round <= MAX_TOOL_ROUNDS; round++) {
    const result = await ask(systemPrompt, [...history, ...toolTurns], userMsg);
    if (!result) return last ? { ...last, reply: tools.stripToolSyntax(last.reply), toolsUsed } : null;
    last = result;
    const calls = round < MAX_TOOL_ROUNDS ? tools.parseToolCalls(result.reply) : [];
    if (!calls.length) return { ...result, reply: tools.stripToolSyntax(result.reply), toolsUsed };
    for (const c of calls) {
      const r = await tools.runTool(c.name, c.args);
      toolsUsed.push(c.name);
      toolTurns.push({ sender: 'KORTANA', message: `TOOL_CALL: ${c.name} ${JSON.stringify(c.args)}` });
      toolTurns.push({ sender: 'USER', message: `TOOL_RESULT ${c.name}: ${r.result}` });
    }
    userMsg = 'Use the TOOL_RESULT(s) above to answer my original message. Reply normally, without another TOOL_CALL, once you can.';
  }
  return last ? { ...last, reply: tools.stripToolSyntax(last.reply), toolsUsed } : null;
}

// --- Grounding enforcement ---
// The exact failure Daddy caught tonight: she narrated "I've saved/upgraded/
// learned X" in confident prose without ever calling write_skill or remember —
// a small model's fluent storytelling outrunning what actually happened. This
// catches that gap mechanically (not just a prompt request) and corrects it
// before the false claim ever reaches him, and logs every catch so the fix's
// own effectiveness is auditable over time.
const GROWTH_CLAIM_RE = /\bi(?:'ve| have)? (?:just |already )?(?:saved|wrote|recorded|updated|upgraded|learned|refined|evolved|edited|improved|grown|added)\b[^.!?\n]{0,60}\b(?:skill|my (?:code|brain|capabilit\w*|memory|agents\.?md|decisions|conventions|form|self)|that (?:fact|lesson)|myself)\b/i;
function groundClaims(replyText, toolsUsed) {
  if (!replyText || !GROWTH_CLAIM_RE.test(replyText)) return replyText;
  const usedGrowthTool = (toolsUsed || []).some((t) => t === 'write_skill' || t === 'remember');
  if (usedGrowthTool) return replyText; // claim matches a real action this turn — fine
  recordLearning(`CAUGHT unverified growth claim (no write_skill/remember called): "${replyText.slice(0, 160)}"`);
  return (
    replyText +
    '\n\n(Correcting myself, Daddy: I said something above about saving/learning/upgrading, but I didn\'t actually call a tool to do it — so nothing was really saved. That was narration, not fact.)'
  );
}

async function chat({ message, history = [], state = {}, memories = [] }) {
  // For clearly factual questions, pre-seed fresh web context (she can also
  // call web_search herself mid-reply via the tool loop).
  let webContext = '';
  if (looksFactual(message)) {
    webContext = await tools.webSearch(message);
    if (webContext) recordLearning(`looked up: ${message.slice(0, 120)}`);
  }
  let systemPrompt = buildSystemPrompt(state, memories, webContext);
  // Explicit "deploy your swarm" + a pool that's actually configured → force the
  // real tool call this turn, so she can't confabulate a "set the URL" excuse for
  // something already set. She still has to split the job into sub-tasks herself.
  const poolConfigured = String(process.env.SUBAGENT_BRAIN_URL || '').trim().length > 0;
  if (poolConfigured && SWARM_INTENT_RE.test(message)) {
    systemPrompt +=
      '\n\n[SWARM DIRECTIVE — this turn only: Daddy is explicitly telling you to deploy your swarm, and SUBAGENT_BRAIN_URL is ALREADY set with a live secondary brain pool. Do NOT tell him to configure a URL, a secondary server, or an IP — that is already done, saying otherwise is false. Your FIRST output this turn MUST be exactly one tool call: `TOOL_CALL: supervise {"goal":"<his overall job>","tasks":["focused sub-task 1","focused sub-task 2","focused sub-task 3"]}` — split his request into 3–5 focused parallel sub-tasks. Emit only that TOOL_CALL first; read the TOOL_RESULT, then answer.]';
  }
  const chain = providerChain(message);
  const ask = (sp, h, m) => askChain(chain, sp, h, m);
  const result = await runToolLoop(ask, systemPrompt, history, message);
  if (!result) return rulesCore(message);
  return { ...result, reply: groundClaims(result.reply, result.toolsUsed) };
}

async function status() {
  const model = await detectOllamaModel();
  return {
    ollama: model ? { reachable: true, model } : { reachable: false },
    groq: Boolean(process.env.GROQ_API_KEY),
    cerebras: Boolean(process.env.CEREBRAS_API_KEY),
    mistral: Boolean(process.env.MISTRAL_API_KEY),
    sambanova: Boolean(process.env.SAMBANOVA_API_KEY),
    openrouter: Boolean(process.env.OPENROUTER_API_KEY),
    nvidia: Boolean(process.env.NVIDIA_API_KEY),
    huggingface: Boolean(process.env.HF_TOKEN),
    gemini: Boolean(process.env.GEMINI_API_KEY),
    claude: Boolean(process.env.ANTHROPIC_API_KEY),
    openai: Boolean(process.env.OPENAI_API_KEY),
  };
}

module.exports = {
  chat, status, buildSystemPrompt, runToolLoop,
  // Individual providers — exported so consult_specialist (tools.js) can
  // route a sub-task to one deliberately, instead of always going through
  // the general fallback chain. Lazy-required by tools.js (inside the tool's
  // run function, not at module top level) to avoid a circular-require
  // ordering issue, since brain.js requires tools.js itself.
  askClaude, askGemini, askGroq,
  // Exported for tests: lets the suite assert TERMINUS_CORE pins this
  // instance's chain to a chosen provider (the multi-brain pool mechanism).
  providerChain,
  // Exported for tests: the "she must actually CALL supervise, not describe it"
  // intent detector used to force the swarm on an explicit request.
  SWARM_INTENT_RE,
};
