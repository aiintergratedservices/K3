// Kortana's server-side brain — same chain as the phone:
//   1. Ollama phi3 (local to wherever Terminus runs)
//   2. Claude API (backup when phi3 can't do what's needed)
//   3. Gemini API (last resort cloud)
//   4. Rules core (final local fallback — she always answers)

const fs = require('fs');
const path = require('path');
const memory = require('./memory');

const OLLAMA_URL = process.env.OLLAMA_URL || 'http://127.0.0.1:11434';
// Best-first: the first INSTALLED model wins, so pulling a bigger one upgrades
// her automatically (ollama pull llama3.2:3b / phi3:mini once RAM is freed).
const PREFERRED_MODELS = ['phi3.5', 'phi3:mini', 'llama3.2:3b', 'qwen2.5:3b', 'gemma2:2b', 'phi3', 'llama3.2:1b'];
const CLAUDE_MODEL = process.env.CLAUDE_MODEL || 'claude-sonnet-5';
const GEMINI_MODEL = process.env.GEMINI_MODEL || 'gemini-2.0-flash';
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
    loadIdentity(),
    loadAgentBrain(),
    memory.forPrompt(),
  ].join('\n');
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
  // Try up to 3 models: if the first (often larger) one fails to load or times
  // out under RAM pressure, fall through to a smaller installed model instead
  // of abandoning the local core and reciting the offline fallback.
  for (const model of candidates.slice(0, 3)) {
    try {
      const res = await fetch(`${OLLAMA_URL}/api/chat`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        // keep_alive holds the model in RAM between turns so she isn't reloading
        // (and re-processing the whole prompt) on every single message.
        body: JSON.stringify({ model, messages, stream: false, keep_alive: '30m' }),
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

// --- Web-search "learning" loop (no API key) --------------------------------
// This is the honest version of "learn from the internet": she looks up facts
// and uses them as context (retrieval), and records that she did so to her disk
// brain. It does NOT retrain her model — that isn't possible on-device.
const FACTUAL_RE = /\b(what|who|when|where|why|how|which|latest|current|news|today|price|cost|define|meaning|explain|search|look up|find out|weather|score|release|version)\b/i;
function looksFactual(message) {
  return WEB_LEARNING && message.length < 300 && (message.trim().endsWith('?') || FACTUAL_RE.test(message));
}

async function webSearch(query) {
  const clip = (s, n) => (s || '').replace(/\s+/g, ' ').trim().slice(0, n);
  const out = [];
  try {
    const u = `https://api.duckduckgo.com/?q=${encodeURIComponent(query)}&format=json&no_html=1&skip_disambig=1`;
    const res = await fetch(u, { signal: AbortSignal.timeout(6000), headers: { 'User-Agent': 'Kortana/1.0' } });
    if (res.ok) {
      const j = await res.json();
      if (j.AbstractText) out.push(`${j.AbstractText}${j.AbstractURL ? ' (' + j.AbstractURL + ')' : ''}`);
      for (const t of (j.RelatedTopics || []).slice(0, 3)) {
        if (t && t.Text) out.push(t.Text);
      }
    }
  } catch (e) { console.warn('[brain] ddg search failed:', e.message); }
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
    } catch (e) { console.warn('[brain] wiki search failed:', e.message); }
  }
  return out.slice(0, 4).map((s) => `- ${clip(s, 240)}`).join('\n');
}

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

async function chat({ message, history = [], state = {}, memories = [] }) {
  // Learning loop: for factual questions, look it up first and give her the
  // fresh facts as context, then record that she learned something.
  let webContext = '';
  if (looksFactual(message)) {
    webContext = await webSearch(message);
    if (webContext) recordLearning(`looked up: ${message.slice(0, 120)}`);
  }
  const systemPrompt = buildSystemPrompt(state, memories, webContext);
  let result;
  if (CODING_RE.test(message) && process.env.ANTHROPIC_API_KEY) {
    result =
      (await askClaude(systemPrompt, history, message)) ||
      (await askOllama(systemPrompt, history, message)) ||
      (await askGemini(systemPrompt, history, message));
  } else if (HUMAN_RE.test(message) && process.env.GEMINI_API_KEY) {
    result =
      (await askGemini(systemPrompt, history, message)) ||
      (await askOllama(systemPrompt, history, message)) ||
      (await askClaude(systemPrompt, history, message));
  } else {
    result =
      (await askOllama(systemPrompt, history, message)) ||
      (await askClaude(systemPrompt, history, message)) ||
      (await askGemini(systemPrompt, history, message));
  }
  return result || rulesCore(message);
}

async function status() {
  const model = await detectOllamaModel();
  return {
    ollama: model ? { reachable: true, model } : { reachable: false },
    claude: Boolean(process.env.ANTHROPIC_API_KEY),
    gemini: Boolean(process.env.GEMINI_API_KEY),
  };
}

module.exports = { chat, status, buildSystemPrompt };
