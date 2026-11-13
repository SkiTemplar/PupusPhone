import { describe, expect, it } from 'vitest';
import { buildClaudeLaunch, claudeEnv } from '../src/claude-args.ts';
import { BridgeConfigError, loadBridgeConfig } from '../src/config.ts';
import { parseClientFrame } from '../src/protocol.ts';
import { tokenEquals } from '../src/server.ts';

const BASE = {
  PUPUS_BRIDGE_TOKEN: 'a-long-enough-token',
  PUPUS_PHONE_URL: 'http://127.0.0.1:8765',
  PUPUS_PHONE_TOKEN: 'p',
};

describe('loadBridgeConfig', () => {
  it('applies defaults: port 8766, Sonnet 5.5, low effort', () => {
    const cfg = loadBridgeConfig(BASE);
    expect(cfg).toMatchObject({ port: 8766, model: 'claude-sonnet-5-5', effort: 'low', claudeBin: 'claude' });
  });

  it('rejects short tokens, odd model names and bad efforts', () => {
    expect(() => loadBridgeConfig({ ...BASE, PUPUS_BRIDGE_TOKEN: 'short' })).toThrow(BridgeConfigError);
    expect(() => loadBridgeConfig({ ...BASE, PUPUS_MODEL: 'x; rm -rf' })).toThrow(/PUPUS_MODEL/);
    expect(() => loadBridgeConfig({ ...BASE, PUPUS_EFFORT: 'turbo' })).toThrow(/PUPUS_EFFORT/);
    expect(() => loadBridgeConfig({ ...BASE, PUPUS_PHONE_URL: '' })).toThrow(/PUPUS_PHONE_URL/);
  });

  it('accepts Haiku and an empty effort to use the CLI default', () => {
    const cfg = loadBridgeConfig({ ...BASE, PUPUS_MODEL: 'claude-haiku-5-5', PUPUS_EFFORT: '' });
    const launch = buildClaudeLaunch(cfg, 'mcp.json', 'prompt');
    expect(launch.args).toContain('claude-haiku-5-5');
    expect(launch.args).not.toContain('--effort');
  });
});

describe('memory paths', () => {
  it('default to ~/.pupusphone, accept a path and turn off with an empty string', () => {
    const def = loadBridgeConfig(BASE);
    expect(def.memoryFile).toMatch(/[\\/]\.pupusphone[\\/]memory\.json$/);
    expect(def.profileFile).toMatch(/[\\/]\.pupusphone[\\/]profile\.md$/);
    const custom = loadBridgeConfig({ ...BASE, PUPUS_MEMORY_FILE: '/x/m.json', PUPUS_PROFILE_FILE: '' });
    expect(custom.memoryFile).toBe('/x/m.json');
    expect(custom.profileFile).toBeUndefined();
  });
});

describe('claude launch', () => {
  it('runs script binaries through node and appends --resume', () => {
    const cfg = loadBridgeConfig({ ...BASE, PUPUS_CLAUDE_BIN: '/x/fake.mjs' });
    const launch = buildClaudeLaunch(cfg, 'mcp.json', 'prompt', 'sess-1');
    expect(launch.command).toBe(process.execPath);
    expect(launch.args[0]).toBe('/x/fake.mjs');
    expect(launch.args.slice(-2)).toEqual(['--resume', 'sess-1']);
  });

  it('passes only allow-listed environment variables', () => {
    const cfg = loadBridgeConfig({ ...BASE, PUPUS_CLAUDE_ENV_PASSTHROUGH: 'EXTRA_OK' });
    const env = claudeEnv(cfg, { PATH: '/bin', EXTRA_OK: '1', SECRET_THING: 's', CLAUDE_CODE_PLUGIN_DIRS: 'x' });
    expect(Object.keys(env).sort()).toEqual(['CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC', 'DISABLE_AUTOUPDATER', 'EXTRA_OK', 'PATH']);
  });
});

describe('protocol and auth helpers', () => {
  it('validates client frames', () => {
    expect(parseClientFrame('{"type":"utterance","id":"1","text":"hola"}')).toEqual({ type: 'utterance', id: '1', text: 'hola' });
    expect(parseClientFrame('{"type":"reset"}')).toEqual({ type: 'reset' });
    expect(parseClientFrame('{"type":"cancel"}')).toHaveProperty('invalid');
    expect(parseClientFrame('[1]')).toHaveProperty('invalid');
    expect(parseClientFrame('{"type":"dance"}')).toHaveProperty('invalid');
  });

  it('compares tokens exactly', () => {
    expect(tokenEquals('abc', 'abc')).toBe(true);
    expect(tokenEquals('abc', 'abd')).toBe(false);
    expect(tokenEquals('', 'abc')).toBe(false);
  });
});
