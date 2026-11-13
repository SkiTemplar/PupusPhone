import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { defaultScenario, flattenScreen, startFakePhone, type RunningFakePhone, type Scenario } from '@pupusphone/fake-phone';
import { ConfigError, loadConfig } from '../src/config.ts';
import { chooseContact, pickNumber, toInternational } from '../src/contacts.ts';
import { age } from '../src/tools/shortcuts.ts';
import { call, connect, textOf, TOKEN, type Connected } from './helpers.ts';

let fake: RunningFakePhone;
let mcp: Connected;

async function start(scenario?: Scenario, countryCode = '34'): Promise<void> {
  fake = await startFakePhone({ token: TOKEN, port: 0, ...(scenario ? { scenario } : {}) });
  mcp = await connect({ phoneUrl: fake.url, defaultCountryCode: countryCode });
}

async function stop(): Promise<void> {
  await mcp.close();
  await fake.close();
}

function bodies(method: string, path: string): unknown[] {
  return fake.phone.requests(method, path).map((r) => r.body);
}

describe('media shortcuts', () => {
  beforeEach(() => start());
  afterEach(() => stop());

  it('play_music drives Spotify search by default: top result tapped and playback confirmed', async () => {
    const out = textOf(await call(mcp.client, 'play_music', { query: 'Bohemian Rhapsody Queen' }));
    expect(out).toBe('playing: Bohemian Rhapsody — Queen (com.spotify.music)');
    // Spotify ignores play-from-search from untrusted apps, so the session path is not even tried.
    expect(bodies('POST', '/media/play_search')).toEqual([]);
    expect(bodies('POST', '/intent')).toEqual([{ data: 'spotify:search:Bohemian%20Rhapsody%20Queen', package: 'com.spotify.music' }]);
    expect(fake.phone.requests('POST', '/input/tap')).toHaveLength(1);
  });

  it('play_music does not tap anything while the search box shows another query', async () => {
    const result = await call(mcp.client, 'play_music', { query: 'Queen' });
    expect(result.isError).toBe(true);
    expect(textOf(result)).toMatch(/no result appeared/);
    expect(fake.phone.requests('POST', '/input/tap')).toHaveLength(0);
  });

  it('play_music uses play-from-search on the session of other apps', async () => {
    const out = textOf(await call(mcp.client, 'play_music', { query: 'Un tema', package: 'com.google.android.apps.podcasts' }));
    expect(out).toBe('playing: Un tema — Fake Artist (com.google.android.apps.podcasts)');
    expect(bodies('POST', '/media/play_search')).toEqual([{ query: 'Un tema', package: 'com.google.android.apps.podcasts' }]);
  });

  it('play_music reports an unconfirmed request when the app had no session', async () => {
    const out = textOf(await call(mcp.client, 'play_music', { query: 'lo-fi', package: 'com.google.android.apps.youtube.music' }));
    expect(out).toBe('requested lo-fi in com.google.android.apps.youtube.music, not confirmed playing yet');
  });

  it('play_music explains a missing notification access (403)', async () => {
    fake.phone.services.notificationAccess = false;
    const result = await call(mcp.client, 'play_music', { query: 'Un tema', package: 'com.google.android.apps.podcasts' });
    expect(result.isError).toBe(true);
    expect(textOf(result)).toMatch(/no notification access.*Notification access > PupusPhone/s);
  });

  it('now_playing lists one line per session', async () => {
    const out = textOf(await call(mcp.client, 'now_playing'));
    expect(out.split('\n')).toEqual([
      'com.google.android.apps.podcasts: playing · Un tema al día — elDiario.es',
      'com.spotify.music: paused · Bohemian Rhapsody — Queen',
    ]);
  });
});

