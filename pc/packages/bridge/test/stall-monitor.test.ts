import { describe, expect, it } from 'vitest';
import { measureStall } from '../src/stall-monitor.ts';

describe('measureStall', () => {
  it('ignores a tick that arrives on time or slightly late', () => {
    expect(measureStall(0, 5_000, 5_000, 15_000)).toBeNull();
    expect(measureStall(0, 12_000, 5_000, 15_000)).toBeNull();
  });

  it('reports the whole gap when the process was frozen', () => {
    expect(measureStall(0, 20_000, 5_000, 15_000)).toBe(20_000);
    expect(measureStall(1_000, 91_000, 5_000, 15_000)).toBe(90_000);
  });

  it('ignores a clock that went backwards', () => {
    expect(measureStall(10_000, 2_000, 5_000, 15_000)).toBeNull();
  });
});
