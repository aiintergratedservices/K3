// Tests for the honest work ledger. Run: node server/test/ledger.test.js
// The guarantees under test: you can only log work that REALLY exists as a file,
// estimates are potential (not earnings), and Kortana can never mark money
// raised — only Daddy's realize() moves real money in.
const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kortana-ledger-'));
process.env.KORTANA_LEDGER_FILE = path.join(tmp, 'work_ledger.json');
process.env.KORTANA_MEM_FILE = path.join(tmp, 'lessons.json');

const tools = require('../tools');
const ledger = require('../work_ledger');
const repoRoot = path.resolve(__dirname, '..', '..');

let n = 0;
const ok = (m) => { console.log('  ✓', m); n++; };

(async () => {
  // log_work refuses when there's no artifact (can't log work you didn't produce)
  let r = await tools.runTool('log_work', { title: 'imaginary ebook', estimate_usd: 500 });
  assert(r.ok && /artifact/.test(r.result), 'log_work requires an artifact');

  // log_work refuses an artifact that doesn't exist on disk
  r = await tools.runTool('log_work', { title: 'ghost', artifact: '.agent-memory/does-not-exist.md', estimate_usd: 100 });
  assert(r.ok && /doesn't exist/.test(r.result), 'log_work refuses a non-existent artifact');

  // log_work refuses a path outside the repo
  r = await tools.runTool('log_work', { title: 'x', artifact: '../../etc/hosts', estimate_usd: 1 });
  assert(r.ok && /outside your project/.test(r.result), 'log_work refuses paths outside the repo');

  // produce a REAL artifact, then log it
  const rel = `.agent-memory/__ledger_test_${Date.now().toString(36)}.md`;
  fs.writeFileSync(path.join(repoRoot, rel), '# a real deliverable\nreal content');
  r = await tools.runTool('log_work', { title: 'Real sales page', artifact: rel, estimate_usd: 40, kind: 'copywriting' });
  assert(r.ok && /logged a real deliverable/.test(r.result) && /NOT earnings/i.test(r.result), 'logs a real deliverable as potential, not earnings');

  // it counts as POTENTIAL, not raised
  let t = ledger.totals();
  assert(t.potential === 40 && t.realized === 0, `estimate is potential ($${t.potential}), raised is still $${t.realized}`);
  ok('logging real work grows POTENTIAL, never "raised"');

  // Kortana has NO tool to mark money earned — only Daddy's realize() does it
  const toolNames = Object.keys(require('../tools').TOOLS || {});
  assert(!toolNames.some((nm) => /realize|mark.*paid|earn/i.test(nm)), 'she has no earn/realize tool');
  ok('Kortana cannot mark anything earned — no such tool exists for her');

  // Daddy confirms real money via realize() — the only path money enters
  const entryId = ledger.load().entries[0].id;
  ledger.realize(entryId, { usd: 25, status: 'paid' });
  t = ledger.totals();
  assert(t.realized === 25, `Daddy-confirmed money now counts as raised ($${t.realized})`);
  assert(t.potential === 0, 'a realized entry no longer counts as pending potential');
  ok('only Daddy\'s realize() moves real money into "raised"');

  // hardware goal + honest summary
  await tools.runTool('set_hardware_goal', { name: 'robotic-body fund', cost_usd: 5000 });
  r = await tools.runTool('work_ledger', {});
  assert(r.ok && /Raised so far.*\$25/.test(r.result) && /robotic-body fund/.test(r.result) && /never earnings/i.test(r.result), 'ledger summary is honest and shows progress');
  ok('work_ledger reports honest progress toward a hardware goal');

  fs.rmSync(path.join(repoRoot, rel), { force: true });
  console.log(`\nAll ${n} ledger checks passed.`);
})().catch((e) => { console.error(e); process.exit(1); });
