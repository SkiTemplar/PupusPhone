import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { createServer } from 'node:net';
import { startFakePhone, type RunningFakePhone } from '@pupusphone/fake-phone';
import { call, connect, idOf, textOf, TOKEN, type Connected } from './helpers.ts';

const EXPECTED_TOOLS = [
  'launch_app', 'list_apps', 'long_press', 'media', 'open_url', 'press', 'read_screen',
  'screenshot', 'scroll', 'scroll_to_text', 'swipe', 'tap', 'tap_text', 'type_text', 'wait', 'wait_for_text',
];

let fake: RunningFakePhone;
let mcp: Connected;

beforeAll(async () => {
  fake = await startFakePhone({ token: TOKEN, port: 0 });
  mcp = await connect({ phoneUrl: fake.url });
});

afterAll(async () => {
  await mcp.close();
  await fake.close();
});

beforeEach(async () => {
  await call(mcp.client, 'press', { action: 'home', observe: false });
  fake.phone.clearActions();
});

describe('tool catalogue', () => {
  it('exposes every phone tool with a description', async () => {
    const { tools } = await mcp.client.listTools();
    expect(tools.map((t) => t.name).sort()).toEqual(EXPECTED_TOOLS);
    for (const tool of tools) expect(tool.description?.length ?? 0).toBeGreaterThan(40);
  });
});

describe('screen tools', () => {
  it('read_screen returns the compact rendering and hits /screen/tree', async () => {
    const out = textOf(await call(mcp.client, 'read_screen'));
    expect(out).toMatch(/^app=com\.google\.android\.apps\.nexuslauncher/);
    expect(out).toContain('"Spotify"');
    expect(fake.phone.requests('GET', '/screen/tree')[0]?.query).toEqual({ visibleOnly: 'true' });
  });

  it('screenshot returns image content', async () => {
    const result = await call(mcp.client, 'screenshot', { scale: 0.5 });
    expect(result.content[0]).toMatchObject({ type: 'image', mimeType: 'image/jpeg' });
    expect(textOf(result)).toContain('540x1200');
  });

  it('wait polls until text appears or times out', async () => {
    const found = textOf(await call(mcp.client, 'wait', { ms: 500, text: 'whatsapp' }));
    expect(found).toMatch(/^found "whatsapp"/);
    const missing = textOf(await call(mcp.client, 'wait', { ms: 500, text: 'no-existe' }));
    expect(missing).toMatch(/^timeout/);
  });
});

describe('input tools', () => {
  it('tap by node id launches the app and observes the new screen in the same result', async () => {
    const screen = textOf(await call(mcp.client, 'read_screen'));
    const out = textOf(await call(mcp.client, 'tap', { node_id: idOf(screen, '"Spotify"') }));
    expect(out).toMatch(/^ok: tapped node \d+ "Spotify"/);
    expect(out).toContain('app=com.spotify.music');
  });

  it('tap by coordinates and observe=false skips the screen read', async () => {
    const out = textOf(await call(mcp.client, 'tap', { x: 165, y: 1920, observe: false }));
    expect(out).toBe('ok: tapped 165,1920');
    expect(fake.phone.currentScreen).toBe('spotify');
    expect(fake.phone.requests('GET', '/screen/tree')).toHaveLength(0);
  });

  it('tap without target and stale node id fail with guidance', async () => {
    expect((await call(mcp.client, 'tap', {})).isError).toBe(true);
    const stale = await call(mcp.client, 'tap', { node_id: 999 });
    expect(stale.isError).toBe(true);
    expect(textOf(stale)).toContain('read_screen');
  });

  it('long_press resolves node centre; swipe and scroll forward their bodies', async () => {
    const screen = textOf(await call(mcp.client, 'read_screen'));
    await call(mcp.client, 'long_press', { node_id: idOf(screen, '"Maps"'), observe: false });
    expect(fake.phone.requests('POST', '/input/long_press')[0]?.body).toEqual({ x: 915, y: 1920 });
    await call(mcp.client, 'swipe', { x1: 500, y1: 2000, x2: 500, y2: 500, duration_ms: 250, observe: false });
    expect(fake.phone.requests('POST', '/input/swipe')[0]?.body).toEqual({ x1: 500, y1: 2000, x2: 500, y2: 500, durationMs: 250 });
    const scrolled = textOf(await call(mcp.client, 'scroll', { direction: 'down' }));
    expect(scrolled).toMatch(/^ok: scrolled down/);
  });

  it('type_text sets the focused field and the observed screen shows it', async () => {
    await call(mcp.client, 'launch_app', { package: 'com.spotify.music', observe: false });
    const screen = textOf(await call(mcp.client, 'read_screen'));
    await call(mcp.client, 'tap', { node_id: idOf(screen, 'desc:"Buscar"'), observe: false });
    const out = textOf(await call(mcp.client, 'type_text', { text: 'Bohemian Rhapsody', submit: true }));
    expect(out).toContain('"Bohemian Rhapsody" #query');
    expect(fake.phone.requests('POST', '/input/text')[0]?.body).toEqual({ text: 'Bohemian Rhapsody', submit: true });
  });

  it('press back returns to the previous screen', async () => {
    await call(mcp.client, 'launch_app', { package: 'com.whatsapp', observe: false });
    const out = textOf(await call(mcp.client, 'press', { action: 'back' }));
    expect(out).toContain('app=com.google.android.apps.nexuslauncher');
  });
});

