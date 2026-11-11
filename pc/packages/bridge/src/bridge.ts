import { readFileSync, rmSync } from 'node:fs';
import { buildClaudeLaunch, claudeEnv, writeMcpConfig } from './claude-args.ts';
import { ClaudeProcess } from './claude-process.ts';
import type { BridgeConfig } from './config.ts';
import { log } from './log.ts';
import { startVoiceServer, type RunningVoiceServer } from './server.ts';
import { VoiceSession, type SpawnFn } from './session.ts';
import { startStallMonitor } from './stall-monitor.ts';
import { startIdleExit } from './idle-exit.ts';

export interface RunningBridge {
  readonly port: number;
  readonly server: RunningVoiceServer;
  readonly session: VoiceSession;
  close(): Promise<void>;
}

export interface BridgeOverrides {
  readonly env?: Readonly<Record<string, string | undefined>>;
  readonly backoffMs?: readonly number[];
  /** Called once when no phone was connected for `idleExitSeconds` (only if that is > 0). */
  readonly onIdle?: () => void;
}

function readSystemPrompt(path: string): string {
  try {
    const prompt = readFileSync(path, 'utf8').trim();
    if (prompt === '') throw new Error('file is empty');
    return prompt;
  } catch (err) {
    throw new Error(`cannot read system prompt ${path}: ${err instanceof Error ? err.message : String(err)}`);
  }
}

/** Starts the WebSocket server and the persistent Claude Code process. */
export async function startBridge(config: BridgeConfig, overrides: BridgeOverrides = {}): Promise<RunningBridge> {
  const systemPrompt = readSystemPrompt(config.systemPromptFile);
  const env = claudeEnv(config, overrides.env ?? process.env);
  // Written before every spawn, not once: the workdir lives in the temp dir by default and can
  // be wiped while the bridge runs (Termux clears $TMPDIR when its service stops), and a
  // missing cwd makes every later spawn fail with ENOENT.
  const spawn: SpawnFn = (resume, handlers) =>
    new ClaudeProcess(buildClaudeLaunch(config, writeMcpConfig(config), systemPrompt, resume), { cwd: config.workdir, env }, handlers);
  const mcpConfigPath = writeMcpConfig(config);

  const server = await startVoiceServer({ host: config.host, port: config.port, token: config.token, model: config.model });
  const session = new VoiceSession({
    model: config.model,
    spawn,
    send: server.broadcast,
    turnTimeoutMs: config.turnTimeoutMs,
    interruptTimeoutMs: config.interruptTimeoutMs,
    ...(overrides.backoffMs ? { backoffMs: overrides.backoffMs } : {}),
  });
  server.attach(session);
  session.start();
  const stopStallMonitor = startStallMonitor({
    onStall: (ms) => log.warn(`bridge process did not run for ${Math.round(ms / 1000)} s (frozen by the OS or CPU asleep)`),
  });
  const onIdle = overrides.onIdle;
  const stopIdleExit =
    config.idleExitSeconds > 0 && onIdle
      ? startIdleExit({
          idleMs: config.idleExitSeconds * 1000,
          hasClient: server.hasClient,
          onIdle: () => {
            log.info(`no phone connected for ${config.idleExitSeconds} s: exiting`);
            onIdle();
          },
        })
      : () => undefined;
  log.info(`voice bridge on ws://${config.host}:${server.port}/voice (model ${config.model})`);
  return {
    port: server.port,
    server,
    session,
    close: async () => {
      stopStallMonitor();
      stopIdleExit();
      session.stop();
      await server.close();
      rmSync(mcpConfigPath, { force: true });
    },
  };
}
