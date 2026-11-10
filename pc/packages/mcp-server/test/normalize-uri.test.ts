import { describe, expect, it } from 'vitest';
import { normalizeUri } from '../src/tools/apps.ts';

describe('normalizeUri', () => {
  it('encodes spaces and apostrophes in deep links', () => {
    expect(normalizeUri("spotify:search:Don't Stop Me Now Queen")).toBe('spotify:search:Don%27t%20Stop%20Me%20Now%20Queen');
  });

  it('leaves already-encoded URLs unchanged', () => {
    expect(normalizeUri('https://wa.me/34600000000?text=hola%20mama')).toBe('https://wa.me/34600000000?text=hola%20mama');
  });
});
