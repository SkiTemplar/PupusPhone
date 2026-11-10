# Phone-only mode (Termux)

The bridge, the MCP server and Claude Code run inside Termux on the phone; the app talks to
them over loopback (`127.0.0.1`). No PC and no network exposure.

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

`scripts/termux-setup.sh` automates all of it. Re-run it after a Claude Code update.

## Running

```bash
bash scripts/termux-setup.sh      # once
claude                            # once: log in, then /exit
bash scripts/termux-start.sh      # every time; keep the Termux session open
```

Set Termux and PupusPhone to *Unrestricted* battery usage, or the system will kill the bridge in
the background. `termux-wake-lock` is taken automatically.

## Known limitations

- Vitest workers crash under Termux; run the test suite on a PC.
- The bridge lives in a Termux session: if you swipe Termux away, it stops.
