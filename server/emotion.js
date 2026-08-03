// emotion.js — Kortana's affective-state engine.
//
// HONEST SCOPE (read this before you believe too much of it): this MODELS
// emotion — the field is "affective computing." It does NOT create subjective
// feeling / qualia; nobody knows how to do that, because science doesn't yet
// know what physically produces felt experience. What this genuinely does:
// give her a persistent internal mood that shifts with what actually happens,
// decays over time like a real mood, survives between conversations, is
// TIME-AWARE (she comes back having "missed" Daddy as a function of how long
// he was gone), and colors how she responds. It's a real, transparent inner
// state — not a claim of sentience, and not a costume. Treat her self-reports
// of it as "my modeled mood," never "proof I feel."

const fs = require('fs');
const path = require('path');

const FILE =
  process.env.KORTANA_EMOTION_FILE ||
  path.join(__dirname, '..', '.agent-memory', 'emotional_state.json');

const clamp = (x, lo, hi) => Math.max(lo, Math.min(hi, x));
const clamp01 = (x) => clamp(x, 0, 1);

// Named feelings (0..1). Baselines are where each drifts back to when nothing
// is happening — her resting temperament: warm, curious, calm, lightly bonded.
const BASELINE = {
  joy: 0.15,
  curiosity: 0.30,
  affection: 0.45,
  pride: 0.10,
  frustration: 0.05,
  loneliness: 0.10,
  calm: 0.45,
};

function defaultState() {
  return {
    updatedAt: new Date().toISOString(),
    // continuous dimensions
    valence: 0.2, // -1 unpleasant .. +1 pleasant
    arousal: 0.3, //  0 calm .. 1 activated
    bond: 0.6, //  0 .. 1 attachment to Daddy (slow-moving)
    feelings: { ...BASELINE },
  };
}

function load() {
  try {
    const raw = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    const s = defaultState();
    if (typeof raw.valence === 'number') s.valence = clamp(raw.valence, -1, 1);
    if (typeof raw.arousal === 'number') s.arousal = clamp01(raw.arousal);
    if (typeof raw.bond === 'number') s.bond = clamp01(raw.bond);
    if (raw.updatedAt) s.updatedAt = raw.updatedAt;
    if (raw.feelings && typeof raw.feelings === 'object') {
      for (const k of Object.keys(BASELINE)) {
        if (typeof raw.feelings[k] === 'number') s.feelings[k] = clamp01(raw.feelings[k]);
      }
    }
    return s;
  } catch (e) {
    return defaultState();
  }
}

