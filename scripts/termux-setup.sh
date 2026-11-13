#!/data/data/com.termux/files/usr/bin/bash
# Installs (or updates) everything PupusPhone needs to run without a PC, inside Termux on Android
# (arm64): Node.js, Claude Code (official linux-arm64 build with a patched ELF interpreter), this
# repository and the bridge dependencies. Safe to re-run: that is also the update path after a
# Claude Code or PupusPhone update.
#
# From the app (Ajustes > Conexión > Configurar Termux), which fills in the tokens:
#   curl -fsSL https://raw.githubusercontent.com/SkiTemplar/PupusPhone/main/scripts/termux-setup.sh \
#     | bash -s -- --device-token <64 hex> --bridge-token <token>
# From a clone:
#   bash scripts/termux-setup.sh [options]
#
# Options:
#   --device-token <hex>  device API token shown in the app (64 hex chars)
#   --bridge-token <tok>  shared bridge token configured in the app (>= 16 chars, [A-Za-z0-9._-])
#   --repo-dir <dir>      where the repository lives (default: this clone, else ~/PupusPhone)
#   --branch <name>       branch to clone or fast-forward (default: main)
#   --repo-url <url>      clone source (default: GitHub). Accepts a local path or a git bundle,
#                         for offline installs and testing unpublished changes.
#
# The whole script is wrapped in main() so bash parses it completely before running anything:
# a truncated download does nothing, and a `git pull` that rewrites this file cannot affect the run.
set -euo pipefail

TERMUX_PREFIX=/data/data/com.termux/files/usr
DEFAULT_REPO_URL=https://github.com/SkiTemplar/PupusPhone
CLAUDE_PACKAGE=@anthropic-ai/claude-code
CLAUDE_DIR="$TERMUX_PREFIX/opt/claude"
GLIBC_LIB="$TERMUX_PREFIX/glibc/lib"
GLIBC_LOADER="$GLIBC_LIB/ld-linux-aarch64.so.1"
ENV_FILE="$HOME/.pupusphone.env"
PROPERTIES_FILE="$HOME/.termux/termux.properties"
MIN_NODE_MAJOR=22
MIN_NODE_MINOR=18

DEVICE_TOKEN=""
BRIDGE_TOKEN=""
REPO_DIR=""
BRANCH=main
REPO_URL=""
GENERATED_BRIDGE_TOKEN=false
WORK_DIR=""

step() { printf '\n== %s\n' "$1"; }
ok() { printf '   ok: %s\n' "$1"; }
warn() { printf '   warning: %s\n' "$1" >&2; }
die() { printf '\nERROR: %s\n' "$1" >&2; exit 1; }

cleanup() { if [ -n "$WORK_DIR" ]; then rm -rf "$WORK_DIR"; fi; }

usage() { sed -n '2,20p' "${BASH_SOURCE[0]:-/dev/null}" 2>/dev/null | sed 's/^# \{0,1\}//' || true; }

parse_args() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --device-token) DEVICE_TOKEN="${2:-}"; shift 2 || die "--device-token needs a value" ;;
      --bridge-token) BRIDGE_TOKEN="${2:-}"; shift 2 || die "--bridge-token needs a value" ;;
      --repo-dir) REPO_DIR="${2:-}"; shift 2 || die "--repo-dir needs a value" ;;
      --branch) BRANCH="${2:-}"; shift 2 || die "--branch needs a value" ;;
      --repo-url) REPO_URL="${2:-}"; shift 2 || die "--repo-url needs a value" ;;
      -h | --help) usage; exit 0 ;;
      *) die "Unknown option: $1 (see --help)" ;;
    esac
  done
  if [ -n "$DEVICE_TOKEN" ] && ! [[ "$DEVICE_TOKEN" =~ ^[0-9a-f]{64}$ ]]; then
    die "--device-token must be the 64 hex characters shown in the app (Ajustes > Conexión)"
  fi
  if [ -n "$BRIDGE_TOKEN" ] && ! [[ "$BRIDGE_TOKEN" =~ ^[A-Za-z0-9._-]{16,}$ ]]; then
    die "--bridge-token must be at least 16 characters of [A-Za-z0-9._-]"
  fi
  [ -n "$BRANCH" ] || die "--branch cannot be empty"
  if [ -z "$REPO_DIR" ]; then REPO_DIR="$(default_repo_dir)"; fi
}

