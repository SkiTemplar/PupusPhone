import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { LineSplitter, parseLine, type ClaudeEvent } from '../src/stream-parser.ts';
import { shortToolName, toolStatus } from '../src/status.ts';

function fixture(name: string): string[] {
  return readFileSync(new URL(`./fixtures/${name}`, import.meta.url), 'utf8').split('\n').filter(Boolean);
}

describe('parseLine', () => {
  const events: ClaudeEvent[] = fixture('turn-launch.jsonl').flatMap(parseLine);

  it('extracts init with session id and tools', () => {
    expect(events[0]).toEqual({ kind: 'init', sessionId: 'SESSION', model: 'claude-sonnet-5-5', tools: ['mcp__phone__launch_app', 'mcp__phone__read_screen'] });
  });

  it('extracts text deltas in order and ignores input_json deltas', () => {
    const texts = events.filter((e) => e.kind === 'text').map((e) => (e.kind === 'text' ? e.text : ''));
    expect(texts).toEqual(['Vale, abro Spotify', 'Listo. Suena la lista del Sr. Pérez, ', 'la número 3.5 de tus favoritas. ¿Quieres', ' que suba el volumen?']);
  });

  it('extracts complete tool_use blocks from assistant messages', () => {
    expect(events.filter((e) => e.kind === 'tool')).toEqual([{ kind: 'tool', id: 'toolu_1', name: 'mcp__phone__launch_app', input: { name: 'Spotify' } }]);
  });

  it('extracts the final result', () => {
    expect(events.at(-1)).toMatchObject({ kind: 'result', subtype: 'success', isError: false, durationMs: 2100, sessionId: 'SESSION' });
  });

  it('parses interrupt acknowledgement and the error result that follows', () => {
    const parsed = fixture('interrupted.jsonl').flatMap(parseLine);
    expect(parsed[0]).toEqual({ kind: 'control_response', requestId: 'int-1', success: true, error: undefined });
    expect(parsed.at(-1)).toMatchObject({ kind: 'result', subtype: 'error_during_execution', isError: true });
  });

  it('ignores subagent output, unknown types and blank lines; flags invalid JSON', () => {
    const sub = JSON.stringify({ type: 'stream_event', parent_tool_use_id: 'toolu_9', event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'x' } } });
    expect(parseLine(sub)).toEqual([]);
    expect(parseLine('{"type":"rate_limit_event"}')).toEqual([]);
    expect(parseLine('   ')).toEqual([]);
    expect(parseLine('not json')[0]?.kind).toBe('invalid');
  });
});

describe('LineSplitter', () => {
  it('reassembles lines split across chunks and strips CR', () => {
    const splitter = new LineSplitter();
    expect(splitter.push('{"a":1}\r\n{"b"')).toEqual(['{"a":1}']);
    expect(splitter.push(':2}\n\n')).toEqual(['{"b":2}']);
  });
});

describe('toolStatus', () => {
  it('maps phone tools to short Spanish status lines', () => {
    expect(shortToolName('mcp__phone__launch_app')).toBe('launch_app');
    expect(toolStatus('mcp__phone__launch_app', { package: 'com.spotify.music' })).toBe('Abriendo Spotify…');
    expect(toolStatus('mcp__phone__launch_app', { name: 'Waze' })).toBe('Abriendo Waze…');
    expect(toolStatus('mcp__phone__open_url', { url: 'spotify:search:queen' })).toBe('Buscando en Spotify…');
    expect(toolStatus('mcp__phone__open_url', { url: 'tel:+34600000000' })).toBe('Preparando la llamada…');
    expect(toolStatus('mcp__phone__media', { action: 'next' })).toBe('Siguiente canción…');
    expect(toolStatus('mcp__phone__press', { action: 'notifications' })).toBe('Mirando las notificaciones…');
    expect(toolStatus('mcp__phone__read_screen', {})).toBe('Mirando la pantalla…');
    expect(toolStatus('mcp__other__thing', {})).toBe('Trabajando…');
  });
});
