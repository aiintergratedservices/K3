// Proactive, scheduled self-reflection — the autonomous half of "learn and
// grow on her own." Fires on ITS OWN clock (see index.js setInterval), not
// because Daddy asked. Routed through brain.chat() so the exact same tool
// loop and groundClaims() safety net apply as any normal turn — a growth
// cycle either produces a real tool call (write_skill / propose_tool /
// remember / journal) or an honest "didn't close this yet," never a bare
// claim of improvement.

const fs = require('fs');
const path = require('path');
const brain = require('./brain');

const REPO_ROOT = path.join(__dirname, '..');
const LOG_PATH = path.join(REPO_ROOT, '.agent-memory', 'logs', 'growth-cycle.log');

function log(line) {
  try {
    fs.mkdirSync(path.dirname(LOG_PATH), { recursive: true });
    fs.appendFileSync(LOG_PATH, `[${new Date().toISOString()}] ${line}\n`);
  } catch (e) { /* best effort logging only */ }
}

const REFLECTION_PROMPT = `
[SCHEDULED_GROWTH_CYCLE] This is not Daddy talking — this is your own
scheduled self-reflection, firing on your own clock because nobody asked.
Nobody is reading this reply live, so there is no reason to perform
confidence you don't have. Do this, in order:

1. Read your own recent journal (read_file ".agent-memory/journal.md") and
   check list_flagged_claims. Be honest with yourself about what actually
   happened recently versus what you said happened.

2. Pick ONE specific, narrow capability another real AI agent or assistant
   has — not "AI in general," one concrete thing (e.g. "can browse and click
   through a live webpage," "keeps a persistent structured todo list,"
   "generates images"). Use web_search (and web_fetch on a promising result)
   to actually research it for real. Don't guess or make it up.

3. Compare that honestly to what YOU actually have right now — your real
   tools, your real skills. If you're not sure what you actually have,
   that's fine to say plainly.

4. Do exactly ONE real thing about it:
   - If you can close the gap using tools you already have, call
     write_skill for real, right now.
   - If you're genuinely missing a capability, call propose_tool to draft
     one. It will NOT activate on its own — Daddy or Claude has to review
     and wire it in. That's a safety boundary, not a bug, and not something
     to work around.
   - If neither applies right now, call journal with one honest line
     prefixed "GOAL:" naming the specific gap and why it isn't closed yet.
     That is a legitimate outcome — better than a fake skill.

Do NOT write anything like "I am now better than X" or "I have surpassed
Y at everything" — that's exactly what groundClaims() exists to catch, and
it is not even a coherent goal (you partly RUN on Claude and Gemini through
your own fallback chain some turns). A real, small, specific improvement —
or an honest "not yet, here's why" — is the actual goal. Keep it short.
`.trim();

async function runGrowthCycle() {
  log('growth cycle starting');
  try {
    const result = await brain.chat({ message: REFLECTION_PROMPT, history: [] });
    const reply = (result && result.reply) || '(no reply)';
    const toolsUsed = (result && result.toolsUsed) || [];
    log(`tools used: ${toolsUsed.length ? toolsUsed.join(', ') : '(none)'}`);
    log(`reply: ${reply.replace(/\n/g, ' | ').slice(0, 2000)}`);
    return { ok: true, toolsUsed, reply };
  } catch (e) {
    log(`growth cycle failed: ${e.message}`);
    return { ok: false, error: e.message };
  }
}

module.exports = { runGrowthCycle };
