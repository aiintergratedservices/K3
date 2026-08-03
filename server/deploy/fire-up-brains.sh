#!/usr/bin/env bash
# fire-up-brains.sh — stand up Kortana's SECONDARY BRAIN POOL and keep it up.
#
# Her main brain (kortana-terminus, :3300) does the talking. This brings up the
# extra brains (#2 :3301, #3 :3302, #4 :3303) she delegates to, so `supervise`
# can fan sub-agents across several INDEPENDENT brains at once and she can run
# one supervisor per directive without them contending. Each is daemonized under
# PM2 (auto-restart, boot-safe) and dedicated to its own free provider.
#
# Run it as many times as you like — it reconciles to the desired state and then
# tells you EXACTLY which brains are functional and prints the SUBAGENT_BRAIN_URL
# line to paste (it only lists the ones that actually answered with a live core;
# a brain still waiting on an API key is skipped, never faked).
#
#   bash server/deploy/fire-up-brains.sh
#
# Works on the PHONE (Termux): each pool brain is dedicated to a CLOUD provider,
# so it's a light Node process, not a model in RAM — several run fine next to the
# main brain. PM2 is the same one fire-up-all.sh already uses; if it's missing,
# this falls back to nohup (still assigning each brain its core).
#
# Prereqs: server deps installed (npm install in server/), and at least one
# provider key per brain set in server/.env (Groq for brain-2, Gemini for
# brain-3, Mistral for brain-4 — all FREE, no card; see .env.example). A brain
# with no key still boots and is reachable, it just isn't "functional" yet, so
# this script (and supervise's own preflight) skips it.
set -u

SERVER_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$SERVER_DIR" || { echo "[brains] FATAL: cannot cd to $SERVER_DIR"; exit 1; }

# port -> dedicated core; MUST stay in sync with ecosystem.brains.config.js
PORTS=(3301 3302 3303 3304 3305)
CORES=(groq gemini mistral sambanova nvidia)
HOST="127.0.0.1"

log() { echo "[brains] $*"; }

mkdir -p ./logs

# On the phone, hold a wakelock so Android doesn't sleep the pool (best effort).
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock || true

# 1. Bring the pool up (or reconcile). PM2 keeps them alive + boot-safe.
if command -v pm2 >/dev/null 2>&1; then
  pm2 start ecosystem.brains.config.js --update-env || pm2 restart ecosystem.brains.config.js
  pm2 save
else
  log "WARN: pm2 not installed (npm i -g pm2). Falling back to nohup — NOT boot-safe."
  idx=0
  for p in "${PORTS[@]}"; do
    core="${CORES[$idx]}"; n=$((idx+2))
    if ! pgrep -f "PORT=$p .*index.js" >/dev/null 2>&1; then
      PORT="$p" TERMINUS_CORE="$core" nohup node index.js >"./logs/kortana-brain-$n-out.log" 2>&1 &
      log "started brain :$p (core=$core) via nohup (pid $!)"
    fi
    idx=$((idx+1))
  done
fi

# 2. Health preflight — the SAME check supervise uses: reachable AND has ≥1 live
#    core (not just a set URL). Only functional brains make the pasted pool list.
sleep 4
LIVE=()
for p in "${PORTS[@]}"; do
  url="http://${HOST}:${p}"
  body="$(curl -sf --max-time 6 "${url}/health" 2>/dev/null || true)"
  if [ -z "$body" ]; then
    log "brain :$p — DOWN (not answering /health yet; check: pm2 logs)"
    continue
  fi
  # a live core = ollama.reachable true OR any provider core true
  if echo "$body" | grep -qE '"reachable":true|"(groq|cerebras|mistral|sambanova|openrouter|nvidia|huggingface|gemini|claude|openai)":true'; then
    log "brain :$p — FUNCTIONAL ✓"
    LIVE+=("${url}")
  else
    log "brain :$p — up but NO LIVE CORE (still needs an API key in server/.env) — skipped"
  fi
done

echo "----------------------------------------------------------------------"
if [ "${#LIVE[@]}" -eq 0 ]; then
  log "No functional secondary brains yet. Set at least one free provider key"
  log "in server/.env (Groq/Gemini/Mistral — see .env.example), then re-run me."
  log "Until then she just uses her main brain; supervise declines honestly."
else
  JOINED="$(IFS=,; echo "${LIVE[*]}")"
  log "${#LIVE[@]} functional secondary brain(s). Put this in server/.env:"
  echo
  echo "  SUBAGENT_BRAIN_URL=${JOINED}"
  echo
  log "Then restart her main brain so it picks it up:  pm2 restart kortana-terminus"
  log "supervise re-checks /health at call time too, so a brain that drops later"
  log "is skipped automatically — the pasted list is just the current healthy set."
fi
echo "----------------------------------------------------------------------"
