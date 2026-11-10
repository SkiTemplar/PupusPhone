import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { startFakePhone, type RunningFakePhone, type ScreenTree } from '../src/index.ts';

const TOKEN = 'fake-phone-test-token';
let fake: RunningFakePhone;

async function api(method: string, path: string, body?: unknown, token = TOKEN): Promise<{ status: number; json: any }> {
  const res = await fetch(`${fake.url}${path}`, {
    method,
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
  });
  return { status: res.status, json: await res.json() };
}

beforeAll(async () => {
  fake = await startFakePhone({ token: TOKEN, port: 0 });
});

afterAll(() => fake.close());

beforeEach(async () => {
  await api('POST', '/input/global', { action: 'home' });
  fake.phone.clearActions();
});

describe('auth and errors', () => {
  it('rejects a missing or wrong token with 401 unauthorized', async () => {
    expect(await api('GET', '/health', undefined, 'nope')).toEqual({ status: 401, json: { error: 'unauthorized' } });
    const res = await fetch(`${fake.url}/health`);
    expect(res.status).toBe(401);
  });

  it('returns 404 for unknown routes and 400 for invalid bodies', async () => {
    expect((await api('GET', '/nope')).status).toBe(404);
    expect(await api('POST', '/input/tap', '{bad')).toEqual({ status: 400, json: { error: 'invalid_json' } });
    expect((await api('POST', '/input/tap', { x: 'a', y: 1 })).json.error).toContain('x must be an integer');
    expect((await api('POST', '/media', { action: 'louder' })).status).toBe(400);
    expect((await api('GET', '/screen/shot?scale=3')).status).toBe(400);
  });
});

describe('scripted screens', () => {
  it('reports health with the foreground package', async () => {
    const { json } = await api('GET', '/health');
    expect(json).toMatchObject({ ok: true, accessibility: true, screen: { width: 1080, height: 2400 }, foregroundPackage: 'com.google.android.apps.nexuslauncher' });
  });

  it('launching Spotify changes the tree; back returns home', async () => {
    expect((await api('POST', '/apps/launch', { package: 'com.spotify.music' })).json).toEqual({ ok: true });
    const tree = (await api('GET', '/screen/tree')).json as ScreenTree;
    expect(tree.package).toBe('com.spotify.music');
    expect(tree.nodes[0]).toMatchObject({ id: 0, parent: -1, cls: 'FrameLayout' });
    await api('POST', '/input/global', { action: 'back' });
    expect(fake.phone.currentScreen).toBe('launcher');
  });

  it('resolves nodeId against the last snapshot and 404s unknown ids', async () => {
    expect((await api('POST', '/input/tap', { nodeId: 999 })).json).toEqual({ error: 'node_not_found' });
    const tree = (await api('GET', '/screen/tree')).json as ScreenTree;
    const whatsapp = tree.nodes.find((n) => n.text === 'WhatsApp');
    await api('POST', '/input/tap', { nodeId: whatsapp?.id });
    expect(fake.phone.currentScreen).toBe('whatsapp');
  });

  it('routes intents by URI prefix and reports unknown handlers as ok=false', async () => {
    expect((await api('POST', '/intent', { data: 'tel:600000000' })).json).toEqual({ ok: true });
    expect(fake.phone.currentScreen).toBe('dialer');
    expect((await api('POST', '/intent', { data: 'unknown:thing' })).json).toEqual({ ok: false });
  });

  it('records every action and tracks media state', async () => {
    await api('POST', '/media', { action: 'play' });
    await api('POST', '/media', { action: 'volume_up' });
    expect(fake.phone.mediaStatus).toEqual({ playing: true, volume: 8, track: 0 });
    expect(fake.phone.actions.map((a) => a.path)).toEqual(['/media', '/media']);
  });

  it('types into the focused editable field', async () => {
    fake.phone.goTo('spotify-search');
    expect((await api('POST', '/input/text', { text: 'Queen', submit: true })).json).toEqual({ ok: true });
    const tree = (await api('GET', '/screen/tree')).json as ScreenTree;
    expect(tree.nodes.find((n) => n.resId === 'query')?.text).toBe('Queen');
  });
});