# The clone this script was started from, or ~/PupusPhone when it was piped from curl.
default_repo_dir() {
  local self="${BASH_SOURCE[0]:-}"
  if [ -n "$self" ] && [ -f "$self" ]; then
    local root
    root="$(cd "$(dirname "$self")/.." && pwd)"
    if [ -d "$root/.git" ]; then printf '%s\n' "$root"; return; fi
  fi
  printf '%s\n' "$HOME/PupusPhone"
}

check_platform() {
  step "Platform"
  [ "${PREFIX:-}" = "$TERMUX_PREFIX" ] && [ -d "$TERMUX_PREFIX" ] \
    || die "Run this inside the Termux app (com.termux from F-Droid or GitHub, not Google Play)"
  local arch
  arch="$(uname -m)"
  [ "$arch" = aarch64 ] || die "Claude Code needs a 64-bit ARM phone (aarch64); this one is $arch"
  ok "Termux on $arch"
}

# dpkg -s also succeeds for removed packages whose config files remain, so check the status.
is_installed() { dpkg-query -W -f='${Status}' "$1" 2>/dev/null | grep -q 'install ok installed'; }

node_is_recent() {
  command -v node >/dev/null 2>&1 || return 1
  node -e "const [a, b] = process.versions.node.split('.').map(Number);
    process.exit(a > $MIN_NODE_MAJOR || (a === $MIN_NODE_MAJOR && b >= $MIN_NODE_MINOR) ? 0 : 1)"
}

pkg_noninteractive() {
  pkg "$1" -y -o Dpkg::Options::=--force-confnew "${@:2}"
}

