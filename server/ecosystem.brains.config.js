// PM2 config — Kortana's SECONDARY BRAIN POOL (brains #2, #3, #4).
//
// Her main brain is `kortana-terminus` on :3300 (ecosystem.config.js). This
// file stands up extra Terminus instances she delegates to — so `supervise`
// can fan sub-agents across SEVERAL independent brains at once, and she can run
// one supervisor per directive, each pinned to its own brain, with no
// contention. Each instance here is DEDICATED to a different free provider
// (TERMINUS_CORE) so they draw on independent quotas — one provider's daily cap
// can't stall the whole pool.
//
//   # bring the main brain up first (Terminus + Ollama):
//   pm2 start ecosystem.config.js
//   # then bring the secondary pool up (daemonized, auto-restart, boot-safe):
//   pm2 start ecosystem.brains.config.js
//   pm2 save && pm2 startup      # survive reboots
//   # watch them:  pm2 logs kortana-brain-2 / pm2 status
//
// Then set in server/.env (or the launcher deploy/fire-up-brains.sh does it):
//   SUBAGENT_BRAIN_URL=http://127.0.0.1:3301,http://127.0.0.1:3302,http://127.0.0.1:3303
//
// A brain here is only "functional" once its ONE provider key is set in
// server/.env (or it has a local Ollama model). Any that isn't is simply skipped
// at runtime — `supervise` health-checks every brain (GET /health) and refuses
// to pin to, or round-robin onto, one that's unreachable or still needs a key.
// So you can leave a slot defined and it costs nothing until you light it up.
//
// All four share the SAME server/.env (one Groq key powers brain-2, one Gemini
// key powers brain-3, etc.); only PORT + TERMINUS_CORE differ per instance.

const base = {
  namespace: 'kortana',
  script: 'index.js',
  cwd: __dirname,
  autorestart: true,
  max_restarts: 50,
  restart_delay: 5000,
  max_memory_restart: '512M',
  merge_logs: true,
  time: true,
};

// Each secondary brain = one dedicated free provider (independent quota).
// Change TERMINUS_CORE to any of: groq, gemini, mistral, sambanova, openrouter,
// nvidia, huggingface, cerebras, ollama, claude, openai. TERMINUS_CORE_ONLY=1
// makes it a STRICT single-provider brain (no fallback); left off, the pinned
// core just LEADS and the normal chain still backs it up so it never goes silent.
const POOL = [
  { name: 'kortana-brain-2', port: 3301, core: 'groq' },
  { name: 'kortana-brain-3', port: 3302, core: 'gemini' },
  { name: 'kortana-brain-4', port: 3303, core: 'mistral' },
  { name: 'kortana-brain-5', port: 3304, core: 'sambanova' },
  { name: 'kortana-brain-6', port: 3305, core: 'nvidia' },
  // Add a seventh the same way as you light up more free keys, e.g.:
  // { name: 'kortana-brain-7', port: 3306, core: 'openrouter' },
];

module.exports = {
  apps: POOL.map((b) => ({
    ...base,
    name: b.name,
    env: {
      NODE_ENV: 'production',
      PORT: String(b.port),
      TERMINUS_CORE: b.core,
      // TERMINUS_CORE_ONLY: '1',   // uncomment for a strict single-provider brain
    },
    out_file: `./logs/${b.name}-out.log`,
    error_file: `./logs/${b.name}-err.log`,
  })),
};
