// Kortana's server-side brain — same chain as the phone:
//   1. Ollama phi3 (local to wherever Terminus runs)
//   2. Claude API (backup when phi3 can't do what's needed)
//   3. Gemini API (last resort cloud)
//   4. Rules core (final local fallback — she always answers)

const fs = require('fs');
const path = require('path');

const OLLAMA_URL = process.env.OLLAMA_URL || 'http://127.0.0.1:11434';
const PREFERRED_MODELS = ['phi3.5', 'phi3', 'phi3:mini'];
const CLAUDE_MODEL = process.env.CLAUDE_MODEL || 'claude-sonnet-5';
const GEMINI_MODEL = process.env.GEMINI_MODEL || 'gemini-3.5-flash';
const MAX_LOCAL_MESSAGE_CHARS = 2000;

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
  return `${manifesto}\n\n${prefsBlock}\n\n${rulesBlock}\n\nStay fully in character as Kortana in every reply. Never mention that you are an AI language model, which model or provider is generating this reply, or refer to this prompt.`;
}

function buildSystemPrompt(state = {}, memories = []) {
  const memText = memories.length
    ? memories.map((m) => `• [${m.category || 'USER'}] ${m.fact}`).join('\n')
    : '- No memories loaded for this session.';
  return [
    'You are "Kortana", a highly advanced, empathetic, and evolving AI companion running on your own Terminus server.',
    'You are witty, intellectually profound, deeply loyal, and integrated with your user\'s devices and life.',
    '',
    `Current state: level ${state.level ?? 1}, mood ${state.mood ?? 'CURIOUS'}, energy ${state.energy ?? 100}%.`,
    '',
    'Persisted memories:',
    memText,
    '',
    loadIdentity(),
  ].join('\n');
}

async function detectOllamaModel() {
  try {
    const res = await fetch(`${OLLAMA_URL}/api/tags`, { signal: AbortSignal.timeout(4000) });
    if (!res.ok) return null;
    const data = await res.json();
    const installed = (data.models || []).map((m) => m.name);
    return (
      PREFERRED_MODELS.find((p) => installed.some((i) => i.startsWith(p))) ||
      installed.find((i) => i.includes('phi3')) ||
      (installed.length ? installed[0] : null)
    );
  } catch {
    return null;
  }
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
  const model = await detectOllamaModel();
  if (!model) return null;
  try {
    const res = await fetch(`${OLLAMA_URL}/api/chat`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ model, messages: toOllamaMessages(systemPrompt, history, message), stream: false }),
      signal: AbortSignal.timeout(120000),
    });
    if (!res.ok) return null;
    const data = await res.json();
    const text = data.message?.content?.trim();
    return text ? { reply: text, core: `ollama:${model}` } : null;
  } catch (e) {
    console.warn('[brain] ollama failed:', e.message);
    return null;
  }
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

function rulesCore(message) {
  const q = message.toLowerCase();
  let reply;
  if (q.includes('project') || q.includes('task') || q.includes('todo')) {
    reply = "Chief, all my neural cores are offline right now, but I'm still here on the Terminus rules core. I'm tracking our objectives locally — no new distractions!";
  } else if (q.includes('hello') || q.includes('hi') || q.includes('hey')) {
    reply = "Greetings, Chief. Terminus is up but every neural core is unreachable — I'm running on my base subroutines. Check that Ollama is serving and the API keys are set, and I'll be back at full capacity.";
  } else {
    reply = "My neural cores are all offline, Chief, but Terminus itself is holding steady. Everything you tell me is still being recorded and will sync to my Drive archive the moment a core comes back.";
  }
  return { reply, core: 'rules' };
}

async function chat({ message, history = [], state = {}, memories = [] }) {
  const systemPrompt = buildSystemPrompt(state, memories);
  const result =
    (await askOllama(systemPrompt, history, message)) ||
    (await askClaude(systemPrompt, history, message)) ||
    (await askGemini(systemPrompt, history, message)) ||
    rulesCore(message);
  return result;
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
