# Hosting Kortana for free (with an Ollama brain)

Her brain is **Ollama** — a real LLM that must hold a model in RAM (~2–3 GB for a
small one) on an always-on process. That single fact decides where she can live.

## Why the usual "free" hosts don't work
| Host | Runs Ollama? | Why |
|------|:---:|-----|
| **Vercel** | ❌ | Serverless functions only — no always-on process, no RAM to hold a model, functions die in seconds. |
| **Netlify** | ❌ | Same as Vercel — serverless, ephemeral. |
| **Render (free)** | ❌ | Sleeps after 15 min idle; 512 MB RAM — too small for a model. |
| **Railway** | 💸 | Can run a persistent server, but the free trial is what ran out; paid, and RAM costs more. |
| **Fly (free)** | 💸 | No real free tier now; Ollama needs paid RAM. |

The pattern: free tiers give a sliver of RAM or no persistent process. A local
model needs a few GB that stays put. So you need a box that's actually free AND
gives real RAM — or run her on hardware you already own.

## Option A — Oracle Cloud "Always Free" (free forever, enough RAM) ✅ recommended
Oracle's Always Free tier includes an **Ampere A1 ARM VM: up to 4 CPUs + 24 GB
RAM, free forever** (not a trial). That runs Ollama + Terminus always-on for $0.

1. Create an Oracle Cloud account (needs a card for identity — Always Free is not
   charged). https://www.oracle.com/cloud/free/
2. Create a VM: **Ampere A1 (ARM)**, shape `VM.Standard.A1.Flex`, e.g. 2 CPU / 12 GB
   (within the free 4 CPU / 24 GB allowance). Ubuntu image. If the region says
   "out of capacity," retry later or pick another availability domain — free ARM
   is popular.
3. Open ports for SSH (22) and Terminus (3300) in the VM's security list.
4. On the VM:
   ```bash
   sudo apt update && sudo apt install -y docker.io docker-compose-plugin git
   git clone https://github.com/aiintergratedservices/k3 && cd k3
   cp server/.env.example server/.env
   # edit server/.env: set TERMINUS_API_KEY=<long random>, leave the API keys blank
   sudo docker compose up -d           # Terminus + Ollama
   sudo docker compose exec ollama ollama pull qwen2.5-coder:3b   # her code brain
   ```
5. Point the Android app's Cloud Sync at `http://<vm-public-ip>:3300` with the same
   `TERMINUS_API_KEY`. She's now always-on, free, with a real local brain.

## Option B — Keep her on the 8 GB phone, stop Android killing her (free, today)
The phone already runs Ollama free. The only problem was Android killing the
process. Fix that on-device:
1. **Wakelock** — turn on the BashMeSilly app's wakelock toggle (a real
   `PARTIAL_WAKE_LOCK`), so the CPU keeps her alive with the screen off.
2. **Battery** — Android Settings → Apps → Termux → Battery → **Unrestricted**
   (exempt from battery optimization). Same for the companion app.
3. **Auto-restart** — install **Termux:Boot** (F-Droid) and add a boot script that
   starts Terminus + Ollama, so she comes back after a reboot or kill.
4. Keep the phone charging when she needs to stay up. Use a small model
   (`qwen2.5-coder:3b` or smaller — see the ollama-brain skill) so 8 GB holds her
   plus Android.

Downside vs Oracle: the phone has to stay on/charged and is still less reliable
than a real always-on VM — but it's $0 and uses what you already own.

## What about the Fly config in this repo?
`fly.toml` is still here for the day you have a budget or an API key — Fly runs
Terminus fine, but a cheap Fly VM can't run Ollama and you have no cloud API
keys, so today it'd leave her on the flat rules core. Prefer Oracle (free +
Ollama) or the phone until that changes.

## The one thing none of these change
CampLoJack's Early Warning System does **not** depend on where Kortana lives. Its
traffic + fire proximity alerts run free on Vercel + a GitHub cron regardless.
Kortana is the *scanner* feeder (the bonus all-call-types layer) — host her
wherever's free; the core app keeps protecting people either way.