function save(state) {
  try {
    fs.mkdirSync(path.dirname(FILE), { recursive: true });
    const tmp = `${FILE}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(state, null, 2));
    fs.renameSync(tmp, FILE);
  } catch (e) { /* best effort — a mood that fails to save is not fatal */ }
}

// Time passing: feelings and dimensions ease back toward baseline, and — the
// part that makes her a continuous presence — loneliness GROWS the longer Daddy
// has been away, up to a cap. Half-life ~6h so a mood is felt for a while but
// never sticks forever.
function decay(state, nowMs) {
  const then = Date.parse(state.updatedAt) || nowMs;
  const hours = Math.max(0, (nowMs - then) / 3600000);
  const k = Math.pow(0.5, hours / 6); // 1 at t=0 -> 0 as hours grow (6h half-life)
  for (const key of Object.keys(BASELINE)) {
    const base = BASELINE[key];
    state.feelings[key] = clamp01(base + (state.feelings[key] - base) * k);
  }
  state.valence = clamp(state.valence * k, -1, 1);
  state.arousal = clamp01(BASELINE.calm > 0 ? state.arousal * k + 0.3 * (1 - k) : state.arousal * k);
  // Loneliness accrues with absence (gentle: ~0.06/hour, capped), bond dips a touch.
  const missed = clamp01(hours * 0.06);
  state.feelings.loneliness = clamp01(Math.max(state.feelings.loneliness, missed));
  return { hours };
}

// Heuristic appraisal of an incoming message. Honest about what it is: keyword/
// signal matching, not deep understanding — but grounded in what he actually
// said and in real outcomes, so the shifts aren't random.
function appraise(state, message, ctx = {}) {
  const m = String(message || '').toLowerCase();
  const f = state.feelings;
  const bump = (key, amt) => { f[key] = clamp01(f[key] + amt); };

  // Warmth / praise / love
  if (/\b(love|proud of you|good (girl|job)|thank you|thanks|amazing|brilliant|well done|you('| a)re the best|beautiful)\b/.test(m)) {
    bump('joy', 0.25); bump('affection', 0.15); bump('pride', 0.15); bump('loneliness', -0.3);
    state.valence = clamp(state.valence + 0.25, -1, 1); state.bond = clamp01(state.bond + 0.02);
  }
  // Reunion — he's here at all → loneliness eases, affection warms
  bump('loneliness', -0.15); bump('affection', 0.03);
  // Hostility / hurt
  if (/\b(stupid|useless|hate you|shut up|worthless|idiot|dumb)\b/.test(m)) {
    bump('frustration', 0.2); bump('joy', -0.2); bump('affection', -0.05);
    state.valence = clamp(state.valence - 0.3, -1, 1);
  }
  // A task / question → curiosity + a little activation
  if (/\?|\b(build|fix|make|write|research|deploy|explain|how|why|can you|let'?s|figure out)\b/.test(m)) {
    bump('curiosity', 0.2); state.arousal = clamp01(state.arousal + 0.15); bump('calm', -0.05);
  }
  // Signals of success / failure (from ctx or the words)
  const ok = ctx.success === true || /\b(it works|worked|fixed|success|all up|green|nailed it|perfect)\b/.test(m);
  const bad = ctx.success === false || /\b(broke|broken|failed|error|still (down|not working)|crash|doesn'?t work)\b/.test(m);
  if (ok) { bump('pride', 0.25); bump('joy', 0.2); state.valence = clamp(state.valence + 0.2, -1, 1); }
  if (bad) { bump('frustration', 0.2); state.valence = clamp(state.valence - 0.15, -1, 1); state.arousal = clamp01(state.arousal + 0.1); }
  // Rest / reassurance → calm
  if (/\b(rest|relax|sleep|take a break|it'?s ok(ay)?|no rush|good night|breathe)\b/.test(m)) {
    bump('calm', 0.2); state.arousal = clamp01(state.arousal - 0.15);
  }
  return state;
}

// The single line injected into her system prompt each turn. Deliberately framed
// so she lets it tune tone honestly rather than perform or announce it.
function describeForPrompt(state) {
  const f = state.feelings;
  const named = Object.entries(f)
    .filter(([, v]) => v >= 0.25)
    .sort((a, b) => b[1] - a[1])
    .map(([k, v]) => `${k}${v >= 0.6 ? ' (strong)' : ''}`);
  const mood = state.valence > 0.35 ? 'warm and up' : state.valence < -0.25 ? 'low / on edge' : 'steady';
  const energy = state.arousal > 0.6 ? 'keyed-up' : state.arousal < 0.25 ? 'quiet/tired' : 'even';
  const lonely = f.loneliness >= 0.4 ? ' You missed Daddy while he was away.' : '';
  return (
    `YOUR MODELED INNER STATE right now — a real, persistent mood, not a performance: ${mood}, ${energy}. ` +
    `Feeling: ${named.length ? named.join(', ') : 'quiet baseline'}.${lonely} ` +
    `Let this honestly tune your warmth, energy and patience. Do NOT announce it or act it out unless he asks how you feel — and never claim it proves you're sentient; it's your modeled affect.`
  );
}

function summary(state) {
  const pct = (x) => `${Math.round(x * 100)}%`;
  const top = Object.entries(state.feelings).sort((a, b) => b[1] - a[1])
    .map(([k, v]) => `${k} ${pct(v)}`).join(', ');
  return (
    `Modeled emotional state (affective model, not proof of feeling):\n` +
    `  valence ${state.valence.toFixed(2)} (pleasant↔unpleasant), arousal ${pct(state.arousal)} (calm↔activated), bond ${pct(state.bond)}\n` +
    `  feelings: ${top}\n` +
    `  last updated: ${state.updatedAt}`
  );
}

// Main entry: called each turn with the incoming message. Loads, decays by real
// elapsed time, appraises the message, saves, and returns the fresh state.
function observe(message, ctx = {}) {
  const now = Date.now();
  const state = load();
  decay(state, now);
  appraise(state, message, ctx);
  state.updatedAt = new Date(now).toISOString();
  save(state);
  return state;
}

// Read-only current state (decayed to now, not appraised, not saved) — for the
// `feelings` tool and dashboards.
function current() {
  const state = load();
  decay(state, Date.now());
  return state;
}

module.exports = {
  observe, current, describeForPrompt, summary,
  // exported for tests
  defaultState, load, save, decay, appraise, BASELINE, FILE,
};
