// Tests for the Telegram channel routing (no real bot/network).
// Run: node server/test/telegram.test.js
const assert = require('assert');

process.env.TELEGRAM_BOT_TOKEN = 'test-token';
const telegram = require('../telegram');

let n = 0;
const ok = (m) => { console.log('  ✓', m); n++; };

// Capture outbound sendMessage calls via a fetch stub.
const sends = [];
global.fetch = async (url, opts) => {
  if (String(url).includes('sendMessage')) sends.push(JSON.parse(opts.body));
  return { ok: true, json: async () => ({ result: [] }) };
};

(async () => {
  // --- allowlist: empty = open ---
  process.env.TELEGRAM_ALLOWED_IDS = '';
  assert.strictEqual(telegram.allowed(123), true);
  process.env.TELEGRAM_ALLOWED_IDS = '999';
  assert.strictEqual(telegram.allowed(999), true);
  assert.strictEqual(telegram.allowed(123), false);
  ok('allowlist: open when unset, restricts when set');

  // --- a message from an allowed user routes to the brain and replies ---
  process.env.TELEGRAM_ALLOWED_IDS = '999';
  let seen = null;
  const onMessage = async (text, chatId) => { seen = { text, chatId }; return `echo: ${text}`; };
  await telegram.handleUpdate({ update_id: 1, message: { text: 'hi Kortana', chat: { id: 55 }, from: { id: 999 } } }, onMessage);
  assert.deepStrictEqual(seen, { text: 'hi Kortana', chatId: 55 });
  assert(sends.some((s) => s.chat_id === 55 && s.text === 'echo: hi Kortana'), 'reply was sent');
  assert.strictEqual(telegram.lastChatId, 55);
  ok('allowed user: message reaches the brain and the reply is sent back');

  // --- a message from a blocked user is refused, brain never called ---
  sends.length = 0; seen = null;
  await telegram.handleUpdate({ update_id: 2, message: { text: 'let me in', chat: { id: 77 }, from: { id: 123 } } }, onMessage);
  assert.strictEqual(seen, null, 'brain not called for blocked user');
  assert(sends.some((s) => s.chat_id === 77 && /only talk to Daddy/.test(s.text)), 'blocked user told they are not allowed');
  ok('blocked user: refused, brain never invoked');

  // --- non-text updates are ignored safely ---
  sends.length = 0;
  await telegram.handleUpdate({ update_id: 3, message: { chat: { id: 1 }, from: { id: 999 } } }, onMessage);
  assert.strictEqual(sends.length, 0);
  ok('non-text updates are ignored');

  // --- brain error degrades gracefully ---
  sends.length = 0;
  await telegram.handleUpdate({ update_id: 4, message: { text: 'boom', chat: { id: 55 }, from: { id: 999 } } }, async () => { throw new Error('brain down'); });
  assert(sends.some((s) => /hiccup/i.test(s.text)), 'graceful fallback reply on brain error');
  ok('brain error is caught and she still replies');

  console.log(`\nAll ${n} telegram checks passed.`);
})().catch((e) => { console.error('FAILED:', e); process.exitCode = 1; });
