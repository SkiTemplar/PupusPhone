import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { eventually, startTestBridge, VoiceClient, type TestBridge } from './ws-helpers.ts';

let tb: TestBridge;
let phone: VoiceClient;

async function connectReady(): Promise<VoiceClient> {
  const client = await VoiceClient.connect(tb.bridge.port);
  client.send({ type: 'hello', client: 'android', version: '0.1.0' });
  await client.waitFor((f) => f.type === 'ready');
  return client;
}

const spawns = (count = 1): Promise<string[]> =>
  eventually(() => tb.readFile('spawns.log').trim().split('\n'), (lines) => lines.length >= count);

beforeEach(async () => {
  tb = await startTestBridge();
  phone = await connectReady();
});

afterEach(async () => {
  phone.close();
  await tb.bridge.close();
});

describe('connection', () => {
  it('rejects a wrong token with 401 and an unknown path with 404', async () => {
    await expect(VoiceClient.connect(tb.bridge.port, 'wrong-token')).rejects.toThrow('HTTP 401');
    await expect(VoiceClient.connect(tb.bridge.port, undefined, '/other')).rejects.toThrow('HTTP 404');
  });

  it('answers hello with ready and the configured model', () => {
    expect(phone.frames[0]).toEqual({ type: 'ready', model: 'claude-sonnet-5-5' });
  });

  it('reports invalid frames without dropping the connection', async () => {
    phone.send('not json');
    phone.send({ type: 'utterance', id: '', text: 'x' });
    await phone.waitFor((f) => f.type === 'error' && f.message.includes('utterance.id'));
    expect(phone.frames.filter((f) => f.type === 'error')).toHaveLength(2);
  });
});

describe('turns', () => {
  it('streams sentences as say frames, tool use as status, then done', async () => {
    phone.send({ type: 'utterance', id: 'u1', text: 'pon música' });
    await phone.waitDone('u1');
    expect(phone.framesFor('u1').map((f) => f.type)).toEqual(['say', 'status', 'say', 'say', 'say', 'done']);
    const spoken = phone.framesFor('u1').flatMap((f) => (f.type === 'say' ? [f.text] : []));
    expect(spoken).toEqual([
      'Vale, abro Spotify',
      'Listo.',
      'Suena la lista del Sr. Pérez, la número 3.5 de tus favoritas.',
      '¿Quieres que suba el volumen?',
    ]);
    expect(phone.framesFor('u1')[1]).toEqual({ type: 'status', id: 'u1', text: 'Abriendo Spotify…' });
    expect(tb.readFile('utterances.log')).toBe('pon música\n');
  });

  it('launches claude isolated from the user configuration', async () => {
    const args = await eventually(() => JSON.parse(tb.readFile('args.json')) as string[]);
    const value = (flag: string): string | undefined => args[args.indexOf(flag) + 1];
    expect(args).toEqual(expect.arrayContaining(['-p', '--verbose', '--include-partial-messages', '--strict-mcp-config', '--disable-slash-commands']));
    expect(value('--input-format')).toBe('stream-json');
    expect(value('--output-format')).toBe('stream-json');
    expect(value('--setting-sources')).toBe('');
    expect(value('--tools')).toBe('');
    expect(value('--allowedTools')).toBe('mcp__phone');
    expect(value('--permission-mode')).toBe('dontAsk');
    expect(value('--model')).toBe('claude-sonnet-5-5');
    expect(value('--system-prompt')).toContain('manos libres');
    const mcp = JSON.parse(readFileSync(value('--mcp-config') ?? '', 'utf8'));
    expect(Object.keys(mcp.mcpServers)).toEqual(['phone']);
    expect(mcp.mcpServers.phone.env).toMatchObject({ PUPUS_PHONE_URL: 'http://127.0.0.1:9', PUPUS_PHONE_TOKEN: 'phone-token' });
    const envKeys = JSON.parse(tb.readFile('env.json')) as string[];
    expect(envKeys).not.toContain('PUPUS_CANARY_SECRET');
    expect(envKeys).not.toContain('CLAUDE_PLUGIN_ROOT');
  });

  it('turns an error result into error + done', async () => {
    phone.send({ type: 'utterance', id: 'u2', text: 'esto falla' });
    await phone.waitDone('u2');
    expect(phone.framesFor('u2').map((f) => f.type)).toEqual(['error', 'done']);
  });

  it('answers an empty utterance with done immediately', async () => {
    phone.send({ type: 'utterance', id: 'e', text: '   ' });
    expect(await phone.waitDone('e')).toEqual({ type: 'done', id: 'e', durationMs: 0 });
  });
});

