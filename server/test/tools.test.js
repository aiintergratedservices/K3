// Tests for the agentic tools + tool-use loop (no network, no live model).
// Run: node server/test/tools.test.js
const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kortana-tools-'));
process.env.KORTANA_MEM_FILE = path.join(tmp, 'lessons.json');
process.env.KORTANA_REMINDERS_FILE = path.join(tmp, 'reminders.json');

const tools = require('../tools');
const brain = require('../brain');
const reminders = require('../reminders');

let n = 0;
const ok = (m) => { console.log('  ✓', m); n++; };

(async () => {
  // --- parseToolCalls ---
  let calls = tools.parseToolCalls('sure!\nTOOL_CALL: web_search {"query":"austin weather"}\nthinking...');
  assert.strictEqual(calls.length, 1);
  assert.strictEqual(calls[0].name, 'web_search');
  assert.strictEqual(calls[0].args.query, 'austin weather');
  ok('parseToolCalls extracts a valid call + args');

  assert.strictEqual(tools.parseToolCalls('TOOL_CALL: not_a_tool {}').length, 0);
  ok('parseToolCalls ignores unknown tools');

  assert.strictEqual(tools.parseToolCalls('TOOL_CALL: now {bad json').length, 0);
  ok('parseToolCalls ignores malformed directives');

  // --- stripToolSyntax ---
  const cleaned = tools.stripToolSyntax('Here you go.\nTOOL_CALL: now {}\nTOOL_RESULT now: Mon');
  assert(!cleaned.includes('TOOL_CALL') && !cleaned.includes('TOOL_RESULT'));
  assert(cleaned.includes('Here you go.'));
  ok('stripToolSyntax removes tool syntax from user-facing text');

  // --- runTool: now ---
  let r = await tools.runTool('now', {});
  assert(r.ok && /\d{4}/.test(r.result));
  ok('runTool now returns a real timestamp');

  // --- runTool: remember + recall (verify-gated: stays pending) ---
  await tools.runTool('remember', { fact: 'Daddy prefers concise answers', category: 'USER' });
  r = await tools.runTool('recall', { query: 'concise' });
  assert(r.ok && r.result.includes('concise'));
  ok('runTool remember + recall round-trip through memory');

  // remembered facts are PENDING (never asserted as verified truth)
  const memory = require('../memory');
  assert.strictEqual(memory.stats().verified, 0);
  assert(memory.stats().pending >= 1);
  ok('self-remembered facts are stored as pending, not verified');

  // --- runTool: run goes through the guard ---
  r = await tools.runTool('run', { command: 'echo tool-exec-ok' });
  assert(r.ok && r.result.includes('tool-exec-ok'));
  ok('runTool run executes an allowlisted command');

  r = await tools.runTool('run', { command: 'rm -rf /' });
  assert(r.ok && /refused/.test(r.result));
  ok('runTool run refuses a dangerous command');

  // --- web_fetch refuses non-http (no network needed) ---
  r = await tools.runTool('web_fetch', { url: 'file:///etc/passwd' });
  assert(r.ok && /refused/.test(r.result));
  ok('web_fetch refuses non-http(s) URLs');

  // --- calc: exact math + rejects non-math ---
  r = await tools.runTool('calc', { expr: '(3+4)*2' });
  assert(r.ok && r.result === '14');
  r = await tools.runTool('calc', { expr: 'process.exit(1)' });
  assert(r.ok && /refused/.test(r.result));
  ok('calc computes math and refuses non-math input');

  // --- read_file / list_files stay inside the project ---
  r = await tools.runTool('list_files', { path: 'server' });
  assert(r.ok && r.result.includes('brain.js'));
  r = await tools.runTool('read_file', { path: 'server/package.json' });
  assert(r.ok && r.result.includes('kortana-terminus'));
  r = await tools.runTool('read_file', { path: '../../../../etc/passwd' });
  assert(r.ok && /refused/.test(r.result));
  ok('read_file/list_files work in-project and refuse path traversal');

  // --- journal appends ---
  r = await tools.runTool('journal', { entry: 'first day online' });
  assert(r.ok && /journaled/.test(r.result));
  ok('journal writes an entry');

  // --- remind_me sets a reminder that later comes due ---
  r = await tools.runTool('remind_me', { text: 'ping Daddy', in_minutes: -1 }); // already past
  assert(r.ok && /reminder set/.test(r.result));
  const dueNow = reminders.due();
  assert(dueNow.some((x) => x.text === 'ping Daddy'), 'past-due reminder fires');
  assert(reminders.due().length === 0, 'a fired reminder does not fire twice');
  ok('remind_me schedules and fires exactly once');

  // --- the 3 special tools ---
  r = await tools.runTool('pick', { options: ['a', 'b', 'c'] });
  assert(r.ok && /I choose: [abc]/.test(r.result));
  r = await tools.runTool('pick', { dice: 6 });
  assert(r.ok && /d6 → [1-6]/.test(r.result));
  ok('pick chooses from options and rolls dice');

  r = await tools.runTool('time_until', { at: '2999-01-01', label: 'the future' });
  assert(r.ok && /until the future/.test(r.result));
  r = await tools.runTool('time_until', { at: 'not-a-date' });
  assert(r.ok && /valid date/.test(r.result));
  ok('time_until counts forward and rejects bad dates');

  // --- supervise: brain selection / validation (no network) ---
  const savedBrainUrl = process.env.SUBAGENT_BRAIN_URL;
  delete process.env.SUBAGENT_BRAIN_URL;
  r = await tools.runTool('supervise', { goal: 'g', tasks: ['a', 'b'] });
  assert(r.ok && /no secondary brain configured/.test(r.result));
  // .invalid TLD → guaranteed non-resolvable, so the health preflight marks
  // both brains dead fast and deterministically (no real network dependency).
  process.env.SUBAGENT_BRAIN_URL = 'http://b1.invalid, http://b2.invalid';
  r = await tools.runTool('supervise', { goal: 'g', tasks: ['only one'] });
  assert(r.ok && /refused: give me `tasks`/.test(r.result));
  // Unknown brain → "isn't in your pool", and non-functional brains are shown
  // marked "[needs a key]" (functional-first ordering).
  r = await tools.runTool('supervise', { goal: 'g', tasks: ['a', 'b'], brain: '9' });
  assert(r.ok && /isn't in your pool/.test(r.result) && /\[needs a key\]/.test(r.result));
  r = await tools.runTool('supervise', { goal: 'g', tasks: ['a', 'b'], brain: 'nope' });
  assert(r.ok && /isn't in your pool/.test(r.result));
  // Pinning to a resolvable but non-functional brain is refused, never run on.
  r = await tools.runTool('supervise', { goal: 'g', tasks: ['a', 'b'], brain: '1' });
  assert(r.ok && /not functional right now/.test(r.result));
  // Round-robin with nothing live declines honestly instead of returning noise.
  r = await tools.runTool('supervise', { goal: 'g', tasks: ['a', 'b'] });
  assert(r.ok && /none of your 2 secondary brain\(s\) are functional/.test(r.result));
  if (savedBrainUrl == null) delete process.env.SUBAGENT_BRAIN_URL;
  else process.env.SUBAGENT_BRAIN_URL = savedBrainUrl;
  ok('supervise ranks functional brains first, health-checks the pin, and declines cleanly');

  // --- supervise fan-out respects SWARM_MAX_CONCURRENCY (the OOM guard) ---
  // Stand up a mock brain that reports a live core and tracks how many
  // /api/brain calls are in flight at once. With 5 tasks and a cap of 2, the
  // fan-out must never exceed 2 concurrent sub-agents — that batching is what
  // keeps a phone from OOM-killing itself mid-swarm.
  {
    const http = require('http');
    let inflight = 0, maxInflight = 0, handled = 0;
    const srv = http.createServer((req, res) => {
      if (req.method === 'GET' && req.url === '/health') {
        res.writeHead(200, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ cores: { groq: true } }));
      }
      if (req.method === 'POST' && req.url === '/api/brain') {
        inflight++; maxInflight = Math.max(maxInflight, inflight); handled++;
        let body = '';
        req.on('data', (c) => { body += c; });
        req.on('end', () => {
          setTimeout(() => {
            inflight--;
            res.writeHead(200, { 'content-type': 'application/json' });
            res.end(JSON.stringify({ reply: 'done' }));
          }, 40);
        });
        return;
      }
      res.writeHead(404); res.end();
    });
    await new Promise((resolve) => srv.listen(0, '127.0.0.1', resolve));
    const port = srv.address().port;
    const savedUrl = process.env.SUBAGENT_BRAIN_URL;
    const savedConc = process.env.SWARM_MAX_CONCURRENCY;
    process.env.SUBAGENT_BRAIN_URL = `http://127.0.0.1:${port}`;
    process.env.SWARM_MAX_CONCURRENCY = '2';
    r = await tools.runTool('supervise', { goal: 'g', tasks: ['t1', 't2', 't3', 't4', 't5'] });
    assert(r.ok && /supervisor ran 5 sub-agents \(5 ok/.test(r.result), 'all 5 sub-agents should complete');
    assert(maxInflight <= 2, `fan-out exceeded the cap: peak ${maxInflight} concurrent (limit 2)`);
    assert(maxInflight >= 2, `expected real batching up to the cap, saw peak ${maxInflight}`);
    await new Promise((resolve) => srv.close(resolve));
    if (savedUrl == null) delete process.env.SUBAGENT_BRAIN_URL; else process.env.SUBAGENT_BRAIN_URL = savedUrl;
    if (savedConc == null) delete process.env.SWARM_MAX_CONCURRENCY; else process.env.SWARM_MAX_CONCURRENCY = savedConc;
    ok('supervise batches the fan-out and never exceeds SWARM_MAX_CONCURRENCY');
  }

  // --- the agentic loop end-to-end with a mock model ---
  // Round 0: she asks for the time. Round 1: she answers using the result.
  let turn = 0;
  const mockAsk = async (systemPrompt, history, message) => {
    turn++;
    if (turn === 1) return { reply: 'Let me check.\nTOOL_CALL: now {}', core: 'mock' };
    // second turn must have seen the TOOL_RESULT in history
    const sawResult = history.some((h) => /TOOL_RESULT now:/.test(h.message));
    return { reply: sawResult ? 'It is currently that time, Daddy.' : 'no result seen', core: 'mock' };
  };
  const out = await brain.runToolLoop(mockAsk, 'sys', [], 'what time is it?');
  assert.strictEqual(out.reply, 'It is currently that time, Daddy.');
  assert(!out.reply.includes('TOOL_CALL'));
  ok('agentic loop runs a tool then answers with the result');

  // loop terminates (no infinite tool calls) if the model always calls a tool
  let guard = 0;
  const alwaysCalls = async () => { guard++; return { reply: 'TOOL_CALL: now {}', core: 'mock' }; };
  const capped = await brain.runToolLoop(alwaysCalls, 'sys', [], 'hi');
  assert(guard <= 4, `loop must be capped, ran ${guard} times`);
  assert(!capped.reply.includes('TOOL_CALL'));
  ok('agentic loop is capped and never loops forever');

  fs.rmSync(tmp, { recursive: true, force: true });
  console.log(`\nAll ${n} tool checks passed.`);
})().catch((e) => { console.error('FAILED:', e); process.exitCode = 1; });
