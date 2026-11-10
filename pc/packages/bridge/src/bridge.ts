import { mkdirSync, readFileSync, rmSync } from 'node:fs';
import { buildClaudeLaunch, claudeEnv, writeMcpConfig } from './claude-args.ts';
import { ClaudeProcess } from './claude-process.ts';
import type { BridgeConfig } from './config.ts';
import { log } from './log.ts';
import { startVoiceServer, type RunningVoiceServer } from './server.ts';
import { VoiceSession, type SpawnFn } from './session.ts';

export interface RunningBridge {
  readonly port: number;
  readonly server: RunningVoiceServer;
  readonly session: VoiceSession;
  close(): Promise<void>;
}

export interface BridgeOverrides {
  readonly env?: Readonly<Record<string, string | undefined>>;
  readonly backoffMs?: readonly number[];
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
  mkdirSync(config.workdir, { recursive: true });
  const mcpConfigPath = writeMcpConfig(config);
  const env = claudeEnv(config, overrides.env ?? process.env);
  const spawn: SpawnFn = (resume, handlers) =>
    new ClaudeProcess(buildClaudeLaunch(config, mcpConfigPath, systemPrompt, resume), { cwd: config.workdir, env }, handlers);

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
  log.info(`voice bridge on ws://${config.host}:${server.port}/voice (model ${config.model})`);
  return {
    port: server.port,
    server,
    session,
    close: async () => {
      session.stop();
      await server.close();
      rmSync(mcpConfigPath, { force: true });
    },
  };
}
