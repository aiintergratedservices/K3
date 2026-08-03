#!/data/data/com.termux/files/usr/bin/sh
# keepalive.sh — keep Kortana's WHOLE stack alive 24/7 on the phone, self-healing.
#
# Runs everything DIRECTLY with `node` — NO PM2. PM2's socket layer (pm2-axon)
# crashes on Node 24 with an unhandled "Parser error" event, so it could never
# reliably keep her up on this phone. This ONE watchdog does the whole job:
# every CHECK_SECS it verifies each piece of her and refires whatever's down:
#   1. Ollama       — her local model daemon (port 11434)
#   2. Terminus     — her MAIN brain, `node index.js` on :3300, checked by
#                     actually hitting /health (a hung OR crashed server is caught)
#   3. Brain POOL   — her secondary brains #2/#3/#4 on :3301/:3302/:3303, each a
#                     LIGHT cloud-dedicated node process (not a model in RAM), so
#                     `supervise` can fan sub-agents across several at once. Each
#                     pool brain is started ONLY if its provider key is set, so a
#                     brain that isn't functional never wastes the phone's RAM —
#                     and self-heals exactly like the main one.
# It holds a Termux wake-lock so Android's battery killer can't stop her, keeps
# one pidfile per process so it never spawns duplicates, and logs to
# logs/keepalive.log.
#
# Run it:   sh ~/k3/server/deploy/keepalive.sh   (normally launched at boot)
# Easiest:  bash ~/k3/server/deploy/fire-up-everything.sh   (sets up boot + runs this)

SERVER="$HOME/k3/server"
LOG="$SERVER/logs/keepalive.log"
OUT="$SERVER/logs/terminus-out.log"
TERMINUS_PID="$SERVER/logs/terminus.pid"
CHECK_SECS=30
HEALTH_URL="http://127.0.0.1:3300/health"

# The secondary brain pool: "port:core" each. Keep in sync with
# ecosystem.brains.config.js and SUBAGENT_BRAIN_URL. A brain is only started if
# its provider key exists in .env (see key_for / key_set), so unlit slots cost
# nothing.
POOL="3301:groq 3302:gemini 3303:mistral 3304:sambanova 3305:openrouter"

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

# Which env var does a given dedicated core need to be functional?
key_for() {
  case "$1" in
    groq) echo GROQ_API_KEY ;;
    gemini) echo GEMINI_API_KEY ;;
    mistral) echo MISTRAL_API_KEY ;;
    sambanova) echo SAMBANOVA_API_KEY ;;
    openrouter) echo OPENROUTER_API_KEY ;;
    nvidia) echo NVIDIA_API_KEY ;;
    huggingface) echo HF_TOKEN ;;
    cerebras) echo CEREBRAS_API_KEY ;;
    claude) echo ANTHROPIC_API_KEY ;;
    openai) echo OPENAI_API_KEY ;;
    ollama) echo OLLAMA ;;   # local, always allowed
    *) echo "" ;;
  esac
}

# Is a key set (non-empty, uncommented) in the environment or in .env?
key_set() {
  var="$1"
  [ "$var" = "OLLAMA" ] && return 0            # local core, no key needed
  [ -z "$var" ] && return 1
  # environment wins
  eval "val=\${$var:-}"
  [ -n "$val" ] && return 0
  # else look in .env for  VAR=<something non-empty, non-comment>
  [ -f "$SERVER/.env" ] && grep -Eq "^[[:space:]]*$var=[^[:space:]#].*" "$SERVER/.env"
}

http_up() { curl -sf --max-time 8 "$1" >/dev/null 2>&1; }

