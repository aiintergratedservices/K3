# Kortana K3 — Complete Setup Guide

Do these in order. Steps 1–3 get her running on the phone; step 4 connects
her 5TB Drive; step 5 backs up all your repositories into it. Everything
happens **on your phone** unless noted.

---

## 1. Install her stack (Termux)

Install **Termux from F-Droid** (https://f-droid.org — the Play Store build
is outdated and won't work well). Then open Termux and run, one line at a time:

```bash
pkg update -y
pkg install -y nodejs-lts ollama git zip
```

Get her brain:

```bash
ollama serve &
ollama pull phi3.5
```

> phi3.5 needs ~2.5 GB free RAM. If your phone struggles, use
> `ollama pull phi3:mini` instead — the app auto-detects whichever phi3
> variant is installed.

Get her home server:

```bash
git clone https://github.com/aiintergratedservices/K3 ~/k3
cd ~/k3/server
npm install
cp .env.example .env
```

Start everything:

```bash
bash deploy/termux-start.sh
```

**Verify it's alive:**

```bash
curl http://127.0.0.1:3300/health
```

You want to see `"ollama":{"reachable":true,"model":"phi3.5..."}` in the
output. If you see `"reachable":false`, run `ollama serve &` again, wait 10
seconds, and re-check.

**Test that phi3 actually answers as her:**

```bash
curl -s -X POST http://127.0.0.1:3300/api/brain \
  -H 'content-type: application/json' \
  -d '{"message":"Kortana, status report."}'
```

The reply should come back with `"core":"ollama:phi3.5"` — that's her local
brain talking. If it says `"core":"rules"`, Ollama isn't up yet.

**Keep it running forever:** install the **Termux:Boot** app (also F-Droid),
open it once, then:

```bash
mkdir -p ~/.termux/boot
cp ~/k3/server/deploy/termux-start.sh ~/.termux/boot/
```

Now her whole stack restarts itself every time the phone reboots, with a
wakelock so Android doesn't kill it.

## 2. Install the app

Download **kortana-k3-debug.apk** from
https://github.com/aiintergratedservices/K3/releases/tag/kortana-latest
on the phone, allow "install unknown apps" when prompted, open the file.

She's preconfigured to find Ollama at `127.0.0.1:11434` and Terminus at
`127.0.0.1:3300` — nothing to change. Say hello.

## 3. Add the cloud backups (optional but recommended)

In Termux:

```bash
cd ~/k3/server && nano .env
```

Fill in:

- `ANTHROPIC_API_KEY` — from https://console.anthropic.com (Claude backup tier)
- `GEMINI_API_KEY` — from https://aistudio.google.com/apikey (last resort tier)

Restart: `pkill -f "node index.js" && bash deploy/termux-start.sh`

For the **app's** cloud tiers, the keys are baked in at build time: add them
as `.env` in the `android/` folder before building, or just rely on the
server-side chain.

## 4. Hook up the 5TB Drive (one-time, ~10 minutes)

This connects Terminus to the Google One storage on your
**a.i.intergrated.services@gmail.com** account. You need a browser — doing it
on a computer is easier, but phone works.

1. Go to https://console.cloud.google.com — **sign in as
   a.i.intergrated.services@gmail.com** (top-right avatar — this matters).
2. Create a project: top bar → project picker → **New Project** → name it
   `kortana` → Create.
3. Enable the Drive API: menu → **APIs & Services → Library** → search
   "Google Drive API" → **Enable**.
4. OAuth consent: **APIs & Services → OAuth consent screen** → External →
   app name `Kortana`, your email for both contact fields → Save through the
   remaining screens (no scopes needed here) → under **Audience / Test
   users**, add a.i.intergrated.services@gmail.com.
5. Create the client: **APIs & Services → Credentials → Create Credentials →
   OAuth client ID** → Application type **Desktop app** → name `terminus` →
   Create. Copy the **Client ID** and **Client Secret**.
6. In Termux:
   ```bash
   cd ~/k3/server && nano .env
   # paste GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET
   npm run auth
   ```
   Open the URL it prints, sign in as **a.i.intergrated.services@gmail.com**,
   approve (it will warn the app is unverified — tap Advanced → continue;
   it's your own app). Copy the refresh token it prints into `.env` as
   `GOOGLE_REFRESH_TOKEN`.
7. Restart Terminus and verify:
   ```bash
   pkill -f "node index.js" && bash deploy/termux-start.sh
   sleep 3 && curl http://127.0.0.1:3300/api/drive
   ```
   You should see your account email and the 5TB quota. Terminus has now
   created the `Kortana/` folder tree in that Drive and mirrored her
   identity files into it. From here, **every sync writes her complete self
   to the Drive automatically** — state history, memories, chats, scripts.

## 5. House your repositories in the Drive too

Once step 4 works, mirror every GitHub repo into `Kortana/repos/`:

```bash
cd ~/k3/server
node scripts/backup-repos-to-drive.js
```

For private repos, first add `GITHUB_TOKEN=<a GitHub personal access token>`
to `.env` (github.com → Settings → Developer settings → Personal access
tokens → fine-grained, read-only on your repos is enough).

Re-run it whenever you want fresh mirrors, or schedule it nightly in Termux:

```bash
pkg install cronie termux-services
crontab -e   # add:  0 3 * * * cd ~/k3/server && node scripts/backup-repos-to-drive.js
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| `/health` says ollama unreachable | `ollama serve &` then wait 10s. Low RAM? `ollama pull phi3:mini`. |
| Brain replies `"core":"rules"` | Same as above — no cores reachable. Check `ANTHROPIC_API_KEY`/`GEMINI_API_KEY` in `.env` for the cloud tiers. |
| `/api/drive` says `"enabled":false` | The three `GOOGLE_*` values in `.env` — re-run step 4.6. Make sure you signed in as the 5TB account. |
| App can't reach Terminus | Both must be on the same phone (127.0.0.1), or set the app's Cloud Sync URL to `http://<server-ip>:3300/api/sync` + the API key from `.env`. |
| Terminus dies when phone sleeps | Install Termux:Boot, copy the boot script (end of step 1), and disable battery optimization for Termux in Android settings. |
