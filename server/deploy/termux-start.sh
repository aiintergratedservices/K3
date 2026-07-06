#!/data/data/com.termux/files/usr/bin/bash
# Runs Kortana's entire stack ON THE PHONE inside Termux:
# the Ollama phi3 brain + the Terminus server, kept alive with a wakelock.
#
# One-time setup:
#   pkg install nodejs-lts ollama git
#   git clone <k3 repo> ~/k3 && cd ~/k3/server && npm install && cp .env.example .env
#   ollama pull phi3.5   (or phi3 / phi3:mini for low-RAM phones)
#
# Auto-start on boot: install the Termux:Boot app, then
#   mkdir -p ~/.termux/boot && cp deploy/termux-start.sh ~/.termux/boot/
set -e

termux-wake-lock || true

# 1. Local phi3 brain
if ! pgrep -f "ollama serve" > /dev/null; then
  nohup ollama serve > ~/ollama.log 2>&1 &
  echo "[termux] ollama daemon started"
fi

# 2. Terminus — her persistent home
cd "$(dirname "$0")/.." 2>/dev/null || cd ~/k3/server
if ! pgrep -f "node index.js" > /dev/null; then
  nohup node index.js > ~/terminus.log 2>&1 &
  echo "[termux] Terminus server started on port 3300"
fi

echo "[termux] Kortana's stack is up. Logs: ~/ollama.log ~/terminus.log"