# Free a port so a fresh node can bind it — RELIABLY. Every node we launch is
# tagged with `--kortana-port=<port>` in its ARGV (index.js ignores the flag and
# still reads PORT from the environment). The old code matched on "PORT=<port>",
# which lives in the ENVIRONMENT and never in argv — so pkill saw nothing, a
# stuck brain kept squatting its port, and every restart died with EADDRINUSE.
# The marker is on the command line, so pkill targets exactly that one process;
# fuser (if present) mops up any untagged orphan holding the socket too.
free_port() {
  port="$1"; pidname="${2:-brain-$port}"
  pidf="$SERVER/logs/$pidname.pid"
  [ -f "$pidf" ] && kill "$(cat "$pidf" 2>/dev/null)" 2>/dev/null
  pkill -f "index.js --kortana-port=$port" 2>/dev/null
  command -v fuser >/dev/null 2>&1 && fuser -k "$port/tcp" 2>/dev/null
  sleep 1
}

start_terminus() {
  # Only free :3300 — do NOT sweep every `node index.js`, that used to kill the
  # whole pool every time the main brain restarted.
  free_port 3300 terminus
  # MUST cd into server/ first: index.js loads .env via dotenv from cwd.
  ( cd "$SERVER" && nohup node index.js --kortana-port=3300 >> "$OUT" 2>&1 & echo $! > "$TERMINUS_PID" )
  log "started MAIN terminus (:3300, pid $(cat "$TERMINUS_PID" 2>/dev/null))"
}

start_brain() {
  port="$1"; core="$2"
  pidf="$SERVER/logs/brain-$port.pid"
  free_port "$port"
  ( cd "$SERVER" && PORT="$port" TERMINUS_CORE="$core" nohup node index.js --kortana-port="$port" \
      >> "$SERVER/logs/brain-$port-out.log" 2>&1 & echo $! > "$pidf" )
  log "started POOL brain :$port (core=$core, pid $(cat "$pidf" 2>/dev/null))"
}

termux-wake-lock 2>/dev/null
log "==== keepalive started (pid $$, direct-node, main + pool) ===="

while true; do
  # Keep Termux awake (idempotent — safe to call every loop).
  termux-wake-lock 2>/dev/null

  # 1) Ollama — start it if no 'ollama serve' is running. (One instance only.)
  if command -v ollama >/dev/null 2>&1 && ! pgrep -f "ollama serve" >/dev/null 2>&1; then
    log "ollama down -> starting"
    nohup ollama serve >"$SERVER/logs/ollama.log" 2>&1 &
    sleep 5
  fi

  # 2) MAIN terminus — the real test is whether /health answers (catches a
  #    crashed OR hung server). (Re)start directly with node if it doesn't.
  if ! http_up "$HEALTH_URL"; then
    log "MAIN terminus /health not answering -> (re)starting"
    start_terminus
    sleep 8
  fi

  # 3) POOL brains — each self-heals like the main one, but only runs when its
  #    provider key is actually set (otherwise it'd be a non-functional brain
  #    eating RAM). supervise still health-checks at call time regardless.
  for entry in $POOL; do
    port="${entry%%:*}"; core="${entry##*:}"
    varname="$(key_for "$core")"
    if key_set "$varname"; then
      if ! http_up "http://127.0.0.1:$port/health"; then
        log "POOL brain :$port ($core) down -> (re)starting"
        start_brain "$port" "$core"
        sleep 2
      fi
    fi
  done

  sleep "$CHECK_SECS"
done

# ============================================================================
# ONE-TIME SETUP so she survives reboots and Android's battery killer:
# (the fire-up-everything.sh script does steps 3-4 for you)
#
# 0) PM2 is not used on the phone. If it's ever running:  pm2 kill
# 1) Install the "Termux:Boot" app, open it once to enable it.
# 2) Android Settings -> Apps -> Termux (and Termux:Boot) -> Battery ->
#    "Unrestricted" / disable optimization, and allow background activity.
# 3) Make Termux run this on boot (~/.termux/boot/kortana-boot.sh):
#      #!/data/data/com.termux/files/usr/bin/sh
#      termux-wake-lock
#      sh ~/k3/server/deploy/keepalive.sh &
# 4) Start it now:  sh ~/k3/server/deploy/keepalive.sh &
# ============================================================================
