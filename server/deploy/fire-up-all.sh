#!/data/data/com.termux/files/usr/bin/bash
# fire-up-all.sh — bring Kortana's whole brain up on the phone and KEEP it up.
#
# Runs the local phi3 brain (Ollama) + her Terminus server under PM2 so both
# auto-restart on crash, then saves the PM2 process list so they come back after
# a reboot. Safe to run repeatedly — it reconciles to the desired state.
#
# One-time setup (do once, on Wi-Fi):
#   pkg install nodejs-lts ollama git
#   npm install -g pm2
#   cd ~/k3/server && npm install && cp .env.example .env   # then edit .env
#   ollama pull phi3:mini            # ~2 GB local brain
#   # auto-start on boot: install the Termux:Boot app, then:
#   mkdir -p ~/.termux/boot && ln -sf ~/k3/server/deploy/fire-up-all.sh ~/.termux/boot/
#
# Daily use: just run  bash ~/k3/server/deploy/fire-up-all.sh
set -u

SERVER_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OLLAMA_MODEL="${OLLAMA_MODEL:-phi3:mini}"
TERMINUS_PORT="${PORT:-3300}"

log() { echo "[fire-up] $*"; }

# 1. Hold a wakelock so Android doesn't sleep the stack.
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock || true

# 2. Make sure a local model is installed, or the tier-1 brain is empty.
if command -v ollama >/dev/null 2>&1; then
  # Start the daemon briefly if needed so we can query/pull.
  pgrep -f "ollama serve" >/dev/null 2>&1 || (nohup ollama serve >/dev/null 2>&1 & sleep 3)
  if ! ollama list 2>/dev/null | grep -qiE "phi3|llama3.2"; then
    log "No local model found — pulling $OLLAMA_MODEL (~2 GB, use Wi-Fi)…"
    ollama pull "$OLLAMA_MODEL" || log "WARN: model pull failed; local brain will be empty until you pull one."
  fi
else
  log "WARN: ollama not installed — skipping local brain. Cloud cores (Terminus/Claude/Gemini) will carry her."
fi

# 3. Bring the stack up under PM2 (Terminus + Ollama, in the 'kortana' namespace).
cd "$SERVER_DIR" || { log "FATAL: cannot cd to $SERVER_DIR"; exit 1; }
if command -v pm2 >/dev/null 2>&1; then
  pm2 start ecosystem.config.js --update-env || pm2 restart ecosystem.config.js
  pm2 save   # persist so `pm2 resurrect` restores everything after a reboot
else
  log "WARN: pm2 not installed (npm install -g pm2). Falling back to nohup."
  pgrep -f "node .*index.js" >/dev/null 2>&1 || nohup node index.js >./logs/terminus-out.log 2>&1 &
fi

# 4. Health check — tell the user plainly whether her brain is actually reachable.
sleep 3
if curl -sf "http://127.0.0.1:${TERMINUS_PORT}/health" >/dev/null 2>&1; then
  log "OK — Terminus is UP at http://127.0.0.1:${TERMINUS_PORT}"
else
  log "WARN: Terminus not answering on :${TERMINUS_PORT} yet. Check: pm2 logs kortana-terminus"
fi
log "Done. In the app, point Cloud Sync at http://127.0.0.1:${TERMINUS_PORT} and leave the API-key"
log "field EMPTY unless you set TERMINUS_API_KEY in .env (a mismatch causes the HTTP 401 sync error)."
