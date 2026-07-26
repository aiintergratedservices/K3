# K3 — Kortana

**One repo. One Kortana.** Her soul, her phone body, and **Terminus** — her own
always-on server — with a Google Drive archive so everything she is and learns
persists.

```
k3/
├── identity/       Her soul: soul_manifesto.md, preferences.json,
│                   kortana_protocol.json, memory_log.md
├── android/        Her phone body: Kotlin + Jetpack Compose companion app
├── server/         Terminus: her Node server + brain chain (POST /api/brain)
├── .agent-memory/  Her memory + skills (loaded into every prompt) + learned lessons
├── docker-compose.yml   Terminus + Ollama in one command (real machine / VM)
└── fly.toml        Optional Fly.io deploy
```

## Her brain — one chain, wherever she runs

| Tier | Core | When |
|------|------|------|
| 1 | **Ollama** (local, e.g. `qwen2.5-coder:3b`) | Default. Free, private, offline. Needs a few GB of RAM. |
| 2 | **Groq** / **Gemini** API | Free, no card. Fast cloud fallback when Ollama is slow/absent — the way to run her without a big box. |
| 3 | **Claude** API | Optional, when a key is set. |
| 4 | Rules core | Everything down; she still answers. |

`server/brain.js` runs the whole chain and picks the best reachable core. Keys
live in `server/.env` (never committed).

## Run her

**On a real box / VM (recommended — she never times out):**
```bash
docker compose up -d
docker compose exec ollama ollama pull qwen2.5-coder:3b   # optional local brain
# or skip Ollama and set GROQ_API_KEY / GEMINI_API_KEY in server/.env
```

**On the phone (Termux):**
```bash
bash server/deploy/fire-up-all.sh      # Ollama + Terminus under PM2, wakelock
```
See `docs/SETUP.md` for the full phone walkthrough and `HOSTING.md` for free
always-on hosting options (no card required).

## What she can do
- **Brain chain** with tool use (web search/fetch, safe read-only shell, memory).
- **Writes her own skills** as she needs them (`.agent-memory/skills/`), loaded
  into her prompt automatically.
- **Spawns sub-agents** for delegated tasks, routed to a secondary brain so they
  never slow her main one down.
- **Feeds the Early Warning System** — posts heard police/fire dispatches to
  CampLoJack's `/api/scanner` so nearby people get warned.
- **Persists** to a 5 TB Google Drive archive so a wipe never erases her.

## API keys without rebuilding
Put keys in `server/.env` once (`GROQ_API_KEY`, `GEMINI_API_KEY`, optional
`ANTHROPIC_API_KEY`) and every device she roams to gets them via Terminus.
`TERMINUS_API_KEY` is required to expose her beyond localhost — it's also her
API key for the Android app's Cloud Sync.
