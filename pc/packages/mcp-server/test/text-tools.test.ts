import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { startFakePhone, type RunningFakePhone } from '@pupusphone/fake-phone';
import type { McpConfig } from '../src/config.ts';
import { PhoneClient } from '../src/phone-client.ts';
import { createPhoneServer } from '../src/server.ts';
import type { ScreenTree, TreeNode } from '../src/types.ts';
import { call, connect, textOf, TOKEN, type Connected } from './helpers.ts';

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

describe('tap_text', () => {
  it('finds and taps by accent-insensitive label in one call, without a prior read_screen', async () => {
    const out = textOf(await call(mcp.client, 'tap_text', { query: 'telefono' }));
    expect(out).toMatch(/^ok: tapped node \d+ "Teléfono"/);
    expect(out).toContain('app=com.google.android.dialer');
    expect(fake.phone.requests('GET', '/screen/tree')).toHaveLength(2);
  });

  it('taps the first of several matches, reports the count and honours index', async () => {
    const first = textOf(await call(mcp.client, 'tap_text', { query: 'a', observe: false }));
    expect(first).toMatch(/^ok: tapped node \d+ "WhatsApp" \(2 matches: 0: \[\d+\] "WhatsApp", 1: \[\d+\] "Maps"; pass index/);
    expect(fake.phone.currentScreen).toBe('whatsapp');
    await call(mcp.client, 'press', { action: 'home', observe: false });
    await call(mcp.client, 'tap_text', { query: 'a', index: 1, observe: false });
    expect(fake.phone.currentScreen).toBe('maps');
  });

  it('exact ignores partial matches', async () => {
    const result = await call(mcp.client, 'tap_text', { query: 'whats', exact: true });
    expect(result.isError).toBe(true);
    expect(fake.phone.requests('POST', '/input/tap')).toHaveLength(0);
  });

  it('a non-clickable label taps through its clickable ancestor', async () => {
    await call(mcp.client, 'launch_app', { package: 'com.whatsapp', observe: false });
    const out = textOf(await call(mcp.client, 'tap_text', { query: 'mama', observe: false }));
    expect(out).toMatch(/^ok: tapped node \d+ "Mamá"/);
    expect(fake.phone.requests('POST', '/input/tap')).toHaveLength(1);
  });

  it('no match fails with the visible labels so the model can retry without reading', async () => {
    const result = await call(mcp.client, 'tap_text', { query: 'Fortnite' });
    expect(result.isError).toBe(true);
    expect(textOf(result)).toMatch(/Visible labels: "Spotify", "WhatsApp", "Teléfono", "Maps", "12:30"/);
    expect(fake.phone.requests('POST', '/input/tap')).toHaveLength(0);
  });

  it('clickable_only=true skips plain text and suggests clickable_only=false', async () => {
    await call(mcp.client, 'launch_app', { package: 'com.spotify.music', observe: false });
    const result = await call(mcp.client, 'tap_text', { query: 'buenas tardes' });
    expect(result.isError).toBe(true);
    expect(textOf(result)).toContain('clickable_only=false');
    const forced = textOf(await call(mcp.client, 'tap_text', { query: 'buenas tardes', clickable_only: false, observe: false }));
    expect(forced).toMatch(/^ok: tapped node \d+ "Buenas tardes"/);
  });

  it('out-of-range index lists the candidates', async () => {
    const result = await call(mcp.client, 'tap_text', { query: 'spotify', index: 3 });
    expect(result.isError).toBe(true);
    expect(textOf(result)).toMatch(/index 3 out of range: 1 match/);
  });
});

describe('wait_for_text', () => {
  it('returns as soon as the text appears', async () => {
    setTimeout(() => fake.phone.goTo('whatsapp'), 150);
    const out = textOf(await call(mcp.client, 'wait_for_text', { query: 'mama', timeout_ms: 3000 }));
    expect(out).toMatch(/^found "mama": node \d+ "Mamá"/);
    expect(out).toContain('app=com.whatsapp');
  });

  it('fails with the last screen on timeout', async () => {
    const result = await call(mcp.client, 'wait_for_text', { query: 'no-existe', timeout_ms: 500 });
    expect(result.isError).toBe(true);
    expect(textOf(result)).toMatch(/^timeout: "no-existe" not visible after 500 ms\n\napp=com\.google\.android\.apps\.nexuslauncher/);
  });
});

describe('scroll_to_text against fake-phone', () => {
  it('returns immediately when the text is already visible', async () => {
    const out = textOf(await call(mcp.client, 'scroll_to_text', { query: 'maps' }));
    expect(out).toMatch(/^found "maps" after 0 scroll\(s\)/);
    expect(fake.phone.requests('POST', '/input/scroll')).toHaveLength(0);
  });

  it('stops when the list does not change', async () => {
    const result = await call(mcp.client, 'scroll_to_text', { query: 'Fortnite' });
    expect(result.isError).toBe(true);
    expect(textOf(result)).toContain('stopped changing after 1 scroll(s)');
    expect(fake.phone.requests('POST', '/input/scroll')).toHaveLength(1);
  });

  it('fails when there is nothing to scroll', async () => {
    await call(mcp.client, 'launch_app', { package: 'com.google.android.dialer', observe: false });
    const result = await call(mcp.client, 'scroll_to_text', { query: 'Fortnite' });
    expect(result.isError).toBe(true);
    expect(textOf(result)).toContain('no scrollable container');
  });
});

/** Phone stub whose list shows a new page of items after each scroll, until the last page. */
class PagedPhone extends PhoneClient {
  page = 0;
  readonly scrolls: unknown[] = [];
  private readonly pages: readonly (readonly string[])[];

  constructor(pages: readonly (readonly string[])[], config: McpConfig) {
    super(config);
    this.pages = pages;
  }

  override async tree(): Promise<ScreenTree> {
    const items = this.pages[Math.min(this.page, this.pages.length - 1)] ?? [];
    const list: TreeNode = { id: 0, parent: -1, cls: 'RecyclerView', text: null, desc: null, resId: 'list', bounds: [0, 0, 1080, 2400], flags: ['scrollable'] };
    const rows = items.map((label, i): TreeNode => ({
      id: i + 1, parent: 0, cls: 'TextView', text: label, desc: null, resId: null, bounds: [0, i * 200, 1080, i * 200 + 200], flags: ['clickable'],
    }));
    return { package: 'test.list', screen: { width: 1080, height: 2400 }, nodes: [list, ...rows] };
  }

  override async post(path: string, body: unknown): Promise<{ ok: boolean }> {
    if (path === '/input/scroll') {
      this.scrolls.push(body);
      this.page += 1;
    }
    return { ok: true };
  }
}

async function connectStub(phone: PhoneClient, config: McpConfig): Promise<Client> {
  const server = createPhoneServer(config, phone);
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  const client = new Client({ name: 'test', version: '0.0.0' });
  await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);
  return client;
}

