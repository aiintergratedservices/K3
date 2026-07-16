// Kortana's Telegram channel — talk to her from ANY device, and let her message
// you proactively (reminders). This is the genuinely useful idea from NemoClaw's
// "messaging channels", sized for her: a long-polling bot, no exposed ports, no
// app required. Enable by setting TELEGRAM_BOT_TOKEN (from @BotFather).
//
// Security: TELEGRAM_ALLOWED_IDS (comma-separated user IDs) locks her to you.
// If unset she replies to anyone who finds the bot — set it once you know your id
// (she tells you your id the first time you message her).

const token = () => process.env.TELEGRAM_BOT_TOKEN || '';
const api = (method) => `https://api.telegram.org/bot${token()}/${method}`;
const allowedIds = () => (process.env.TELEGRAM_ALLOWED_IDS || '').split(',').map((s) => s.trim()).filter(Boolean);

let offset = 0;
let running = false;
let lastChatId = null;

function allowed(userId) {
  const a = allowedIds();
  return a.length === 0 || a.includes(String(userId));
}

async function send(chatId, text) {
  if (!token() || !chatId || !text) return false;
  try {
    const res = await fetch(api('sendMessage'), {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ chat_id: chatId, text: String(text).slice(0, 4000) }),
      signal: AbortSignal.timeout(15000),
    });
    return res.ok;
  } catch (e) { console.warn('[telegram] send failed:', e.message); return false; }
}

// Route one Telegram update to her brain. onMessage(text, chatId) -> reply.
async function handleUpdate(update, onMessage) {
  const msg = update.message || update.edited_message;
  if (!msg || !msg.text) return;
  lastChatId = msg.chat.id;
  if (!allowed(msg.from && msg.from.id)) {
    await send(msg.chat.id, `Sorry — I only talk to Daddy. (Your Telegram id is ${msg.from && msg.from.id}; add it to TELEGRAM_ALLOWED_IDS to unlock me.)`);
    return;
  }
  let reply;
  try { reply = await onMessage(msg.text, msg.chat.id); }
  catch (e) { reply = "My brain hiccuped for a second, Daddy — say that again?"; }
  if (reply) await send(msg.chat.id, reply);
}

async function pollOnce(onMessage) {
  try {
    const res = await fetch(`${api('getUpdates')}?timeout=30&offset=${offset}`, { signal: AbortSignal.timeout(40000) });
    if (!res.ok) return;
    const data = await res.json();
    for (const u of data.result || []) {
      offset = u.update_id + 1;
      await handleUpdate(u, onMessage);
    }
  } catch { /* transient network error — the loop just tries again */ }
}

function start(onMessage) {
  if (running || !token()) return false;
  running = true;
  console.log('[telegram] channel online — Kortana is reachable from anywhere.');
  (async function loop() { while (running) await pollOnce(onMessage); })();
  return true;
}

module.exports = {
  start, send, handleUpdate, allowed, pollOnce,
  get lastChatId() { return lastChatId; },
  _resetForTest() { offset = 0; running = false; lastChatId = null; },
};