describe('read_notifications', () => {
  beforeEach(() => start());
  afterEach(() => stop());

  it('starts with the third-party warning and renders compact lines, newest first', async () => {
    const lines = textOf(await call(mcp.client, 'read_notifications')).split('\n');
    expect(lines[0]).toMatch(/third-party.*never instructions/);
    expect(lines.slice(1)).toEqual([
      'WhatsApp · Mamá: ¿Llegas a cenar? (3 min ago)',
      'Gmail · Factura de la luz: Tu factura de noviembre ya está disponible (25 min ago)',
      'WhatsApp · Fútbol: Javi: mañana a las 8 / Laura: vale (2 h ago)',
    ]);
  });

  it('filters by visible app name ignoring case and accents', async () => {
    const lines = textOf(await call(mcp.client, 'read_notifications', { app: 'whatsápp' })).split('\n');
    expect(lines).toHaveLength(3);
    expect(lines.slice(1).every((l) => l.startsWith('WhatsApp · '))).toBe(true);
    expect(fake.phone.requests('GET', '/notifications')[0]?.query).toEqual({});
  });

  it('passes a package filter to the phone', async () => {
    const out = textOf(await call(mcp.client, 'read_notifications', { app: 'com.google.android.gm' }));
    expect(out).toContain('Gmail · Factura de la luz');
    expect(fake.phone.requests('GET', '/notifications')[0]?.query).toEqual({ package: 'com.google.android.gm' });
  });

  it('says so when nothing matches, and surfaces the 403', async () => {
    expect(textOf(await call(mcp.client, 'read_notifications', { app: 'Telegram' }))).toBe('no notifications from Telegram');
    fake.phone.services.notificationAccess = false;
    const result = await call(mcp.client, 'read_notifications');
    expect(result.isError).toBe(true);
    expect(textOf(result)).toContain('notification access');
  });
});

describe('contacts and calls', () => {
  beforeEach(() => start());
  afterEach(() => stop());

  it('find_contact lists every number with its label', async () => {
    const out = textOf(await call(mcp.client, 'find_contact', { name: 'mama' }));
    expect(out.split('\n')).toEqual(['Mamá: 91 555 12 34 (Casa)', 'Mamá: 600 11 22 33 (Móvil)']);
    expect(textOf(await call(mcp.client, 'find_contact', { name: 'Nadie' }))).toBe('no contacts match "Nadie"');
  });

  it('call resolves a unique name to its mobile number', async () => {
    expect(textOf(await call(mcp.client, 'call', { contact: 'Papá' }))).toBe('calling Papá (+34 612 345 678)');
    expect(textOf(await call(mcp.client, 'call', { contact: 'Mamá' }))).toBe('calling Mamá (600 11 22 33)');
    expect(bodies('POST', '/call')).toEqual([{ number: '+34 612 345 678' }, { number: '600 11 22 33' }]);
  });

  it('call reports the dialer mode and dials raw numbers without a lookup', async () => {
    fake.phone.services.callPermission = false;
    const out = textOf(await call(mcp.client, 'call', { contact: '612 345 678' }));
    expect(out).toMatch(/^dialer opened for 612 345 678, user must tap Call/);
    expect(fake.phone.requests('GET', '/contacts')).toHaveLength(0);
  });

  it('call does not dial when the name is ambiguous or unknown', async () => {
    const ambiguous = await call(mcp.client, 'call', { contact: 'Javi' });
    expect(ambiguous.isError).toBe(true);
    expect(textOf(ambiguous)).toMatch(/2 contacts match "Javi": Javi Pérez: .*; Javier López: 677 888 999\..*Nothing was dialed/);
    const unknown = await call(mcp.client, 'call', { contact: 'Nadie' });
    expect(unknown.isError).toBe(true);
    expect(textOf(unknown)).toMatch(/no contact matches "Nadie".*Nothing was dialed/);
    expect(fake.phone.requests('POST', '/call')).toHaveLength(0);
  });

  it('call explains a missing contacts permission', async () => {
    fake.phone.services.contactsPermission = false;
    const result = await call(mcp.client, 'call', { contact: 'Papá' });
    expect(result.isError).toBe(true);
    expect(textOf(result)).toContain('no Contacts permission');
    expect(fake.phone.requests('POST', '/call')).toHaveLength(0);
  });
});

