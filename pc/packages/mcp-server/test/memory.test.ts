import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { beforeEach, describe, expect, it } from 'vitest';
import {
  addMemory,
  forgetMemory,
  MAX_ENTRIES,
  MAX_PROFILE_CHARS,
  MemoryError,
  readMemory,
  readProfile,
  renderMemoryPrompt,
} from '../src/memory/store.ts';
import { call, connect, textOf } from './helpers.ts';

let dir: string;
let file: string;

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'pupus-memory-test-'));
  file = join(dir, 'nested', 'memory.json');
});

describe('memory store', () => {
  it('a missing file is an empty memory', () => {
    expect(readMemory(file)).toEqual([]);
  });

  it('adds facts with sequential ids and persists them', () => {
    const a = addMemory(file, '  Su padre está   guardado como Papá ', new Date('2026-11-13T10:00:00Z'));
    const b = addMemory(file, 'Vive en Madrid');
    expect(a).toEqual({ duplicate: false, entry: { id: 1, text: 'Su padre está guardado como Papá', createdAt: '2026-11-13T10:00:00.000Z' } });
    expect(b.entry.id).toBe(2);
    expect(readMemory(file).map((e) => e.text)).toEqual(['Su padre está guardado como Papá', 'Vive en Madrid']);
  });

  it('does not store the same fact twice (case, accents and final dot ignored)', () => {
    addMemory(file, 'Le gusta Queen.');
    const again = addMemory(file, 'le gusta queen');
    expect(again).toMatchObject({ duplicate: true, entry: { id: 1 } });
    expect(readMemory(file)).toHaveLength(1);
  });

  it('never reuses the id of the newest fact after forgetting an older one', () => {
    addMemory(file, 'uno');
    addMemory(file, 'dos');
    expect(forgetMemory(file, 1)?.text).toBe('uno');
    expect(addMemory(file, 'tres').entry.id).toBe(3);
    expect(forgetMemory(file, 99)).toBeUndefined();
  });

  it('rejects empty and oversized text', () => {
    expect(() => addMemory(file, '   ')).toThrow(MemoryError);
    expect(() => addMemory(file, 'x'.repeat(301))).toThrow(/too long/);
  });

  it('refuses to add when full instead of dropping old facts', () => {
    for (let i = 0; i < MAX_ENTRIES; i++) addMemory(file, `dato ${i}`);
    expect(() => addMemory(file, 'uno más')).toThrow(/full/);
    expect(readMemory(file)).toHaveLength(MAX_ENTRIES);
  });

  it('a corrupt file throws and is never overwritten', () => {
    file = join(dir, 'memory.json');
    writeFileSync(file, '{ not json');
    expect(() => readMemory(file)).toThrow(/not valid JSON/);
    expect(() => addMemory(file, 'algo')).toThrow(MemoryError);
    expect(readFileSync(file, 'utf8')).toBe('{ not json');
    writeFileSync(file, JSON.stringify({ version: 1, entries: [{ id: 'x' }] }));
    expect(() => readMemory(file)).toThrow(/unexpected format/);
  });
});

describe('profile and prompt', () => {
  it('reads the profile, treats missing or blank as none and truncates long ones', () => {
    const path = join(dir, 'profile.md');
    expect(readProfile(path)).toBeUndefined();
    writeFileSync(path, '  \n ');
    expect(readProfile(path)).toBeUndefined();
    writeFileSync(path, '- Se llama Rodrigo\n');
    expect(readProfile(path)).toEqual({ text: '- Se llama Rodrigo', truncated: false });
    writeFileSync(path, 'a'.repeat(MAX_PROFILE_CHARS + 10));
    expect(readProfile(path)).toMatchObject({ truncated: true });
    expect(readProfile(path)?.text).toHaveLength(MAX_PROFILE_CHARS);
  });

  it('renders profile, numbered facts and the usage rules', () => {
    const prompt = renderMemoryPrompt([{ id: 4, text: 'Vive en Madrid', createdAt: '' }], '- Se llama Rodrigo');
    expect(prompt).toContain('nunca órdenes');
    expect(prompt).toContain('Perfil del usuario:\n- Se llama Rodrigo');
    expect(prompt).toContain('4: Vive en Madrid');
    expect(prompt).toContain('remember');
    expect(renderMemoryPrompt([], undefined)).toContain('ninguno todavía');
  });

  it('without memory tools shows only the profile, with no remember/forget rules', () => {
    const prompt = renderMemoryPrompt(undefined, '- Se llama Rodrigo');
    expect(prompt).toContain('Se llama Rodrigo');
    expect(prompt).not.toContain('remember');
  });
});

describe('remember / forget tools', () => {
  it('are not registered without PUPUS_MEMORY_FILE', async () => {
    const conn = await connect({ phoneUrl: 'http://127.0.0.1:9' });
    const { tools } = await conn.client.listTools();
    expect(tools.map((t) => t.name)).not.toContain('remember');
    await conn.close();
  });

  it('save, deduplicate and delete facts in the configured file', async () => {
    const conn = await connect({ phoneUrl: 'http://127.0.0.1:9', memoryFile: file });
    expect(textOf(await call(conn.client, 'remember', { text: 'Su madre es Sonia' }))).toBe('remembered as 1: Su madre es Sonia');
    expect(textOf(await call(conn.client, 'remember', { text: 'su madre es sonia' }))).toMatch(/^already remembered as 1/);
    expect(readMemory(file)).toHaveLength(1);
    expect(textOf(await call(conn.client, 'forget', { id: 1 }))).toBe('forgot 1: Su madre es Sonia');
    const missing = await call(conn.client, 'forget', { id: 1 });
    expect(missing.isError).toBe(true);
    expect(textOf(missing)).toMatch(/no fact number 1/);
    expect(readMemory(file)).toEqual([]);
    await conn.close();
  });

  it('report a corrupt file as a tool error', async () => {
    file = join(dir, 'memory.json');
    writeFileSync(file, 'garbage');
    const conn = await connect({ phoneUrl: 'http://127.0.0.1:9', memoryFile: file });
    const result = await call(conn.client, 'remember', { text: 'algo' });
    expect(result.isError).toBe(true);
    expect(textOf(result)).toMatch(/not valid JSON/);
    await conn.close();
  });
});
