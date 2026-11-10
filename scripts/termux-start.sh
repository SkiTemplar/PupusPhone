#!/data/data/com.termux/files/usr/bin/bash
# Starts the PupusPhone voice bridge inside Termux (no PC needed).
set -euo pipefail
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${PUPUS_ENV_FILE:-$HOME/.pupusphone.env}"

[ -f "$ENV_FILE" ] || { echo "Missing $ENV_FILE: run scripts/termux-setup.sh first" >&2; exit 1; }
termux-wake-lock 2>/dev/null || echo "termux-wake-lock unavailable; keep Termux unrestricted in battery settings" >&2
set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a
cd "$REPO_DIR/pc"
exec node packages/bridge/src/cli.ts
