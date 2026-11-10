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

HOST="${PUPUS_BRIDGE_HOST:-127.0.0.1}"
PORT="${PUPUS_BRIDGE_PORT:-8766}"
if node -e "require('net').connect($PORT, '$HOST').on('connect', () => process.exit(0)).on('error', () => process.exit(1))"; then
  echo "Bridge already running on $HOST:$PORT"
  exit 0
fi

LOG_FILE="${PUPUS_LOG_FILE:-$HOME/.pupusphone/bridge.log}"
mkdir -p "$(dirname "$LOG_FILE")"
# Keep the last ~1 MB of logs.
if [ -f "$LOG_FILE" ] && [ "$(wc -c < "$LOG_FILE")" -gt 1048576 ]; then
  tail -c 524288 "$LOG_FILE" > "$LOG_FILE.tmp" && mv "$LOG_FILE.tmp" "$LOG_FILE"
fi

termux-wake-lock 2>/dev/null || echo "termux-wake-lock unavailable; keep Termux unrestricted in battery settings" >&2
cd "$REPO_DIR/pc"
echo "Starting bridge on $HOST:$PORT (log: $LOG_FILE)"
exec node packages/bridge/src/cli.ts >> "$LOG_FILE" 2>&1
