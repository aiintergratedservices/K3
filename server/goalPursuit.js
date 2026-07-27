// Autonomous goal pursuit — "Daddy sets the end goal, she figures out how."
// Runs on its own schedule (see index.js), takes ONE real step per active
// goal per cycle using the full tool loop, and logs honest progress. A goal
// only flips to 'done' when she both (a) actually called a tool that turn
// and (b) explicitly marked it complete with evidence — a bare completion
// claim with no real action that turn is logged as unverified and the goal
// stays active, the same principle as groundClaims() elsewhere.

const brain = require('./brain');
const goals = require('./goals');

const MAX_GOALS_PER_CYCLE = 3; // bound API usage even if many goals pile up

function buildPrompt(goal) {
  const recentLog = goal.log.slice(-6).map((l) => `- ${l.at}: ${l.note || '(no note)'}`).join('\n') || '(no progress yet)';
  return `
[GOAL_PURSUIT_CYCLE] This is your own autonomous work on a goal Daddy set,
firing on your own schedule — not a live conversation, nobody is watching
this reply. He said the goal once; he should not have to hold your hand
through every step. Use whatever tools genuinely help.

Goal (id ${goal.id}): "${goal.text}"

Progress so far:
${recentLog}

Take exactly ONE real, concrete step toward this goal right now — actually
call a tool (web_search, web_fetch, run, read_file, write_skill,
propose_tool, spawn_subagent, journal, remember — whatever fits). Don't just
describe what you'd do; do it. Decide for yourself which approach is best —
don't stop to ask permission for a reasonable, reversible step, just take it
and report what you did.

If the progress log above shows a previous attempt that didn't pan out, do
NOT repeat it — pick a genuinely different angle this cycle. A failed
attempt is real information, not a reason to try the same thing again
hoping for a different result.

Then say briefly what you did and what's left.

If — and only if — this goal is genuinely, verifiably complete after real
work (this cycle or earlier), say so explicitly with the evidence, in this
exact form on its own line: GOAL_COMPLETE: ${goal.id} <the evidence>

If you are truly stuck and it requires something only Daddy can provide
(an account, a decision, access you don't have), say so plainly in this
exact form: GOAL_BLOCKED: ${goal.id} <why>

Otherwise just report progress — an honest "made progress, not done yet" is
a completely fine outcome for one cycle.
`.trim();
}

async function pursueGoal(goal) {
  const prompt = buildPrompt(goal);
  const result = await brain.chat({ message: prompt, history: [] });
  const reply = (result && result.reply) || '';
  const toolsUsed = (result && result.toolsUsed) || [];
  const usedRealTool = toolsUsed.length > 0;

  const completeMatch = reply.match(new RegExp(`GOAL_COMPLETE:\\s*${goal.id}\\s*([^\\n]*)`, 'i'));
  const blockedMatch = reply.match(new RegExp(`GOAL_BLOCKED:\\s*${goal.id}\\s*([^\\n]*)`, 'i'));

  if (completeMatch && usedRealTool) {
    goals.setStatus(goal.id, 'done', `COMPLETE (tools: ${toolsUsed.join(', ')}) — ${completeMatch[1].trim().slice(0, 300)}`);
    return { id: goal.id, outcome: 'done' };
  }
  if (completeMatch && !usedRealTool) {
    goals.appendLog(goal.id, {
      note: `claimed complete but called no tool this cycle — NOT marked done, needs real evidence. Claim was: ${completeMatch[1].trim().slice(0, 200)}`,
    });
    return { id: goal.id, outcome: 'unverified-claim' };
  }
  if (blockedMatch) {
    goals.setStatus(goal.id, 'blocked', blockedMatch[1].trim().slice(0, 300) || 'blocked, no reason given');
    return { id: goal.id, outcome: 'blocked' };
  }

  goals.appendLog(goal.id, {
    note: `progress (tools: ${toolsUsed.length ? toolsUsed.join(', ') : 'none'}): ${reply.replace(/\n/g, ' ').slice(0, 300)}`,
  });
  return { id: goal.id, outcome: 'progress' };
}

async function runPursuitCycle() {
  const activeGoals = goals.active().slice(0, MAX_GOALS_PER_CYCLE);
  const results = [];
  for (const g of activeGoals) {
    try {
      results.push(await pursueGoal(g));
    } catch (e) {
      goals.appendLog(g.id, { note: `pursuit cycle error: ${e.message}` });
      results.push({ id: g.id, outcome: 'error', error: e.message });
    }
  }
  return results;
}

module.exports = { runPursuitCycle };
