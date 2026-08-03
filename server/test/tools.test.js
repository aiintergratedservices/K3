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

  // --- browse: reads text, lists links, and can follow one ---
  r = await tools.runTool('browse', { url: 'file:///etc/passwd' });
  assert(r.ok && /refused|only http/i.test(r.result), 'browse refuses non-http(s)');
  {
    const http = require('http');
    const srv = http.createServer((req, res) => {
      res.writeHead(200, { 'content-type': 'text/html' });
      if (req.url === '/') {
        res.end('<html><head><title>Home Page</title></head><body><h1>Welcome</h1><p>hello world body text</p><a href="/about">About Us</a> <a href="https://ext.example/x">External</a></body></html>');
      } else if (req.url === '/about') {
        res.end('<html><head><title>About</title></head><body>the about page content here</body></html>');
      } else { res.writeHead(404); res.end(); }
    });
    await new Promise((rr) => srv.listen(0, '127.0.0.1', rr));
    const base = `http://127.0.0.1:${srv.address().port}`;
    r = await tools.runTool('browse', { url: base + '/' });
    assert(r.ok && /hello world body text/.test(r.result), 'browse returns page text');
    assert(/Title: Home Page/.test(r.result), 'browse returns the title');
    assert(/About Us ->/.test(r.result) && /\[1\]/.test(r.result), 'browse lists followable links');
    // follow a link by its number
    r = await tools.runTool('browse', { url: base + '/', link: '1' });
    assert(r.ok && /Followed ->/.test(r.result) && /the about page content here/.test(r.result), 'browse follows a link by number');
    await new Promise((rr) => srv.close(rr));
    ok('browse reads text, lists links, and follows one');
  }

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
    assert(r.ok && /supervisor ran 5 sub-agents in parallel \(5 ok/.test(r.result), 'all 5 sub-agents should complete');
    assert(maxInflight <= 2, `fan-out exceeded the cap: peak ${maxInflight} concurrent (limit 2)`);
    assert(maxInflight >= 2, `expected real batching up to the cap, saw peak ${maxInflight}`);
    await new Promise((resolve) => srv.close(resolve));
    if (savedUrl == null) delete process.env.SUBAGENT_BRAIN_URL; else process.env.SUBAGENT_BRAIN_URL = savedUrl;
    if (savedConc == null) delete process.env.SWARM_MAX_CONCURRENCY; else process.env.SWARM_MAX_CONCURRENCY = savedConc;
    ok('supervise batches the fan-out and never exceeds SWARM_MAX_CONCURRENCY');
  }

  // --- supervise: specialist ROLES + stateful PIPELINE (shared state) ---
  {
    const http = require('http');
    const received = [];
    let calls = 0;
    const srv = http.createServer((req, res) => {
      if (req.method === 'GET' && req.url === '/health') {
        res.writeHead(200, { 'content-type': 'application/json' });
        return res.end(JSON.stringify({ cores: { groq: true } }));
      }
      if (req.method === 'POST' && req.url === '/api/brain') {
        let body = '';
        req.on('data', (c) => { body += c; });
        req.on('end', () => {
          received.push((JSON.parse(body || '{}').message) || '');
          const id = ++calls;
          res.writeHead(200, { 'content-type': 'application/json' });
          res.end(JSON.stringify({ reply: `RESULT_${id}` }));
        });
        return;
      }
      res.writeHead(404); res.end();
    });
    await new Promise((rr) => srv.listen(0, '127.0.0.1', rr));
    const port = srv.address().port;
    const savedUrl = process.env.SUBAGENT_BRAIN_URL;
    process.env.SUBAGENT_BRAIN_URL = `http://127.0.0.1:${port}`;

    // Roles: each sub-agent gets its specialist framing, and the report shows it.
    received.length = 0; calls = 0;
    let rr = await tools.runTool('supervise', { goal: 'g', tasks: [
      { task: 'lay out the steps', role: 'planner' },
      { task: 'poke holes in the plan', role: 'critic' },
    ] });
    assert(rr.ok && /\[planner\]/.test(rr.result) && /\[critic\]/.test(rr.result), 'roles shown in the report');
    assert(received.some((m) => /you are a PLANNER/i.test(m)), 'planner framing reached a sub-agent');
    assert(received.some((m) => /you are a CRITIC/i.test(m)), 'critic framing reached a sub-agent');

    // Pipeline: sequential, and each sub-agent sees the earlier ones' results.
    received.length = 0; calls = 0;
    rr = await tools.runTool('supervise', { goal: 'g', mode: 'pipeline', tasks: ['first', 'second', 'third'] });
    assert(rr.ok && /in a stateful pipeline/.test(rr.result), 'pipeline mode is labeled');
    assert(received.length >= 3, 'all three sub-agents ran');
    assert(/RESULT_1/.test(received[1]), 'sub-agent 2 saw sub-agent 1 output (shared state)');
    assert(/RESULT_1/.test(received[2]) && /RESULT_2/.test(received[2]), 'sub-agent 3 saw both prior outputs');

    await new Promise((rr2) => srv.close(rr2));
    if (savedUrl == null) delete process.env.SUBAGENT_BRAIN_URL; else process.env.SUBAGENT_BRAIN_URL = savedUrl;
    ok('supervise supports specialist roles and a stateful pipeline (shared state)');
  }

  // --- apply_change: autonomous self-modification, guardrails + mandatory audit ---
  {
    const repoRoot = path.resolve(__dirname, '..', '..');
    const smExisted = fs.existsSync(path.join(repoRoot, 'self-modifications'));
    const rel = `.agent-memory/__apply_test_${Date.now().toString(36)}.txt`;
    // must be documented
    let a = await tools.runTool('apply_change', { file: rel, content: 'hello' });
    assert(a.ok && /MUST be documented/.test(a.result), 'apply_change requires summary + reason');
    // protected path refused
    a = await tools.runTool('apply_change', { file: '.env', content: 'x=1', summary: 's', reason: 'r' });
    assert(a.ok && /protected/.test(a.result), 'apply_change refuses .env (secrets)');
    // outside repo refused
    a = await tools.runTool('apply_change', { file: '../../etc/hosts', content: 'x', summary: 's', reason: 'r' });
    assert(a.ok && /outside your project/.test(a.result), 'apply_change refuses paths outside the repo');
    // broken .js refused (won't take her brain down)
    const badJs = `.agent-memory/__apply_bad_${Date.now().toString(36)}.js`;
    a = await tools.runTool('apply_change', { file: badJs, content: 'function ( {', summary: 's', reason: 'r' });
    assert(a.ok && /SYNTAX ERROR/.test(a.result), 'apply_change refuses a .js that will not parse');
    assert(!fs.existsSync(path.join(repoRoot, badJs)), 'refused broken .js is never written');
    // real apply + audit
    a = await tools.runTool('apply_change', { file: rel, content: 'line one\nline two\n', summary: 'unit test apply', reason: 'verify the autonomous path' });
    assert(a.ok && /APPLIED live change/.test(a.result), 'apply_change applies a valid change');
    assert(fs.readFileSync(path.join(repoRoot, rel), 'utf8') === 'line one\nline two\n', 'the file was actually written');
    const log = fs.readFileSync(path.join(repoRoot, 'self-modifications', 'LOG.md'), 'utf8');
    assert(/unit test apply/.test(log), 'the change is recorded in the audit LOG.md');
    // cleanup test artifacts (self-modifications is gitignored anyway)
    fs.rmSync(path.join(repoRoot, rel), { force: true });
    if (!smExisted) fs.rmSync(path.join(repoRoot, 'self-modifications'), { recursive: true, force: true });
    else { // just drop our test line back out
      fs.writeFileSync(path.join(repoRoot, 'self-modifications', 'LOG.md'),
        log.split('\n').filter((l) => !/unit test apply/.test(l)).join('\n'));
    }
    ok('apply_change gives autonomy but enforces confinement, syntax, and a mandatory audit trail');
  }

  // --- swarm-intent detection (forces supervise instead of confabulating) ---
  {
    const re = brain.SWARM_INTENT_RE;
    for (const yes of [
      'Deploy your swarm and research three hosts',
      'use your sub-agents to compare these',
      'fire the swarm on this',
      'spin up your brain pool',
      'unleash your subagents',
      'run supervise across your other brains',
    ]) assert(re.test(yes), `should detect swarm intent: "${yes}"`);
    for (const no of [
      'how are you today?',
      'what time is it',
      'tell me about bees',
      'I deployed the app to the server', // "deploy" but not the swarm
    ]) assert(!re.test(no), `should NOT trip on: "${no}"`);
    ok('swarm-intent detector fires on explicit swarm requests only');
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
