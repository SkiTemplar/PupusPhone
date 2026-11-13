import { tmpdir } from 'node:os';
import { defaultMemoryDir } from 'pupusphone-mcp/memory';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

export interface BridgeConfig {
  readonly host: string;
  readonly port: number;
  readonly token: string;
  readonly model: string;
  readonly effort: string | undefined;
  /** Executable for Claude Code; a .js/.mjs/.ts path is run with the current Node binary. */
  readonly claudeBin: string;
  /** Empty working directory for the Claude process (no CLAUDE.md, no project settings). */
  readonly workdir: string;
  readonly phoneUrl: string;
  readonly phoneToken: string;
  /** Entry point of the MCP server spawned by Claude Code. */
  readonly mcpEntry: string;
  readonly systemPromptFile: string;
  /** Memory facts (remember/forget); undefined disables memory. Default ~/.pupusphone/memory.json. */
  readonly memoryFile: string | undefined;
  /** Hand-written user profile appended to the prompt; undefined disables it. Default ~/.pupusphone/profile.md. */
  readonly profileFile: string | undefined;
  readonly turnTimeoutMs: number;
  readonly interruptTimeoutMs: number;
  /** Seconds without any phone connected before the bridge exits; 0 keeps it running (PC default). */
  readonly idleExitSeconds: number;
  /** Extra environment variable names passed through to the Claude process. */
  readonly envPassthrough: readonly string[];
}

export class BridgeConfigError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'BridgeConfigError';
  }
}

type Env = Readonly<Record<string, string | undefined>>;

const MODEL_RE = /^[a-z0-9][a-z0-9.\-[\]]*$/i;
const EFFORTS = ['low', 'medium', 'high', 'xhigh', 'max'];

function int(env: Env, key: string, fallback: number, min: number, max: number): number {
  const raw = env[key];
  if (raw === undefined || raw === '') return fallback;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < min || n > max) throw new BridgeConfigError(`${key} must be an integer in [${min}, ${max}]`);
  return n;
}

function required(env: Env, key: string, hint: string): string {
  const value = env[key];
  if (!value) throw new BridgeConfigError(`${key} is not set (${hint})`);
  return value;
}

/** Unset → default path; empty string → disabled; anything else → that path. */
function optionalPath(env: Env, key: string, fallback: string): string | undefined {
  const value = env[key];
  if (value === undefined) return fallback;
  return value === '' ? undefined : value;
}

function defaultPath(relative: string): string {
  return fileURLToPath(new URL(relative, import.meta.url));
}

/** Reads and validates the bridge configuration from environment variables. */
export function loadBridgeConfig(env: Env = process.env): BridgeConfig {
  const token = required(env, 'PUPUS_BRIDGE_TOKEN', 'shared secret also configured in the app');
  if (token.length < 16) throw new BridgeConfigError('PUPUS_BRIDGE_TOKEN must be at least 16 characters');
  const model = env['PUPUS_MODEL'] || 'claude-sonnet-5-5';
  if (!MODEL_RE.test(model)) throw new BridgeConfigError(`PUPUS_MODEL has invalid characters: "${model}"`);
  const effort = env['PUPUS_EFFORT'] === '' ? undefined : (env['PUPUS_EFFORT'] ?? 'low');
  if (effort !== undefined && !EFFORTS.includes(effort)) throw new BridgeConfigError(`PUPUS_EFFORT must be one of ${EFFORTS.join('|')}`);
  return {
    host: env['PUPUS_BRIDGE_HOST'] || '127.0.0.1',
    port: int(env, 'PUPUS_BRIDGE_PORT', 8766, 0, 65535),
    token,
    model,
    effort,
    claudeBin: env['PUPUS_CLAUDE_BIN'] || 'claude',
    workdir: env['PUPUS_BRIDGE_WORKDIR'] || join(tmpdir(), 'pupusphone-bridge'),
    phoneUrl: required(env, 'PUPUS_PHONE_URL', 'Device API URL, e.g. http://<phone-tailscale-ip>:8765'),
    phoneToken: required(env, 'PUPUS_PHONE_TOKEN', 'token shown in the PupusPhone app settings'),
    mcpEntry: env['PUPUS_MCP_ENTRY'] || defaultPath('../../mcp-server/src/cli.ts'),
    systemPromptFile: env['PUPUS_SYSTEM_PROMPT_FILE'] || defaultPath('../prompts/voice-system-prompt.md'),
    memoryFile: optionalPath(env, 'PUPUS_MEMORY_FILE', join(defaultMemoryDir(), 'memory.json')),
    profileFile: optionalPath(env, 'PUPUS_PROFILE_FILE', join(defaultMemoryDir(), 'profile.md')),
    turnTimeoutMs: int(env, 'PUPUS_TURN_TIMEOUT_MS', 120_000, 1000, 600_000),
    interruptTimeoutMs: int(env, 'PUPUS_INTERRUPT_TIMEOUT_MS', 3000, 100, 60_000),
    idleExitSeconds: int(env, 'PUPUS_IDLE_EXIT_SECONDS', 0, 0, 86_400),
    envPassthrough: (env['PUPUS_CLAUDE_ENV_PASSTHROUGH'] ?? '').split(',').map((s) => s.trim()).filter(Boolean),
  };
}