describe('interruption', () => {
  it('cancel interrupts the running turn via control request and keeps the process', async () => {
    phone.send({ type: 'utterance', id: 'slow', text: 'cuéntame algo lento' });
    await phone.waitFor((f) => f.type === 'say' && f.id === 'slow');
    phone.send({ type: 'cancel', id: 'slow' });
    await phone.waitDone('slow');
    const saysAfterDone = phone.frames.length;
    phone.send({ type: 'utterance', id: 'next', text: 'pon música' });
    await phone.waitDone('next');
    expect(phone.frames.slice(saysAfterDone).every((f) => !('id' in f) || f.id === 'next')).toBe(true);
    expect(await spawns()).toEqual(['new']);
  });

  it('a new utterance mid-turn interrupts the old one and then runs', async () => {
    phone.send({ type: 'utterance', id: 'a', text: 'algo lento' });
    await phone.waitFor((f) => f.type === 'say' && f.id === 'a');
    phone.send({ type: 'utterance', id: 'b', text: 'pon música' });
    await phone.waitDone('b');
    const order = phone.frames.filter((f) => f.type === 'done').map((f) => (f.type === 'done' ? f.id : ''));
    expect(order).toEqual(['a', 'b']);
    expect(phone.framesFor('b').filter((f) => f.type === 'say')).toHaveLength(4);
  });

  it('kills and resumes the session when the interrupt does not land', async () => {
    phone.send({ type: 'utterance', id: 'deaf', text: 'modo sordo' });
    await phone.waitFor((f) => f.type === 'say' && f.id === 'deaf');
    phone.send({ type: 'cancel', id: 'deaf' });
    await phone.waitDone('deaf');
    await phone.waitFor((f) => f.type === 'ready', 5000, phone.after('deaf'));
    expect((await spawns(2))[1]).toMatch(/^resume fake-\d+$/);
    phone.send({ type: 'utterance', id: 'after', text: 'pon música' });
    await phone.waitDone('after');
  });
});

describe('lifecycle', () => {
  it('reports a crash, respawns with --resume and serves the next turn', async () => {
    phone.send({ type: 'utterance', id: 'boom', text: 'crash' });
    await phone.waitDone('boom');
    expect(phone.framesFor('boom').some((f) => f.type === 'error')).toBe(true);
    phone.send({ type: 'utterance', id: 'later', text: 'pon música' });
    await phone.waitDone('later');
    expect(await spawns(2)).toHaveLength(2);
    expect((await spawns(2))[1]).toMatch(/^resume /);
  });

  it('reset starts a fresh process without resume and announces ready', async () => {
    phone.send({ type: 'utterance', id: 'r1', text: 'pon música' });
    await phone.waitDone('r1');
    const mark = phone.frames.length;
    phone.send({ type: 'reset' });
    await phone.waitFor((f) => f.type === 'ready', 5000, mark);
    expect(await spawns(2)).toEqual(['new', 'new']);
  });

  it('a newer connection replaces the older one', async () => {
    const second = await connectReady();
    second.send({ type: 'utterance', id: 'n', text: 'pon música' });
    await second.waitDone('n');
    expect(phone.framesFor('n')).toHaveLength(0);
    second.close();
  });
});
