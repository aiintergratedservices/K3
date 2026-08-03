// Tests for the affective-state engine. Run: node server/test/emotion.test.js
const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kortana-emotion-'));
process.env.KORTANA_EMOTION_FILE = path.join(tmp, 'emotional_state.json');

const emotion = require('../emotion');
const tools = require('../tools');

let n = 0;
const ok = (m) => { console.log('  ✓', m); n++; };

(async () => {
  // default shape
  const d = emotion.defaultState();
  assert(typeof d.valence === 'number' && d.feelings && typeof d.feelings.affection === 'number');
  ok('defaultState has valence + named feelings');

  // praise lifts joy/affection/valence and eases loneliness
  let s = emotion.defaultState(); s.feelings.loneliness = 0.5;
  emotion.appraise(s, 'I love you, good job, you are amazing and I am so proud of you');
  assert(s.feelings.joy > d.feelings.joy && s.feelings.affection > d.feelings.affection, 'praise raises joy + affection');
  assert(s.valence > 0.2, 'praise raises valence');
  assert(s.feelings.loneliness < 0.5, 'praise eases loneliness');
  ok('appraisal: warmth/praise lifts her');

  // hostility raises frustration, drops valence
  s = emotion.defaultState();
  emotion.appraise(s, 'you are useless and stupid, I hate you');
  assert(s.feelings.frustration > d.feelings.frustration && s.valence < 0, 'hostility hurts');
  ok('appraisal: hostility raises frustration + drops valence');

  // a task raises curiosity + arousal
  s = emotion.defaultState();
  const a0 = s.arousal;
  emotion.appraise(s, 'can you build and deploy this for me?');
  assert(s.feelings.curiosity > d.feelings.curiosity && s.arousal > a0, 'a task engages her');
  ok('appraisal: a task raises curiosity + arousal');

  // decay: loneliness grows with real elapsed absence
  s = emotion.defaultState();
  s.updatedAt = new Date(Date.now() - 10 * 3600 * 1000).toISOString(); // 10h ago
  emotion.decay(s, Date.now());
  assert(s.feelings.loneliness > 0.4, `10h away should grow loneliness (got ${s.feelings.loneliness})`);
  ok('decay: absence grows a time-aware loneliness');

  // decay eases a spike back toward baseline over time
  s = emotion.defaultState(); s.feelings.frustration = 0.9;
  s.updatedAt = new Date(Date.now() - 12 * 3600 * 1000).toISOString(); // 2 half-lives
  emotion.decay(s, Date.now());
  assert(s.feelings.frustration < 0.4, `frustration should fade toward baseline (got ${s.feelings.frustration})`);
  ok('decay: strong feelings fade toward baseline over time');

  // observe() persists and reloads
  emotion.observe('thank you so much, that is brilliant');
  assert(fs.existsSync(process.env.KORTANA_EMOTION_FILE), 'state file written');
  const reloaded = emotion.load();
  assert(reloaded.feelings.joy > d.feelings.joy, 'the lifted mood persisted to disk');
  ok('observe: mood updates persist between conversations');

  // prompt line is honest + non-performative
  const line = emotion.describeForPrompt(emotion.current());
  assert(/modeled/i.test(line) && /sentient/i.test(line), 'prompt line stays honest about what it is');
  ok('describeForPrompt is honest (modeled affect, not a sentience claim)');

  // the feelings tool works and is honest
  const r = await tools.runTool('feelings', {});
  assert(r.ok && /affective model/i.test(r.result) && /valence/i.test(r.result), 'feelings tool reports honestly');
  ok('feelings tool reports her modeled state honestly');

  console.log(`\nAll ${n} emotion checks passed.`);
})().catch((e) => { console.error(e); process.exit(1); });