describe('app and media tools', () => {
  it('list_apps lists label = package', async () => {
    const out = textOf(await call(mcp.client, 'list_apps'));
    expect(out).toContain('Spotify = com.spotify.music');
  });

  it('launch_app by name is accent-insensitive; unknown names fail', async () => {
    const out = textOf(await call(mcp.client, 'launch_app', { name: 'telefono' }));
    expect(out).toContain('app=com.google.android.dialer');
    const unknown = await call(mcp.client, 'launch_app', { name: 'Fortnite' });
    expect(unknown.isError).toBe(true);
  });

  it('open_url sends an intent with the deep link', async () => {
    const out = textOf(await call(mcp.client, 'open_url', { url: 'spotify:search:queen' }));
    expect(out).toContain('app=com.spotify.music');
    expect(fake.phone.requests('POST', '/intent')[0]?.body).toEqual({ data: 'spotify:search:queen' });
  });

  it('open_url rejects malformed or forbidden URIs at the boundary', async () => {
    expect((await call(mcp.client, 'open_url', { url: 'not a url' })).isError).toBe(true);
    expect((await call(mcp.client, 'open_url', { url: 'javascript:alert(1)' })).isError).toBe(true);
    expect(fake.phone.requests('POST', '/intent')).toHaveLength(0);
  });

  it('media sends the key without reading the screen by default', async () => {
    const out = textOf(await call(mcp.client, 'media', { action: 'play_pause' }));
    expect(out).toBe('ok: media play_pause');
    expect(fake.phone.mediaStatus.playing).toBe(true);
    expect(fake.phone.requests('GET', '/screen/tree')).toHaveLength(0);
  });

  it('rejects invalid enum values before calling the phone', async () => {
    const result = await call(mcp.client, 'media', { action: 'louder' });
    expect(result.isError).toBe(true);
    expect(fake.phone.requests('POST', '/media')).toHaveLength(0);
  });
});

async function closedPort(): Promise<number> {
  const server = createServer();
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  const port = typeof address === 'object' && address ? address.port : 0;
  await new Promise<void>((resolve) => server.close(() => resolve()));
  return port;
}

describe('failures', () => {
  it('wrong token yields an actionable 401 message', async () => {
    const bad = await connect({ phoneUrl: fake.url, token: 'wrong' });
    const result = await call(bad.client, 'read_screen');
    expect(result.isError).toBe(true);
    expect(textOf(result)).toContain('PUPUS_PHONE_TOKEN');
    await bad.close();
  });

  it('connection refused points to the accessibility service and Tailscale', async () => {
    const down = await connect({ phoneUrl: `http://127.0.0.1:${await closedPort()}` });
    const result = await call(down.client, 'tap', { x: 1, y: 1 });
    expect(result.isError).toBe(true);
    expect(textOf(result)).toMatch(/accessibility service.*Tailscale/s);
    await down.close();
  });
});
