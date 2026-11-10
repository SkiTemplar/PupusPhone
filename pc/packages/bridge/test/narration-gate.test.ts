import { describe, expect, it } from 'vitest';
import { NarrationGate } from '../src/narration-gate.ts';

describe('NarrationGate', () => {
  it('drops narration that is followed by a tool call', () => {
    const gate = new NarrationGate();
    expect(gate.push(['Results are empty; wait and read.'])).toEqual([]);
    expect(gate.dropForTool()).toEqual(['Results are empty; wait and read.']);
    expect(gate.flush()).toEqual([]);
  });

  it('releases the final answer when the turn ends', () => {
    const gate = new NarrationGate();
    expect(gate.push(['Hecho.', 'Ya suena.'])).toEqual([]);
    expect(gate.flush()).toEqual(['Hecho.', 'Ya suena.']);
  });

  it('starts speaking long answers before the turn ends', () => {
    const gate = new NarrationGate(20);
    expect(gate.push(['Tienes tres mensajes.'])).toEqual(['Tienes tres mensajes.']);
    expect(gate.push(['El primero es de Ana.'])).toEqual(['El primero es de Ana.']);
    expect(gate.flush()).toEqual([]);
  });

  it('holds again after a tool call even if the previous text was released', () => {
    const gate = new NarrationGate(10);
    gate.push(['Un texto bastante largo.']);
    gate.dropForTool();
    expect(gate.push(['Corto.'])).toEqual([]);
    expect(gate.flush()).toEqual(['Corto.']);
  });
});