describe('scroll_to_text with a paged list', () => {
  const config: McpConfig = { phoneUrl: 'http://127.0.0.1:1', token: TOKEN, timeoutMs: 1000, settleMs: 1, defaultCountryCode: '34' };
  const pages = [['Uno', 'Dos'], ['Tres', 'Cuatro'], ['Canción final', 'Fin']];

  it('scrolls the first scrollable until the text appears', async () => {
    const phone = new PagedPhone(pages, config);
    const client = await connectStub(phone, config);
    const out = textOf(await call(client, 'scroll_to_text', { query: 'cancion' }));
    expect(out).toMatch(/^found "cancion" after 2 scroll\(s\): node 1 "Canción final"/);
    expect(phone.scrolls).toEqual([{ direction: 'down', nodeId: 0 }, { direction: 'down', nodeId: 0 }]);
    await client.close();
  });

  it('honours max_scrolls, direction and an explicit node_id', async () => {
    const phone = new PagedPhone(pages, config);
    const client = await connectStub(phone, config);
    await call(client, 'read_screen');
    const result = await call(client, 'scroll_to_text', { query: 'cancion', max_scrolls: 1, direction: 'up', node_id: 0 });
    expect(result.isError).toBe(true);
    expect(textOf(result)).toMatch(/^"cancion" not found after 1 scroll\(s\)/);
    expect(phone.scrolls).toEqual([{ direction: 'up', nodeId: 0 }]);
    await client.close();
  });

  it('rejects a node_id that is not in the latest screen', async () => {
    const phone = new PagedPhone(pages, config);
    const client = await connectStub(phone, config);
    const result = await call(client, 'scroll_to_text', { query: 'cancion', node_id: 0 });
    expect(result.isError).toBe(true);
    expect(textOf(result)).toContain('read_screen');
    expect(phone.scrolls).toHaveLength(0);
    await client.close();
  });
});
