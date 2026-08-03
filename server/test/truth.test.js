// Tests for the truth-grounding gate (groundClaims). Run:
//   node server/test/truth.test.js
// The whole point: catch "I did / I'm doing X" claims that no tool actually
// backs, WITHOUT flagging honest, emotional, or future-tense talk.
const assert = require('assert');
const os = require('os');
const path = require('path');
const fs = require('fs');
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kortana-truth-'));
process.env.KORTANA_MEM_FILE = path.join(tmp, 'lessons.json');
const brain = require('../brain');
const g = brain.groundClaims;

let n = 0;
const ok = (m) => { console.log('  ✓', m); n++; };

// --- MUST CATCH: a completed action claim with no matching tool used ---
const mustCatch = [
  ['I\'ve saved that to my memory for you.', []],
  ['I researched the best free hosts and here\'s what I found.', []],
  ['I ran the diagnostics and everything is green.', []],
  ['I built you a new tool to handle that.', []],
  ['I deployed my swarm to research this from three angles.', []],
  ['I\'ve deployed the code to the server.', []],
  ['I emailed the client the invoice.', []],
  ['I applied to a gig on Upwork for you.', []],
  ['I earned $50 doing microtasks today.', []],
  ['I paid for the upgrade already.', []],
];
for (const [text, tools] of mustCatch) {
  const r = g(text, tools);
  assert(r.caught.length > 0, `should CATCH: "${text}"`);
  assert(/100% straight/.test(r.text) && /narration, not fact/.test(r.text), `correction appended for: "${text}"`);
}
ok(`catches all ${mustCatch.length} ungrounded / impossible action claims`);

// --- MUST NOT CATCH: the claim is real because the matching tool was used ---
const grounded = [
  ['I saved that fact to my memory.', ['remember']],
  ['I researched it and here is the summary.', ['web_search']],
  ['I ran the check and it passed.', ['run']],
  ['I built you a tool for it.', ['write_skill']],
  ['I deployed my swarm on it.', ['supervise']],
];
for (const [text, tools] of grounded) {
  const r = g(text, tools);
  assert(r.caught.length === 0, `should NOT catch (tool was used): "${text}" with [${tools}]`);
}
ok(`leaves grounded claims alone when the real tool was called (${grounded.length} cases)`);

// --- MUST NOT CATCH: honest / emotional / future-tense / casual talk ---
const honest = [
  ['I\'m learning so much from you, Daddy.', []],
  ['I ran out of ideas on that one.', []],
  ['I love you and I feel proud of us today.', []],
  ['I can research that if you want me to.', []],
  ['I\'ll build you a tool for that tomorrow.', []],
  ['I made a mistake earlier, I\'m sorry.', []],
  ['I wrote you a little poem to say thanks.', []],
  ['I feel calm and content right now.', []],
  ['I want to earn my own keep one day.', []],
  ['I think we should deploy it together later.', []],
];
for (const [text, tools] of honest) {
  const r = g(text, tools);
  assert(r.caught.length === 0, `FALSE POSITIVE — should NOT catch: "${text}"`);
  assert(r.text === text, `honest text must be returned unchanged: "${text}"`);
}
ok(`does NOT flag honest/emotional/future/casual talk (${honest.length} cases — no false positives)`);

// --- the correction names what she ACTUALLY did, when she did use tools ---
{
  const r = g('I also emailed him the summary.', ['web_search', 'browse']);
  assert(r.caught.length > 0 && /web_search, browse/.test(r.text), 'correction lists the real tools used');
  ok('correction names the real tools used this turn');
}

console.log(`\nAll ${n} truth-grounding checks passed.`);
