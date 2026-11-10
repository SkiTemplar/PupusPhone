import { chmodSync, mkdirSync, writeFileSync } from 'node:fs';
import { basename, join, posix } from 'node:path';
import type { BridgeConfig } from './config.ts';

/** Name of the MCP server inside Claude Code; tools become `mcp__phone__<tool>`. */
export const MCP_SERVER_NAME = 'phone';

/**
 * Environment variables passed to Claude Code. Everything else is dropped: the user's shell exports
 * variables that inject plugins/hooks (e.g. ULTRON inline plugins) and must not leak into the assistant.
 * Windows needs the system variables below to locate executables, the user profile and temp dirs.
 */
const BASE_ENV = [
  'PATH', 'Path', 'PATHEXT', 'SYSTEMROOT', 'SystemRoot', 'SYSTEMDRIVE', 'WINDIR', 'COMSPEC', 'TEMP', 'TMP', 'TMPDIR',
  'HOME', 'USERPROFILE', 'HOMEDRIVE', 'HOMEPATH', 'APPDATA', 'LOCALAPPDATA', 'PROGRAMDATA', 'PROGRAMFILES',
  'USERNAME', 'USER', 'LOGNAME', 'LANG', 'LC_ALL', 'TERM', 'SHELL', 'PREFIX', 'ANDROID_ROOT', 'ANDROID_DATA',
  'HTTPS_PROXY', 'HTTP_PROXY', 'NO_PROXY', 'https_proxy', 'http_proxy', 'no_proxy', 'NODE_EXTRA_CA_CERTS',
  // Explicit credentials, used instead of the stored OAuth login when present (headless hosts, Termux).
  'CLAUDE_CODE_OAUTH_TOKEN', 'ANTHROPIC_API_KEY',
];

export function claudeEnv(config: BridgeConfig, source: Readonly<Record<string, string | undefined>> = process.env): Record<string, string> {
  const env: Record<string, string> = {};
  const seen = new Set<string>();
  for (const key of [...BASE_ENV, ...config.envPassthrough]) {
    // Windows env lookups are case-insensitive: avoid passing PATH and Path twice.
    const folded = key.toUpperCase();
    const value = source[key];
    if (value === undefined || seen.has(folded)) continue;
    seen.add(folded);
    env[key] = value;
  }
  // Keep the child quiet and self-contained.
  env['DISABLE_AUTOUPDATER'] = '1';
  env['CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC'] = '1';
  return env;
}

/**
 * Path of the Node.js executable for child processes. Under Termux, termux-exec launches Node
 * through the Android dynamic linker, so resolveNodeBin() is `/apex/.../linker64` instead of node.
 */
export function resolveNodeBin(
  execPath: string = process.execPath,
  env: Readonly<Record<string, string | undefined>> = process.env,
): string {
  if (env['PUPUS_NODE_BIN']) return env['PUPUS_NODE_BIN'];
  if (!/^linker(64)?$/.test(basename(execPath))) return execPath;
  return env['PREFIX'] ? posix.join(env['PREFIX'], 'bin', 'node') : 'node';
}

/** Writes the MCP config (contains the phone token) into the private workdir and returns its path. */
export function writeMcpConfig(config: BridgeConfig): string {
  mkdirSync(config.workdir, { recursive: true });
  const path = join(config.workdir, 'mcp-config.json');
  const mcp = {
    mcpServers: {
      [MCP_SERVER_NAME]: {
        type: 'stdio',
        command: resolveNodeBin(),
        args: [config.mcpEntry],
        env: { PUPUS_PHONE_URL: config.phoneUrl, PUPUS_PHONE_TOKEN: config.phoneToken, PUPUS_LOG_LEVEL: 'warn' },
      },
    },
  };
  writeFileSync(path, JSON.stringify(mcp, null, 2), { encoding: 'utf8', mode: 0o600 });
  chmodSync(path, 0o600);
  return path;
}

export interface ClaudeLaunch {
  readonly command: string;
  readonly args: readonly string[];
}

/**
 * Builds the persistent Claude Code command line. Isolation from the user's own setup:
 * - `--setting-sources ""`: no user/project/local settings → no hooks, enabled plugins, permissions or env.
 * - `--strict-mcp-config --mcp-config <file>`: only the phone MCP server.
 * - `--tools ""`: no built-in tools (Bash, Read, Edit, Task…); `--allowedTools mcp__phone` pre-approves the phone.
 * - `--permission-mode dontAsk`: anything not pre-approved is denied instead of prompting.
 * - `--disable-slash-commands`: no skills or custom commands.
 * - `--system-prompt`: replaces the coding-agent prompt with the voice prompt (shorter, faster).
 * - cwd is a dedicated empty workdir; with no setting sources, CLAUDE.md files are not loaded.
 */
export function buildClaudeLaunch(config: BridgeConfig, mcpConfigPath: string, systemPrompt: string, resumeSessionId?: string): ClaudeLaunch {
  const args = [
    '-p',
    '--input-format', 'stream-json',
    '--output-format', 'stream-json',
    '--verbose',
    '--include-partial-messages',
    '--model', config.model,
    ...(config.effort ? ['--effort', config.effort] : []),
    '--setting-sources', '',
    '--strict-mcp-config',
    '--mcp-config', mcpConfigPath,
    '--tools', '',
    '--allowedTools', `mcp__${MCP_SERVER_NAME}`,
    '--permission-mode', 'dontAsk',
    '--disable-slash-commands',
    '--system-prompt', systemPrompt,
    ...(resumeSessionId ? ['--resume', resumeSessionId] : []),
  ];
  if (/\.(c|m)?(j|t)s$/i.test(config.claudeBin)) return { command: resolveNodeBin(), args: [config.claudeBin, ...args] };
  return { command: config.claudeBin, args };
}
