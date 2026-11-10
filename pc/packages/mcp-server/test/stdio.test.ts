import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { startFakePhone, type RunningFakePhone } from '@pupusphone/fake-phone';
import { ConfigError, loadConfig } from '../src/config.ts';
import { matchApp } from '../src/tools/apps.ts';
import { TOKEN, textOf } from './helpers.ts';
import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';

const CLI = fileURLToPath(new URL('../src/cli.ts', import.meta.url));

describe('loadConfig', () => {
  it('requires URL and token and validates numbers', () => {
    expect(() => loadConfig({})).toThrow(ConfigError);
    expect(() => loadConfig({ PUPUS_PHONE_URL: 'http://x:1' })).toThrow(/TOKEN/);
    expect(() => loadConfig({ PUPUS_PHONE_URL: 'ftp://x', PUPUS_PHONE_TOKEN: 't' })).toThrow(/http/);
    expect(() => loadConfig({ PUPUS_PHONE_URL: 'http://x:1', PUPUS_PHONE_TOKEN: 't', PUPUS_PHONE_TIMEOUT_MS: '-3' })).toThrow();
    const cfg = loadConfig({ PUPUS_PHONE_URL: 'http://100.64.0.2:8765/', PUPUS_PHONE_TOKEN: 't' });
    expect(cfg).toEqual({ phoneUrl: 'http://100.64.0.2:8765', token: 't', timeoutMs: 8000, settleMs: 700 });
  });
});

describe('matchApp', () => {
  const apps = [
    { package: 'a.spotify', label: 'Spotify' },
    { package: 'b.maps', label: 'Maps' },
    { package: 'c.mapsgo', label: 'Maps Go' },
  ];
  it('prefers exact label, then prefix, and reports ambiguity', () => {
    expect(matchApp(apps, 'MAPS')).toEqual(apps[1]);
    expect(matchApp(apps, 'spot')).toEqual(apps[0]);
    expect(matchApp(apps, 'ma')).toHaveLength(2);
    expect(matchApp(apps, 'zzz')).toEqual([]);
  });
});

describe('stdio transport (real process)', () => {
  let fake: RunningFakePhone;
  let client: Client;

  beforeAll(async () => {
    fake = await startFakePhone({ token: TOKEN, port: 0 });
    client = new Client({ name: 'stdio-test', version: '0.0.0' });
    const transport = new StdioClientTransport({
      command: process.execPath,
      args: [CLI],
      env: { PUPUS_PHONE_URL: fake.url, PUPUS_PHONE_TOKEN: TOKEN, PUPUS_SETTLE_MS: '5', PUPUS_LOG_LEVEL: 'warn' },
      stderr: 'pipe',
    });
    await client.connect(transport);
  });

  afterAll(async () => {
    await client.close();
    await fake.close();
  });

  it('serves tools over stdio with the server name "phone"', async () => {
    expect(client.getServerVersion()?.name).toBe('phone');
    const result = (await client.callTool({ name: 'launch_app', arguments: { name: 'Spotify' } })) as CallToolResult;
    expect(textOf(result)).toContain('app=com.spotify.music');
  });
});
