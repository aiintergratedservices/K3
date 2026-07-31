#!/data/data/com.termux/files/usr/bin/sh
# keepalive.sh — keep Kortana's WHOLE stack alive 24/7 on the phone, self-healing.
#
# Every CHECK_SECS it verifies each piece of her and refires whatever's down:
#   1. Ollama        — her local model daemon (port 11434)
#   2. PM2 daemon    — the process manager that runs her server
#   3. Terminus      — her brain server, checked by actually hitting /health
#                      (so a HUNG-but-not-crashed server is caught and restarted,
#                       which PM2 alone would miss)
# It also holds a Termux wake-lock so Android's battery killer can't quietly
# put her to sleep, and logs everything to logs/keepalive.log.
#
# This is belt-AND-suspenders: PM2 already restarts a crashed server on its own;
# this watchdog is the outer layer that restarts PM2 itself, guards Ollama, and
# catches the "process alive but not answering" case. Between the two, the only
# way she goes down is the phone being off — and Termux:Boot brings her right
# back when it powers on (see the setup notes at the bottom of this file).
#
# Run it:   sh ~/k3/server/deploy/keepalive.sh   (normally launched at boot)

SERVER="$HOME/k3/server"
LOG="$SERVER/logs/keepalive.log"
CHECK_SECS=30
HEALTH_URL="http://127.0.0.1:3300/health"

mkdir -p "$SERVER/logs"
log() { echo "$(date '+%Y-%m-%dT%H:%M:%S') $*" >> "$LOG"; }

# Only ever run ONE watchdog — a second copy fighting the first would thrash her.
LOCK="$SERVER/logs/keepalive.lock"
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
  echo "keepalive already running (pid $(cat "$LOCK")) — not starting a second."
  exit 0
fi
echo $$ > "$LOCK"
trap 'rm -f "$LOCK"' EXIT INT TERM

termux-wake-lock 2>/dev/null
log "==== keepalive started (pid $$) ===="

while true; do
  # Keep Termux awake (idempotent — safe to call every loop).
  termux-wake-lock 2>/dev/null

  # 1) Ollama — start it if no 'ollama serve' is running. (One instance only;
  #    a second would hit 'address already in use', so we check first.)
  if ! pgrep -f "ollama serve" >/dev/null 2>&1; then
    log "ollama down -> starting"
    nohup ollama serve >"$SERVER/logs/ollama.log" 2>&1 &
    sleep 5
  fi

  # 2) PM2 daemon — resurrect the saved process list if the daemon isn't up.
  if ! pm2 ping >/dev/null 2>&1; then
    log "pm2 daemon down -> resurrect"
    pm2 resurrect >/dev/null 2>&1
  fi

  # 3) Terminus — the REAL test is whether /health answers. This catches both a
  #    stopped process and a hung one. Restart (or start) her if it doesn't.
  if ! curl -sf --max-time 8 "$HEALTH_URL" >/dev/null 2>&1; then
    log "terminus /health not answering -> (re)starting"
    ( cd "$SERVER" && { pm2 restart kortana-terminus >/dev/null 2>&1 \
        || pm2 start ecosystem.config.js --only kortana-terminus >/dev/null 2>&1; } )
    sleep 6
  fi

  sleep "$CHECK_SECS"
done

# ============================================================================
# ONE-TIME SETUP so she survives reboots and Android's battery killer:
#
# 1) Install the "Termux:Boot" app (F-Droid), open it once to enable it.
# 2) Android Settings -> Apps -> Termux (and Termux:Boot) -> Battery ->
#    "Unrestricted" / disable optimization, and allow background activity.
# 3) Make Termux run this on boot:
#      mkdir -p ~/.termux/boot
#      cat > ~/.termux/boot/kortana.sh <<'EOF'
#      #!/data/data/com.termux/files/usr/bin/sh
#      termux-wake-lock
#      sh ~/k3/server/deploy/keepalive.sh &
#      EOF
#      chmod +x ~/.termux/boot/kortana.sh
# 4) Persist the PM2 process list so 'pm2 resurrect' has something to restore:
#      pm2 save
# 5) Start it now (don't wait for a reboot):
#      chmod +x ~/k3/server/deploy/keepalive.sh
#      sh ~/k3/server/deploy/keepalive.sh &
# ============================================================================
