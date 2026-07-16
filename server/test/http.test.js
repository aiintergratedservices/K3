// Integration test: exercise the real loop endpoints over HTTP against the
// real memory + executor modules (mirrors the routes wired in index.js).
// Run: node server/test/http.test.js
const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const http = require('http');
const express = require('express');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kortana-http-'));
process.env.KORTANA_MEM_FILE = path.join(tmp, 'lessons.json');
const memory = require('../memory');
const executor = require('../executor');

const app = express();
app.use(express.json());
const REPO = path.join(__dirname, '..', '..');

app.post('/api/kortana/run', async (req, res) => {
  const command = String((req.body && req.body.command) || '');
  const verdict = executor.classify(command);
  if (!verdict.allowed) return res.status(403).json({ error: 'blocked', reason: verdict.reason });
  res.json(await executor.run(command, { cwd: REPO }));
});
app.post('/api/kortana/learn', async (req, res) => {
  const { lesson, verify } = req.body || {};
  if (!lesson || !verify) return res.status(400).json({ error: 'required' });
  const verdict = executor.classify(verify);
  if (!verdict.allowed) return res.status(403).json({ error: 'verify blocked', reason: verdict.reason });
  const result = await executor.run(verify, { cwd: REPO });
  const passed = result.code === 0 && !result.timedOut;
  const recorded = memory.record({ text: lesson, verified: passed, evidence: 'test' });
  res.json({ passed, lesson: recorded, stats: memory.stats() });
});
app.get('/api/kortana/memory', (req, res) => res.json({ stats: memory.stats(), lessons: memory.all() }));

const req = (method, p, body) => new Promise((resolve) => {
  const data = body ? JSON.stringify(body) : null;
  const r = http.request({ host: '127.0.0.1', port, path: p, method, headers: { 'content-type': 'application/json' } }, (resp) => {
    let b = ''; resp.on('data', (c) => (b += c)); resp.on('end', () => resolve({ status: resp.statusCode, json: JSON.parse(b || '{}') }));
  });
  if (data) r.write(data); r.end();
});

let port;
const server = app.listen(0, '127.0.0.1', async () => {
  port = server.address().port;
  let n = 0; const ok = (m) => { console.log('  ✓', m); n++; };
  try {
    // learn with a PASSING verify -> verified lesson
    let r = await req('POST', '/api/kortana/learn', { lesson: 'brain.js has valid syntax', verify: 'node --check server/brain.js' });
    assert.strictEqual(r.status, 200); assert.strictEqual(r.json.passed, true);
    assert.strictEqual(r.json.lesson.status, 'verified');
    ok('learn: passing verify records a VERIFIED lesson');

    // learn with a FAILING verify -> pending, not verified
    r = await req('POST', '/api/kortana/learn', { lesson: 'this file exists', verify: 'cat does-not-exist-xyz.js' });
    assert.strictEqual(r.json.passed, false); assert.strictEqual(r.json.lesson.status, 'pending');
    ok('learn: failing verify records only a PENDING guess');

    // dangerous verify command is refused at the endpoint
    r = await req('POST', '/api/kortana/learn', { lesson: 'x', verify: 'rm -rf /' });
    assert.strictEqual(r.status, 403);
    ok('learn: dangerous verify command is refused (403)');

    // run endpoint blocks non-allowlisted command
    r = await req('POST', '/api/kortana/run', { command: 'git push --force' });
    assert.strictEqual(r.status, 403);
    ok('run: force-push is blocked (403)');

    // run endpoint executes an allowed command
    r = await req('POST', '/api/kortana/run', { command: 'echo hello-terminus' });
    assert.strictEqual(r.json.code, 0); assert(r.json.stdout.includes('hello-terminus'));
    ok('run: allowed command executes and returns output');

    // memory endpoint reflects state
    r = await req('GET', '/api/kortana/memory');
    assert.strictEqual(r.json.stats.verified, 1);
    ok('memory: endpoint reports verified/pending counts');

    console.log(`\nAll ${n} HTTP checks passed.`);
  } catch (e) {
    console.error('FAILED:', e.message); process.exitCode = 1;
  } finally {
    server.close(); fs.rmSync(tmp, { recursive: true, force: true });
  }
});
