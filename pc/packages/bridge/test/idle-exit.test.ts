import { describe, expect, it } from 'vitest';
import { IdleCounter } from '../src/idle-exit.ts';

describe('IdleCounter', () => {
  it('fires after enough consecutive ticks without a client', () => {
    const counter = new IdleCounter(30_000, 10_000);
    expect(counter.tick(false)).toBe(false);
    expect(counter.tick(false)).toBe(false);
    expect(counter.tick(false)).toBe(true);
  });

  it('a connected phone resets the count', () => {
    const counter = new IdleCounter(30_000, 10_000);
    counter.tick(false);
    counter.tick(false);
    expect(counter.tick(true)).toBe(false);
    expect(counter.tick(false)).toBe(false);
    expect(counter.tick(false)).toBe(false);
    expect(counter.tick(false)).toBe(true);
  });

  it('never fires while a phone stays connected', () => {
    const counter = new IdleCounter(10_000, 10_000);
    for (let i = 0; i < 100; i++) expect(counter.tick(true)).toBe(false);
  });
});
