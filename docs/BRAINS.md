# Kortana's Brain Pool

She used to have one main brain and, at most, one secondary. This gives her a
**pool of 6 brains**: her main brain plus **5 dedicated sub-agent brains** she
delegates to — so `supervise` can fan sub-agents across **several independent
brains at once**, and she can run **one supervisor per directive, each pinned to
its own brain**, without them fighting over one core.

| # | Port | Dedicated core | Role |
|---|------|----------------|------|
| 1 | 3300 | full fallback chain | **Main brain** — talks to Daddy, stays free (never runs sub-agents) |
| 2 | 3301 | Groq | sub-agent brain |
| 3 | 3302 | Gemini | sub-agent brain |
| 4 | 3303 | Mistral | sub-agent brain |
| 5 | 3304 | SambaNova | sub-agent brain |
| 6 | 3305 | NVIDIA NIM | sub-agent brain |

All five sub-agent providers are **free, no card**, each an **independent
quota**. Only the brains whose key is set actually run and get used — the rest
sink to the bottom, unused until keyed (see "Functional-first" below).

## Why more than one secondary helps

- **Parallelism.** `supervise` round-robins its sub-agents across every live
  brain in the pool, so four sub-tasks can genuinely run on four brains at once
  instead of queueing on one.
- **Independent quotas.** Each pool brain is **dedicated to a different free
  provider** (`TERMINUS_CORE`) — brain-2 = Groq, brain-3 = Gemini, brain-4 =
  Mistral. One provider hitting its daily cap can't stall the whole pool.
- **One supervisor per directive.** When Daddy fires several directives at once,
  she calls `supervise` once per directive and passes `brain` to pin each
  supervisor to its own brain — so the directives don't contend. See the
  `teacher` skill for exactly when and how she does this.

## This runs on the PHONE

Each pool brain is dedicated to a **cloud** provider, so it's a **light Node
process (~tens of MB), not a model held in RAM.** Three or four run comfortably
in Termux next to the main brain — only the *main* brain optionally carries
Ollama. You do **not** need a VPS for the pool.

### Phone / Termux (the real target) — PM2

```bash
cd ~/k3/server
npm install                       # once
# set at least one free key per brain in .env (Groq / Gemini / Mistral — see .env.example)
pm2 start ecosystem.config.js         # main brain (:3300) + Ollama
bash deploy/fire-up-brains.sh         # the pool (:3301 groq … :3305 nvidia)
pm2 save && pm2 startup               # survive reboots
```

> On THIS phone PM2's socket layer is unreliable on Node 24, so the real
> production path is the **self-healing watchdog** — one command brings up
> everything (main brain + Ollama + the whole pool) and keeps it alive, and it
> auto-starts when the phone powers on:
>
> ```bash
> bash server/deploy/fire-up-everything.sh
> ```

`fire-up-brains.sh` brings the pool up, **health-checks each brain**, and prints
the exact `SUBAGENT_BRAIN_URL=` line to paste into `.env` — listing **only the
brains that came up functional**. Then:

```bash
# paste the printed SUBAGENT_BRAIN_URL into ~/k3/server/.env, then:
pm2 restart kortana-terminus
```

### VPS only (Oracle free tier, etc.) — Docker or systemd

Not for the phone. On a real box you can instead use:

```bash
docker compose --profile pool up -d          # brain2/brain3/brain4 (see docker-compose.yml)
# or systemd:
sudo cp server/deploy/kortana-brain@.service /etc/systemd/system/
sudo systemctl enable --now kortana-brain@3301 kortana-brain@3302 kortana-brain@3303
```

## "Functional, not still needing an API key" — enforced

Daddy's rule: she only ever uses a brain that's **actually live**, never one
that's up but still waiting on a key. This is enforced in code, not just
documented:

- `supervise` runs a **health preflight** before delegating. It GETs each
  brain's `/health` and treats a brain as functional only if it's **reachable
  AND has at least one live core** (Ollama reachable, or a provider key set).
- **Pinning** to a brain that isn't functional is **refused** with a message
  telling her which brains *are* live, so she pins to a working one.
- **Round-robin** silently **drops** any non-functional brain from the fan-out;
  if none are live, it declines honestly instead of returning rules-core noise.

So you can define brain-4's slot before you have its key — it just sits unused,
costing nothing, until you light it up. `deploy/fire-up-brains.sh` uses the same
check, so what it prints is exactly what she'll actually use.

## Dedicating a brain to a provider — `TERMINUS_CORE`

A single Terminus instance normally tries a whole fallback chain. Set
`TERMINUS_CORE` on a **pool instance** (per-process, in
`ecosystem.brains.config.js` / the compose pool / a systemd drop-in — *not* in
the shared `.env`) to make that instance **lead with one provider**:

```
TERMINUS_CORE=groq        # this instance thinks with Groq first
TERMINUS_CORE_ONLY=1      # optional: STRICT single-provider (no fallback)
```

Valid cores: `groq`, `gemini`, `mistral`, `sambanova`, `openrouter`, `nvidia`,
`huggingface`, `cerebras`, `ollama`, `claude`, `openai`. Without
`TERMINUS_CORE_ONLY`, the pinned core just leads and the normal chain still
backs it up, so a dedicated brain never goes fully silent if its provider blips.

## Adding a seventh brain (and beyond)

Light up another free key, then add a line to the `POOL` array in
`server/ecosystem.brains.config.js` **and** the `POOL` string in
`server/deploy/keepalive.sh` (next free port, next provider), re-run the
launcher, and paste the new `SUBAGENT_BRAIN_URL`. Nothing else to change —
`supervise` picks up whatever's live.
