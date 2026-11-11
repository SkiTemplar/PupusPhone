# Phone-only mode (Termux)

The bridge, the MCP server and Claude Code run inside Termux on the phone; the app talks to
them over loopback (`127.0.0.1`). No PC and no network exposure.

## Install, step by step

You need an arm64 phone with Android 11+, about 1 GB free, and a Claude subscription or API key.

1. **Termux.** Install it from [F-Droid](https://f-droid.org/packages/com.termux/) or the
   [GitHub releases](https://github.com/termux/termux-app/releases) (`arm64-v8a` APK). The Google
   Play build is outdated and does not accept commands from other apps. Open it once and wait
   until the prompt appears.
2. **PupusPhone.** Install the APK (Releases, or build it: [android/README.md](../android/README.md))
   and grant microphone and notifications when asked.
3. **Allow restricted settings (Android 13+).** Android blocks the accessibility service of
   apps installed outside an app store. Open *Settings › Apps › PupusPhone*, tap **⋮** (top
   right) › **Allow restricted settings** and confirm. The option only appears after you have
   tried to enable the service once (step 4) and been refused; if it is missing, do step 4 first
   and come back.
4. **Accessibility.** In the app, *Ajustes › Permisos › Accesibilidad*, enable *PupusPhone*.
5. **Battery.** Set both PupusPhone and Termux to *Unrestricted* (*Settings › Apps › … ›
   Battery*). Otherwise Android stops the bridge in the background. See the brand notes below.
6. **Install command.** In the app, *Ajustes › Conexión › Configurar Termux*. It points the app
   at `ws://127.0.0.1:8766/voice`, creates the bridge token if there is none, and copies one
   command with both tokens. In Termux: long-press › *Paste* › Enter. It takes 3-10 minutes
   (it downloads Node.js, the glibc runtime and Claude Code, about 300 MB) and ends with a summary.
7. **Log in to Claude Code once.** In Termux run `claude`, sign in (it opens the browser) and
   type `/exit`.
8. **Termux permission.** Back in *Ajustes › Conexión*, tap *Conceder permiso de Termux*. The
   status line should read *Listo*.
9. **Use it.** Turn on *Modo coche*: the app asks Termux to start the bridge
   (`scripts/termux-start.sh`) and connects to it. Tap the button and talk.

To update PupusPhone or Claude Code later, paste the same command again (or run
`bash ~/PupusPhone/scripts/termux-setup.sh`), then restart the bridge:
`pkill -f packages/bridge/src/cli.ts`, and turn *Modo coche* off and on.

### Brand notes

- **Xiaomi / HyperOS / MIUI.** For both apps: *App info › Autostart* on, and *Battery saver ›
  No restrictions*. Lock Termux in the recents screen (long-press the card › lock). *USB debugging
  (security settings)* in Developer options is only needed if you install or debug over `adb`; a
  normal install does not need it.
- **Samsung / One UI.** *Settings › Battery › Background usage limits*: make sure neither app is
  in *Sleeping apps* or *Deep sleeping apps*, and add both to *Never sleeping apps*.
- **Others.** Look for "autostart", "app launch" or "background activity" and allow it for both
  apps; [dontkillmyapp.com](https://dontkillmyapp.com) lists the steps per brand.

## What the setup script does

`scripts/termux-setup.sh` checks every step and stops with a clear `ERROR:` line and a non-zero
exit code when one fails:

1. Platform: running inside Termux, CPU is `aarch64`.
2. Packages, without prompts (`DEBIAN_FRONTEND=noninteractive`, `--force-confnew`): `git`,
   `curl`, `patchelf`, `glibc-repo`, `glibc-runner`, and `nodejs-lts` unless Node.js ≥ 22.18 is
   already there. `pkg upgrade` runs first only when something is missing.
3. `allow-external-apps = true` in `~/.termux/termux.properties`, then `termux-reload-settings`.
4. Repository: clones it into `~/PupusPhone`, or fast-forwards an existing clone
   (`git pull --ff-only` semantics; local changes stop the script instead of being overwritten).
5. Claude Code: downloads the `linux-arm64` build of the latest version (skipped when already
   current), patches it and checks that `claude --version` runs.
6. `npm ci` in `pc/`.
7. `~/.pupusphone.env` (mode 600) with the tokens given on the command line; an existing file
   keeps its other settings. Tokens are never printed.

Options: `--device-token`, `--bridge-token`, `--repo-dir <dir>` (default: the clone the script
runs from, else `~/PupusPhone`), `--branch <name>` (default `main`), and `--repo-url <url>` to
clone from somewhere else: a fork, a local path or a git bundle (offline installs, or testing
unpublished changes: `git bundle create pupus.bundle main`, copy it to the phone, then
`--repo-url /path/to/pupus.bundle`). With a custom `--repo-dir`, update the script path in
*Ajustes › Conexión* to match.

The tokens are on the command line, so they end up in Termux's `~/.bash_history`, which only
Termux can read. Clear it with `history -c && rm ~/.bash_history` if you prefer.

## Claude Code on Android

npm has no Android build of Claude Code (`@anthropic-ai/claude-code-linux-arm64-android` returns
404), and the generic package refuses to start ("native binary not installed"). What works:

1. Take the official `@anthropic-ai/claude-code-linux-arm64` binary (glibc).
2. Install Termux's glibc runtime (`pkg install glibc-repo && pkg install glibc-runner`).
3. `patchelf --set-interpreter $PREFIX/glibc/lib/ld-linux-aarch64.so.1 --set-rpath $PREFIX/glibc/lib`.
4. Start it **without** `LD_PRELOAD` (termux-exec). termux-exec routes every `exec` through the
   bionic linker, which rejects the non-PIE binary with `unexpected e_type: 2`. The wrapper in
   `$PREFIX/bin/claude` does `env -u LD_PRELOAD bash -c 'exec claude "$@"'`.

Running the binary through the loader (`ld-linux-aarch64.so.1 ./claude` or `grun`) fails with
`Could not find a PHDR`: the binary is a Bun standalone executable that reads its payload from
`/proc/self/exe`, which then points at the loader.

## Running by hand

```bash
bash ~/PupusPhone/scripts/termux-start.sh   # idempotent; log in ~/.pupusphone/bridge.log
```

`termux-wake-lock` is taken automatically.

## Stopping

- **Car mode off.** The app runs `scripts/termux-stop.sh`: it stops the bridge, Claude Code and the
  MCP server, releases the wake lock and closes the Termux service (like the *Exit* button of its
  notification). Set `PUPUS_STOP_TERMUX=0` in `~/.pupusphone.env` to keep Termux open if you also
  use it by hand.
- **App killed** (swiped away, HyperOS cleaner). The app cannot say anything, so the bridge exits
  on its own after `PUPUS_IDLE_EXIT_SECONDS` (default 180) without a phone connected, and the same
  cleanup runs. Idle time is counted in 10 s ticks, so a frozen process does not count as idle.

The bridge log records every disconnection (`phone disconnected (code …) after N s`) and every time
the process did not run for 15 s or more (`bridge process did not run for N s`): the latter means
Android froze Termux or let the CPU sleep.

## Troubleshooting

| Symptom | Fix |
|---|---|
| App: *Termux no acepta comandos externos* | Termux from Google Play: reinstall it from F-Droid or GitHub. |
| Termux notification "allow-external-apps" | Re-run the setup command, or close Termux fully and reopen it. |
| Accessibility switch greyed out | Step 3: *Allow restricted settings*. |
| `ERROR: cannot fast-forward ~/PupusPhone` | You edited files there: `mv ~/PupusPhone ~/PupusPhone.old` and re-run. |
| Bridge stops after a while | Battery *Unrestricted* and the brand notes above. |
| Bridge keeps disconnecting, log shows `did not run for N s` | HyperOS freezes Termux: *App info › Battery saver › No restrictions* and *Autostart* for Termux. Check with `adb shell dumpsys power | grep termux`: the wake lock must not say `DISABLED`. |
| Accessibility dies after swiping the app away (Xiaomi) | See *HyperOS kills the app when swiped from recents* below. |
| Voice says it cannot reach the phone | Device token changed in the app: use *Configurar Termux* again. |

### HyperOS kills the app when swiped from recents

On Xiaomi/HyperOS (seen on a Xiaomi 15T, HyperOS 3) swiping PupusPhone away from recents kills its
process (`SwipeUpClean`, `ApplicationExitInfo` reason 13). Android then lists the accessibility
service under *Crashed services* and never rebinds it: the device API (`:8765`) and the floating
button die silently until the service is switched off and on. Termux (the bridge) can be killed the
same way.

- **Self-heal, one-time grant.** With the phone on USB debugging, run once on the PC:
  `adb shell pm grant com.pupus.phone android.permission.WRITE_SECURE_SETTINGS`.
  The app then rewrites `ENABLED_ACCESSIBILITY_SERVICES` (without its service, then with it,
  keeping any other services) to force the rebind. It checks on process start, when the app is
  opened, when car mode starts, after boot and after an update (at most once every 10 s).
  The grant survives reboots and updates; it is lost if the app is uninstalled.
- **Bridge watchdog.** In car mode with a loopback bridge URL, if the WebSocket has been down for
  more than 15 s the app asks Termux to run `termux-start.sh` again (idempotent), at most once a
  minute.
- **Lock both apps in recents.** Recents → long-press the PupusPhone card → padlock; same for
  Termux. Locked apps are not cleaned by the swipe.
- **Autostart.** Settings → Apps → PupusPhone / Termux → *Autostart* on, and battery *No
  restrictions*.
- **Check.** App settings → *Estado* shows accessibility (active / down — repaired / down — no
  permission), device API, bridge, Termux, the grant (with the command to copy), battery
  optimisation and a *Reparar ahora* button.

## Known limitations

- Vitest workers crash under Termux; run the test suite on a PC.
- The bridge lives in a Termux session: if you swipe Termux away, it stops.