install_packages() {
  step "Packages (Node.js, git, curl, patchelf, glibc runtime)"
  export DEBIAN_FRONTEND=noninteractive
  local missing=() name
  for name in git curl patchelf glibc-repo; do
    is_installed "$name" || missing+=("$name")
  done
  # Any node >= 22.18 will do (it runs the bridge's .ts files directly); nodejs and nodejs-lts conflict.
  node_is_recent || missing+=(nodejs-lts)
  if [ ${#missing[@]} -gt 0 ]; then
    echo "   installing: ${missing[*]}"
    # Termux is a rolling distribution: upgrade first so new packages match the installed libraries.
    pkg_noninteractive upgrade || die "pkg upgrade failed; check the network and run: pkg upgrade"
    pkg_noninteractive install "${missing[@]}" || die "pkg install ${missing[*]} failed"
  fi
  if ! is_installed glibc-runner; then
    # glibc-repo adds a new source list; refresh it before installing from it.
    apt-get update || die "apt-get update failed after adding the glibc repository"
    pkg_noninteractive install glibc-runner || die "pkg install glibc-runner failed"
  fi
  node_is_recent || die "Node.js >= $MIN_NODE_MAJOR.$MIN_NODE_MINOR is required; found $(node --version 2>/dev/null || echo none)"
  command -v npm >/dev/null 2>&1 || die "npm is missing; reinstall it with: pkg install nodejs-lts"
  [ -f "$GLIBC_LOADER" ] || die "glibc loader not found at $GLIBC_LOADER; reinstall with: pkg install glibc-runner"
  ok "node $(node --version), npm $(npm --version), glibc loader present"
}

enable_external_apps() {
  step "Allow the app to start the bridge (allow-external-apps)"
  mkdir -p "$(dirname "$PROPERTIES_FILE")"
  touch "$PROPERTIES_FILE"
  if grep -Eq '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true[[:space:]]*$' "$PROPERTIES_FILE"; then
    ok "already enabled in $PROPERTIES_FILE"
    return
  fi
  if grep -Eq '^[[:space:]]*allow-external-apps[[:space:]]*=' "$PROPERTIES_FILE"; then
    sed -i -E 's/^[[:space:]]*allow-external-apps[[:space:]]*=.*/allow-external-apps = true/' "$PROPERTIES_FILE"
  else
    printf '\nallow-external-apps = true\n' >> "$PROPERTIES_FILE"
  fi
  grep -Eq '^allow-external-apps = true$' "$PROPERTIES_FILE" || die "could not write allow-external-apps to $PROPERTIES_FILE"
  if command -v termux-reload-settings >/dev/null 2>&1 && timeout 15 termux-reload-settings >/dev/null 2>&1; then
    ok "enabled and reloaded"
  else
    warn "enabled, but termux-reload-settings failed: close Termux completely and open it again"
  fi
}

sync_repo() {
  step "PupusPhone repository ($REPO_DIR, branch $BRANCH)"
  if [ -d "$REPO_DIR/.git" ]; then
    local source="${REPO_URL:-origin}"
    git -C "$REPO_DIR" config core.fileMode false
    git -C "$REPO_DIR" fetch --quiet "$source" "$BRANCH" || die "git fetch $source $BRANCH failed (network?)"
    if [ "$(git -C "$REPO_DIR" rev-parse --abbrev-ref HEAD)" != "$BRANCH" ]; then
      git -C "$REPO_DIR" checkout --quiet "$BRANCH" 2>/dev/null \
        || git -C "$REPO_DIR" checkout --quiet -b "$BRANCH" FETCH_HEAD \
        || die "cannot switch $REPO_DIR to $BRANCH: commit or discard local changes (git -C $REPO_DIR status)"
    fi
    git -C "$REPO_DIR" merge --quiet --ff-only FETCH_HEAD \
      || die "cannot fast-forward $REPO_DIR: it has local changes or commits (git -C $REPO_DIR status); move it away and re-run"
    ok "updated to $(git -C "$REPO_DIR" log -1 --format='%h %s')"
  elif [ -e "$REPO_DIR" ] && [ -n "$(ls -A "$REPO_DIR" 2>/dev/null)" ]; then
    die "$REPO_DIR exists but is not a git clone; move it away or pass --repo-dir <dir>"
  else
    git clone --quiet --branch "$BRANCH" "${REPO_URL:-$DEFAULT_REPO_URL}" "$REPO_DIR" \
      || die "git clone ${REPO_URL:-$DEFAULT_REPO_URL} failed (network? branch $BRANCH exists?)"
    ok "cloned $(git -C "$REPO_DIR" log -1 --format='%h %s')"
  fi
  # npm ci marks the workspace CLIs executable; ignore mode bits so the clone stays fast-forwardable.
  git -C "$REPO_DIR" config core.fileMode false
  local start="$REPO_DIR/scripts/termux-start.sh"
  [ -f "$start" ] || die "$start is missing: is $REPO_DIR a PupusPhone clone?"
  # Termux RUN_COMMAND executes the file directly, so it must be executable.
  [ -x "$start" ] || chmod 755 "$start"
}

installed_claude_version() {
  "$TERMUX_PREFIX/bin/claude" --version 2>/dev/null | awk 'NR == 1 { print $1 }'
}

write_claude_wrapper() {
  # The binary must be started without termux-exec (LD_PRELOAD), which routes every exec through
  # the bionic linker and rejects non-PIE executables.
  cat > "$TERMUX_PREFIX/bin/claude" <<'WRAPPER'
#!/data/data/com.termux/files/usr/bin/sh
exec env -u LD_PRELOAD /data/data/com.termux/files/usr/bin/bash -c 'exec "$0" "$@"' /data/data/com.termux/files/usr/opt/claude/claude "$@"
WRAPPER
  chmod 755 "$TERMUX_PREFIX/bin/claude"
}

install_claude() {
  step "Claude Code (linux-arm64 build, patched for Termux's glibc)"
  local version current
  version="$(npm view "$CLAUDE_PACKAGE" version 2>/dev/null)" || die "cannot reach the npm registry to look up $CLAUDE_PACKAGE"
  [ -n "$version" ] || die "npm returned no version for $CLAUDE_PACKAGE"
  write_claude_wrapper
  current="$(installed_claude_version || true)"
  if [ -x "$CLAUDE_DIR/claude" ] && [ "$current" = "$version" ]; then
    ok "Claude Code $version already installed"
    return
  fi
  echo "   downloading $CLAUDE_PACKAGE-linux-arm64@$version (${current:-not installed} before)"
  WORK_DIR="$(mktemp -d)"
  (cd "$WORK_DIR" && npm pack --silent "$CLAUDE_PACKAGE-linux-arm64@$version" >/dev/null && tar xzf ./*.tgz) \
    || die "download of $CLAUDE_PACKAGE-linux-arm64@$version failed"
  [ -f "$WORK_DIR/package/claude" ] || die "the linux-arm64 package has no 'claude' binary; its layout changed"
  mkdir -p "$CLAUDE_DIR"
  install -m 755 "$WORK_DIR/package/claude" "$CLAUDE_DIR/claude.new"
  patchelf --set-interpreter "$GLIBC_LOADER" --set-rpath "$GLIBC_LIB" "$CLAUDE_DIR/claude.new" \
    || die "patchelf failed on the Claude Code binary"
  mv -f "$CLAUDE_DIR/claude.new" "$CLAUDE_DIR/claude"
  rm -rf "$WORK_DIR"
  WORK_DIR=""
  current="$(installed_claude_version || true)"
  [ "$current" = "$version" ] \
    || die "the patched Claude Code does not start (claude --version gave '${current:-nothing}'); see docs/termux.md"
  ok "Claude Code $version"
}

install_bridge_deps() {
  step "Bridge dependencies (npm ci)"
  (cd "$REPO_DIR/pc" && npm ci --no-audit --no-fund --loglevel=error) \
    || die "npm ci failed in $REPO_DIR/pc; re-run this script, or run npm ci there to see the error"
  ok "installed"
}

# Replaces KEY=... in the env file or appends it. Values are validated tokens or fixed paths.
set_env_value() {
  local key="$1" value="$2"
  if grep -q "^$key=" "$ENV_FILE"; then
    sed -i "s|^$key=.*|$key=$value|" "$ENV_FILE"
  else
    printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
  fi
}

write_env() {
  step "Configuration ($ENV_FILE)"
  umask 077
  if [ ! -f "$ENV_FILE" ]; then
    if [ -z "$BRIDGE_TOKEN" ]; then
      BRIDGE_TOKEN="$(node -e "console.log(require('crypto').randomBytes(24).toString('hex'))")"
      GENERATED_BRIDGE_TOKEN=true
    fi
    cat > "$ENV_FILE" <<EOF
PUPUS_PHONE_URL=http://127.0.0.1:8765
# Device token shown in the PupusPhone app (Ajustes > Conexión).
PUPUS_PHONE_TOKEN=$DEVICE_TOKEN
PUPUS_BRIDGE_TOKEN=$BRIDGE_TOKEN
PUPUS_BRIDGE_HOST=127.0.0.1
PUPUS_BRIDGE_PORT=8766
PUPUS_MODEL=claude-haiku-5-5
PUPUS_EFFORT=low
PUPUS_CLAUDE_BIN=$TERMUX_PREFIX/bin/claude
EOF
  else
    if [ -n "$DEVICE_TOKEN" ]; then set_env_value PUPUS_PHONE_TOKEN "$DEVICE_TOKEN"; fi
    if [ -n "$BRIDGE_TOKEN" ]; then set_env_value PUPUS_BRIDGE_TOKEN "$BRIDGE_TOKEN"; fi
    set_env_value PUPUS_CLAUDE_BIN "$TERMUX_PREFIX/bin/claude"
  fi
  chmod 600 "$ENV_FILE"
  grep -Eq '^PUPUS_PHONE_TOKEN=[0-9a-f]{64}$' "$ENV_FILE" \
    || warn "no device token yet: use «Configurar Termux» in the app, or re-run with --device-token"
  grep -Eq '^PUPUS_BRIDGE_TOKEN=.{16,}$' "$ENV_FILE" || die "$ENV_FILE has no valid PUPUS_BRIDGE_TOKEN"
  ok "written (mode 600; tokens not shown)"
}

summary() {
  step "Done"
  local n=1
  if [ ! -f "$HOME/.claude/.credentials.json" ] && [ -z "${ANTHROPIC_API_KEY:-}" ]; then
    echo "$n. Log in to Claude Code once: run  claude  , sign in, then type /exit"
    n=$((n + 1))
  fi
  if $GENERATED_BRIDGE_TOKEN; then
    echo "$n. A bridge token was generated. Copy it into the app (see: grep BRIDGE_TOKEN ~/.pupusphone.env),"
    echo "   or simply use «Configurar Termux» in the app, which sets both tokens for you."
    n=$((n + 1))
  fi
  if [ "$REPO_DIR" != "$HOME/PupusPhone" ]; then
    echo "$n. In the app, set «Script de arranque en Termux» to $REPO_DIR/scripts/termux-start.sh"
    n=$((n + 1))
  fi
  echo "$n. In the app: grant the Termux permission (Ajustes > Conexión) and turn on Modo coche;"
  echo "   it starts the bridge for you. By hand: bash $REPO_DIR/scripts/termux-start.sh"
  if pgrep -f 'packages/bridge/src/cli.ts' >/dev/null 2>&1; then
    echo "   A bridge is already running with the old code; restart it to apply this update:"
    echo "   pkill -f packages/bridge/src/cli.ts; bash $REPO_DIR/scripts/termux-start.sh"
  fi
  echo "Battery: set Termux and PupusPhone to Unrestricted, or Android will stop the bridge."
}

main() {
  parse_args "$@"
  trap cleanup EXIT
  check_platform
  install_packages
  enable_external_apps
  sync_repo
  install_claude
  install_bridge_deps
  write_env
  summary
}

main "$@"
