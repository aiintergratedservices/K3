#!/data/data/com.termux/files/usr/bin/sh
# keepalive.sh — keep Kortana's WHOLE stack alive 24/7 on the phone, self-healing.
#
# Runs her server DIRECTLY with `node` — NO PM2. PM2's socket layer (pm2-axon)
# crashes on Node 24 with an unhandled "Parser error" event, so it could never
# reliably keep her up on this phone. This watchdog does the job itself:
# every CHECK_SECS it verifies each piece of her and refires whatever's down:
#   1. Ollama    — her local model daemon (port 11434)
#   2. Terminus  — her brain server, run as `node index.js` and checked by
#                  actually hitting /health (so a hung OR crashed server is
#                  caught and restarted)
# It holds a Termux wake-lock so Android's battery killer can't stop her, keeps
# one pidfile so it never spawns duplicate servers, and logs to
# logs/keepalive.log (her server's own output goes to logs/terminus-out.log).
#
# Run it:   sh ~/k3/server/deploy/keepalive.sh   (normally launched at boot)

SERVER="$HOME/k3/server"
LOG="$SERVER/logs/keepalive.log"
OUT="$SERVER/logs/terminus-out.log"
TERMINUS_PID="$SERVER/logs/terminus.pid"
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

terminus_up() { curl -sf --max-time 8 "$HEALTH_URL" >/dev/null 2>&1; }

start_terminus() {
  # Stop a stale copy we started before (by pidfile), then sweep any orphan, so
  # a fresh start can bind :3300 cleanly.
  [ -f "$TERMINUS_PID" ] && kill "$(cat "$TERMINUS_PID" 2>/dev/null)" 2>/dev/null
  pkill -f "node index.js" 2>/dev/null
  sleep 1
  # MUST cd into the server dir first: index.js loads .env via dotenv from the
  # current directory, so her keys only load when cwd is server/.
  ( cd "$SERVER" && nohup node index.js >> "$OUT" 2>&1 & echo $! > "$TERMINUS_PID" )
  log "started terminus (node index.js, pid $(cat "$TERMINUS_PID" 2>/dev/null))"
}

termux-wake-lock 2>/dev/null
log "==== keepalive started (pid $$, no-PM2 direct-node mode) ===="

while true; do
  # Keep Termux awake (idempotent — safe to call every loop).
  termux-wake-lock 2>/dev/null

  # 1) Ollama — start it if no 'ollama serve' is running. (One instance only.)
  if ! pgrep -f "ollama serve" >/dev/null 2>&1; then
    log "ollama down -> starting"
    nohup ollama serve >"$SERVER/logs/ollama.log" 2>&1 &
    sleep 5
  fi

  # 2) Terminus — the real test is whether /health answers. Catches a crashed
  #    OR hung server. (Re)start her directly with node if it doesn't.
  if ! terminus_up; then
    log "terminus /health not answering -> (re)starting"
    start_terminus
    sleep 8
  fi

  sleep "$CHECK_SECS"
done

# ============================================================================
# ONE-TIME SETUP so she survives reboots and Android's battery killer:
#
# 0) PM2 is not used anymore. Shut its daemon down once so it can't fight for
#    port 3300:   pm2 kill   (safe — the watchdog runs her now)
# 1) Install the "Termux:Boot" app, open it once to enable it.
# 2) Android Settings -> Apps -> Termux (and Termux:Boot) -> Battery ->
#    "Unrestricted" / disable optimization, and allow background activity.
# 3) Make Termux run this on boot (~/.termux/boot/kortana.sh):
#      #!/data/data/com.termux/files/usr/bin/sh
#      termux-wake-lock
#      sh ~/k3/server/deploy/keepalive.sh &
# 4) Start it now:  sh ~/k3/server/deploy/keepalive.sh &
# ============================================================================
