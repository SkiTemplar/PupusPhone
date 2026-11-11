#!/data/data/com.termux/files/usr/bin/bash
# Stops the PupusPhone voice bridge inside Termux, with the Claude Code process and the MCP server
# it started, releases the Termux wake lock and closes the Termux service (all its sessions).
# The app runs it (Termux RUN_COMMAND) when car mode is turned off. Idempotent.
# Set PUPUS_STOP_TERMUX=0 in ~/.pupusphone.env to keep Termux open (e.g. if you use it by hand).
set -uo pipefail
ENV_FILE="${PUPUS_ENV_FILE:-$HOME/.pupusphone.env}"
if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

BRIDGE_PATTERN="packages/bridge/src/cli.ts"
CHILD_PATTERNS=("mcp-server/src/cli.ts" "--strict-mcp-config")

# SIGTERM first: the bridge stops Claude Code cleanly. Then make sure nothing is left behind.
pkill -TERM -f "$BRIDGE_PATTERN" 2>/dev/null
for _ in 1 2 3 4 5 6 7 8 9 10; do
  pgrep -f "$BRIDGE_PATTERN" >/dev/null 2>&1 || break
  sleep 0.5
done
pkill -KILL -f "$BRIDGE_PATTERN" 2>/dev/null
for pattern in "${CHILD_PATTERNS[@]}"; do
  pkill -KILL -f -- "$pattern" 2>/dev/null
done
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) [stop] bridge stopped" >> "${PUPUS_LOG_FILE:-$HOME/.pupusphone/bridge.log}"

termux-wake-unlock 2>/dev/null
if [ "${PUPUS_STOP_TERMUX:-1}" = "1" ]; then
  # Same as the "Exit" button of the Termux notification: ends every session and task.
  am startservice -a com.termux.service_stop -n com.termux/.app.TermuxService >/dev/null 2>&1
fi
exit 0
