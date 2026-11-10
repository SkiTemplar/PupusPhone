#!/data/data/com.termux/files/usr/bin/bash
# Installs everything PupusPhone needs to run without a PC, inside Termux on Android (arm64):
# Node.js, Claude Code (official linux-arm64 build with a patched ELF interpreter) and the bridge.
# Run it from Termux: bash scripts/termux-setup.sh   (from a clone of this repository)
set -euo pipefail

CLAUDE_DIR="$PREFIX/opt/claude"
GLIBC_LIB="$PREFIX/glibc/lib"
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"

step() { printf '\n== %s\n' "$1"; }

step "Packages (nodejs-lts, glibc runtime, patchelf)"
pkg update -y
pkg install -y nodejs-lts git patchelf glibc-repo
pkg install -y glibc-runner

step "Claude Code (linux-arm64 build)"
# There is no Android build on npm; the glibc linux-arm64 binary runs once its interpreter and
# rpath point at Termux's glibc. It must be started without termux-exec (LD_PRELOAD), which
# routes every exec through the bionic linker and rejects non-PIE executables.
VERSION="$(npm view @anthropic-ai/claude-code version)"
WORK="$(mktemp -d)"
(cd "$WORK" && npm pack "@anthropic-ai/claude-code-linux-arm64@$VERSION" >/dev/null && tar xzf ./*.tgz)
mkdir -p "$CLAUDE_DIR"
install -m 755 "$WORK/package/claude" "$CLAUDE_DIR/claude"
rm -rf "$WORK"
patchelf --set-interpreter "$GLIBC_LIB/ld-linux-aarch64.so.1" --set-rpath "$GLIBC_LIB" "$CLAUDE_DIR/claude"

cat > "$PREFIX/bin/claude" <<'WRAPPER'
#!/data/data/com.termux/files/usr/bin/sh
exec env -u LD_PRELOAD /data/data/com.termux/files/usr/bin/bash -c 'exec "$0" "$@"' /data/data/com.termux/files/usr/opt/claude/claude "$@"
WRAPPER
chmod 755 "$PREFIX/bin/claude"
claude --version

step "Bridge dependencies"
(cd "$REPO_DIR/pc" && npm ci --no-audit --no-fund)

ENV_FILE="$HOME/.pupusphone.env"
if [ ! -f "$ENV_FILE" ]; then
  step "Config ($ENV_FILE)"
  BRIDGE_TOKEN="$(node -e "console.log(require('crypto').randomBytes(24).toString('hex'))")"
  cat > "$ENV_FILE" <<EOF
PUPUS_PHONE_URL=http://127.0.0.1:8765
# Device token shown in the PupusPhone app (Ajustes > Conexión).
PUPUS_PHONE_TOKEN=
PUPUS_BRIDGE_TOKEN=$BRIDGE_TOKEN
PUPUS_BRIDGE_HOST=127.0.0.1
PUPUS_BRIDGE_PORT=8766
PUPUS_MODEL=claude-sonnet-5-5
PUPUS_EFFORT=low
PUPUS_CLAUDE_BIN=$PREFIX/bin/claude
EOF
  chmod 600 "$ENV_FILE"
  echo "Paste the device token into $ENV_FILE and use this bridge token in the app: $BRIDGE_TOKEN"
fi

step "Done"
echo "1. Log in once:   claude   (then /exit)"
echo "2. Start bridge:  bash $REPO_DIR/scripts/termux-start.sh"
echo "3. In the app:    bridge URL ws://127.0.0.1:8766/voice and the bridge token above"