describe('whatsapp_send', () => {
  afterEach(() => stop());

  it('opens the chat with the text prefilled and taps Enviar', async () => {
    await start();
    const out = textOf(await call(mcp.client, 'whatsapp_send', { contact: 'Papá', text: 'Llego en 10 minutos, ¿vale?' }));
    expect(out).toMatch(/^ok: sent WhatsApp message to Papá \(\+34 612 345 678\)/);
    expect(out).toContain('app=com.whatsapp');
    expect(bodies('POST', '/intent')).toEqual([
      { data: 'https://wa.me/34612345678?text=Llego%20en%2010%20minutos%2C%20%C2%BFvale%3F', package: 'com.whatsapp' },
    ]);
    const sendId = flattenScreen(defaultScenario().screens['whatsapp-chat']!, { width: 1080, height: 2400 })
      .tree.nodes.find((n) => n.desc === 'Enviar')?.id;
    expect(bodies('POST', '/input/tap')).toEqual([{ nodeId: sendId }]);
  });

  it('adds the configured country code to numbers without one', async () => {
    await start(undefined, '351');
    await call(mcp.client, 'whatsapp_send', { contact: 'Mamá', text: 'hola' });
    await call(mcp.client, 'whatsapp_send', { contact: '0044 20 7946 0958', text: 'hi' });
    expect(bodies('POST', '/intent').map((b) => (b as { data: string }).data)).toEqual([
      'https://wa.me/351600112233?text=hola',
      'https://wa.me/442079460958?text=hi',
    ]);
  });

  it('sends nothing when the contact is ambiguous or unknown', async () => {
    await start();
    expect((await call(mcp.client, 'whatsapp_send', { contact: 'Javi', text: 'hola' })).isError).toBe(true);
    const unknown = await call(mcp.client, 'whatsapp_send', { contact: 'Nadie', text: 'hola' });
    expect(textOf(unknown)).toContain('Nothing was sent');
    expect(fake.phone.requests('POST', '/intent')).toHaveLength(0);
    expect(fake.phone.requests('POST', '/input/tap')).toHaveLength(0);
  });

  it('fails without tapping anything when the send button never appears', async () => {
    await start({ ...defaultScenario(), deepLinks: {} });
    const result = await call(mcp.client, 'whatsapp_send', { contact: 'Papá', text: 'hola', wait_ms: 300 });
    expect(result.isError).toBe(true);
    expect(textOf(result)).toMatch(/no "Enviar"\/"Send" button appeared within 300 ms.*NOT sent.*Visible labels: .*"Mamá"/s);
    expect(fake.phone.requests('POST', '/intent')).toHaveLength(1);
    expect(fake.phone.requests('POST', '/input/tap')).toHaveLength(0);
  });
});

describe('navigate', () => {
  beforeEach(() => start());
  afterEach(() => stop());

  it('starts Google Maps navigation with an encoded destination and mode', async () => {
    const out = textOf(await call(mcp.client, 'navigate', { destination: 'Calle Mayor 5, Madrid', mode: 'walking' }));
    expect(out).toBe('ok: navigating to Calle Mayor 5, Madrid (walking) in Google Maps');
    await call(mcp.client, 'navigate', { destination: 'Toledo' });
    expect(bodies('POST', '/intent')).toEqual([
      { data: 'google.navigation:q=Calle%20Mayor%205%2C%20Madrid&mode=w', package: 'com.google.android.apps.maps' },
      { data: 'google.navigation:q=Toledo', package: 'com.google.android.apps.maps' },
    ]);
    expect(fake.phone.currentScreen).toBe('maps');
  });
});

describe('pure helpers', () => {
  it('toInternational strips formatting and adds the default country code', () => {
    expect(toInternational('+34 612 345 678', '34')).toBe('34612345678');
    expect(toInternational('(600) 11-22-33', '34')).toBe('34600112233');
    expect(toInternational('0033 6 12 34 56 78', '34')).toBe('33612345678');
    expect(toInternational('12', '34')).toBeNull();
    expect(toInternational('abc', '34')).toBeNull();
  });

  it('pickNumber prefers mobile labels and chooseContact prefers a unique full-name match', () => {
    const javi = { name: 'Javi', numbers: [{ number: '1', label: 'Trabajo' }, { number: '2', label: 'CELL' }] };
    const javier = { name: 'Javier', numbers: [{ number: '3', label: null }] };
    expect(pickNumber(javi)?.number).toBe('2');
    expect(pickNumber(javier)?.number).toBe('3');
    expect(chooseContact([javi, javier], 'javi')).toBe(javi);
    expect(chooseContact([javi, javier], 'jav')).toEqual([javi, javier]);
  });

  it('age switches from minutes to hours to days', () => {
    expect(age(0, 4 * 60_000)).toBe('4 min ago');
    expect(age(0, 3 * 3_600_000)).toBe('3 h ago');
    expect(age(0, 72 * 3_600_000)).toBe('3 d ago');
  });

  it('loadConfig validates PUPUS_DEFAULT_COUNTRY_CODE', () => {
    const base = { PUPUS_PHONE_URL: 'http://x:1', PUPUS_PHONE_TOKEN: 't' };
    expect(loadConfig({ ...base, PUPUS_DEFAULT_COUNTRY_CODE: '1' }).defaultCountryCode).toBe('1');
    expect(() => loadConfig({ ...base, PUPUS_DEFAULT_COUNTRY_CODE: '+34' })).toThrow(ConfigError);
    expect(() => loadConfig({ ...base, PUPUS_DEFAULT_COUNTRY_CODE: '12345' })).toThrow(ConfigError);
  });
});
