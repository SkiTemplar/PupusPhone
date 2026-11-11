#!/data/data/com.termux/files/usr/bin/bash
# Starts the PupusPhone voice bridge inside Termux (no PC needed).
# Idempotent: exits quietly if a bridge is already listening, so the app can call it on every
# car-mode start (Termux RUN_COMMAND). Logs go to $PUPUS_LOG_FILE (default ~/.pupusphone/bridge.log).
set -euo pipefail
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${PUPUS_ENV_FILE:-$HOME/.pupusphone.env}"

[ -f "$ENV_FILE" ] || { echo "Missing $ENV_FILE: run scripts/termux-setup.sh first" >&2; exit 1; }
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

STATE_DIR="${PUPUS_STATE_DIR:-$HOME/.pupusphone}"
mkdir -p "$STATE_DIR"

# A stop still running (car mode switched off and on again) closes the Termux service a moment
# later: that releases the wake lock and wipes $TMPDIR under a bridge started meanwhile. Wait for
# it, plus a short grace for the service to finish going away.
STOPPING_DIR="$STATE_DIR/stopping"
is_stop_running() {
  local marker pid
  for marker in "$STOPPING_DIR"/*; do
    [ -e "$marker" ] || continue
    pid="${marker##*/}"
    if [ -r "/proc/$pid/cmdline" ] && tr '\0' ' ' < "/proc/$pid/cmdline" | grep -q "termux-stop.sh"; then return 0; fi
  done
  return 1
}
if [ -d "$STOPPING_DIR" ] && [ -n "$(ls -A "$STOPPING_DIR" 2>/dev/null)" ]; then
  for _ in $(seq 1 40); do
    is_stop_running || break
    sleep 0.5
  done
  rm -rf "$STOPPING_DIR"
  sleep "${PUPUS_STOP_GRACE_SECONDS:-2}"
fi

HOST="${PUPUS_BRIDGE_HOST:-127.0.0.1}"
PORT="${PUPUS_BRIDGE_PORT:-8766}"
if node -e "require('net').connect($PORT, '$HOST').on('connect', () => process.exit(0)).on('error', () => process.exit(1))"; then
  # The watchdog calls this while the bridge is unreachable: if the Termux service was closed
  # under a live bridge, taking the wake lock again brings the service back and unfreezes it.
  termux-wake-lock 2>/dev/null || true
  echo "Bridge already running on $HOST:$PORT"
  exit 0
fi

LOG_FILE="${PUPUS_LOG_FILE:-$STATE_DIR/bridge.log}"
mkdir -p "$(dirname "$LOG_FILE")"

# One start at a time. Android can deliver two RUN_COMMAND starts together (a frozen Termux gets
# the queued one late): both would see the port free, one would fail with EADDRINUSE and its
# cleanup below would kill the bridge that won. The loser leaves without touching anything.
LOCK_DIR="${PUPUS_LOCK_DIR:-$STATE_DIR/start.lock}"
is_start_script() {
  [ -n "$1" ] && [ -r "/proc/$1/cmdline" ] && tr '\0' ' ' < "/proc/$1/cmdline" | grep -q "termux-start.sh"
}
take_lock() {
  mkdir "$LOCK_DIR" 2>/dev/null || return 1
  echo "$$" > "$LOCK_DIR/pid"
}
if ! take_lock; then
  # The owner writes its PID right after mkdir: give it a moment before calling the lock stale.
  [ -s "$LOCK_DIR/pid" ] || sleep 0.5
  owner="$(cat "$LOCK_DIR/pid" 2>/dev/null || true)"
  if is_start_script "$owner"; then
    echo "Another termux-start.sh (pid $owner) is already starting the bridge"
    exit 0
  fi
  # Stale lock: its owner was killed (Termux closed) before it could remove it.
  rm -rf "$LOCK_DIR"
  take_lock || { echo "Another termux-start.sh took the lock"; exit 0; }
fi
trap 'rm -rf "$LOCK_DIR"' EXIT
# Keep the last ~1 MB of logs.
if [ -f "$LOG_FILE" ] && [ "$(wc -c < "$LOG_FILE")" -gt 1048576 ]; then
  tail -c 524288 "$LOG_FILE" > "$LOG_FILE.tmp" && mv "$LOG_FILE.tmp" "$LOG_FILE"
fi

# On the phone the app can be killed without notice (swiped away): the bridge exits on its own
# after this many seconds without a phone connected, and everything is cleaned up below.
export PUPUS_IDLE_EXIT_SECONDS="${PUPUS_IDLE_EXIT_SECONDS:-180}"
# Out of $TMPDIR, which Termux wipes when its service stops.
export PUPUS_BRIDGE_WORKDIR="${PUPUS_BRIDGE_WORKDIR:-$STATE_DIR/work}"

termux-wake-lock 2>/dev/null || echo "termux-wake-lock unavailable; keep Termux unrestricted in battery settings" >&2
cd "$REPO_DIR/pc"
echo "Starting bridge on $HOST:$PORT (log: $LOG_FILE)"
status=0
node packages/bridge/src/cli.ts >> "$LOG_FILE" 2>&1 || status=$?
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) [start] bridge exited with status $status" >> "$LOG_FILE"
# Wake lock, leftover Claude/MCP processes and the Termux service go with the bridge.
# The lock goes first: the stop may kill this script before its EXIT trap runs.
rm -rf "$LOCK_DIR"
bash "$REPO_DIR/scripts/termux-stop.sh"
exit "$status"
