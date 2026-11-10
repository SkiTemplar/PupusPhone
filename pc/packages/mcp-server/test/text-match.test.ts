import { describe, expect, it } from 'vitest';
import { findTextMatches, normalize, textSignature, visibleLabels } from '../src/text-match.ts';
import type { ScreenTree, TreeNode } from '../src/types.ts';

type NodeSpec = Partial<TreeNode> & Pick<TreeNode, 'id'>;

function node(spec: NodeSpec): TreeNode {
  return { parent: -1, cls: 'TextView', text: null, desc: null, resId: null, bounds: [0, 0, 100, 100], flags: [], ...spec };
}

function tree(...nodes: NodeSpec[]): ScreenTree {
  return { package: 'test.app', screen: { width: 1080, height: 2400 }, nodes: nodes.map(node) };
}

const ids = (matches: readonly { node: TreeNode }[]): number[] => matches.map((m) => m.node.id);

describe('normalize', () => {
  it('strips accents, lower-cases and collapses whitespace', () => {
    expect(normalize('  Canción   Ñandú 1 ')).toBe('cancion nandu 1');
    expect(normalize('ÁÉÍÓÚÜ')).toBe('aeiouu');
  });
});

describe('findTextMatches', () => {
  it('is case- and accent-insensitive on text and desc', () => {
    const t = tree({ id: 0, text: 'Canción del verano' }, { id: 1, desc: 'TELÉFONO' }, { id: 2, text: 'Otra' });
    expect(ids(findTextMatches(t, 'cancion'))).toEqual([0]);
    expect(ids(findTextMatches(t, 'telefono'))).toEqual([1]);
    expect(findTextMatches(t, 'canción')[0]?.label).toBe('Canción del verano');
  });

  it('exact requires whole-string equality after normalization', () => {
    const t = tree({ id: 0, text: 'Buscar amigos' }, { id: 1, desc: 'Búscar ' });
    expect(ids(findTextMatches(t, 'buscar', { exact: true }))).toEqual([1]);
    expect(ids(findTextMatches(t, 'busc', { exact: true }))).toEqual([]);
    expect(ids(findTextMatches(t, 'busc'))).toEqual([0, 1]);
  });

  it('ranks exact first, then clickable (self or ancestor), then top-to-bottom, then left-to-right', () => {
    const t = tree(
      { id: 0, text: 'Play list', bounds: [0, 100, 100, 200] },
      { id: 1, flags: ['clickable'], bounds: [0, 900, 1080, 1000] },
      { id: 2, parent: 1, text: 'Play next', bounds: [0, 900, 500, 1000] },
      { id: 3, text: 'Play', bounds: [0, 1500, 100, 1600] },
      { id: 4, text: 'Play later', bounds: [600, 300, 700, 400], flags: ['clickable'] },
      { id: 5, text: 'Play again', bounds: [200, 300, 300, 400], flags: ['clickable'] },
    );
    expect(ids(findTextMatches(t, 'play'))).toEqual([3, 5, 4, 2, 0]);
  });

  it('clickableOnly drops matches with nothing tappable', () => {
    const t = tree({ id: 0, text: 'Mamá' }, { id: 1, flags: ['clickable'] }, { id: 2, parent: 1, text: 'Mamá' });
    expect(ids(findTextMatches(t, 'mama', { clickableOnly: true }))).toEqual([2]);
    expect(findTextMatches(t, 'mama', { clickableOnly: true })[0]?.isClickable).toBe(true);
  });

  it('counts a node once when both text and desc match, preferring the exact value', () => {
    const t = tree({ id: 0, text: 'Reproducir ahora', desc: 'Reproducir' });
    const matches = findTextMatches(t, 'reproducir');
    expect(matches).toHaveLength(1);
    expect(matches[0]).toMatchObject({ label: 'Reproducir', isExact: true });
  });

  it('returns nothing for a blank query and survives parent cycles', () => {
    const t = tree({ id: 0, parent: 1, text: 'A' }, { id: 1, parent: 0, text: 'B' });
    expect(findTextMatches(t, '   ')).toEqual([]);
    expect(findTextMatches(t, 'a')[0]?.isClickable).toBe(false);
  });
});

describe('visibleLabels', () => {
  it('lists distinct labels, tappable first, capped and truncated', () => {
    const t = tree(
      { id: 0, text: 'Título' },
      { id: 1, text: 'Abrir', flags: ['clickable'] },
      { id: 2, text: 'abrir' },
      { id: 3, desc: 'x'.repeat(60) },
      ...Array.from({ length: 10 }, (_, i) => ({ id: 4 + i, text: `item ${i}` })),
    );
    const labels = visibleLabels(t);
    expect(labels).toHaveLength(8);
    expect(labels.slice(0, 2)).toEqual(['Abrir', 'Título']);
    expect(labels[2]).toHaveLength(40);
  });
});

describe('textSignature', () => {
  it('ignores order and case but changes when the texts change', () => {
    const a = tree({ id: 0, text: 'Uno' }, { id: 1, text: 'Dos' });
    const b = tree({ id: 0, text: 'dos' }, { id: 1, text: 'UNO' });
    const c = tree({ id: 0, text: 'Dos' }, { id: 1, text: 'Tres' });
    expect(textSignature(a)).toBe(textSignature(b));
    expect(textSignature(a)).not.toBe(textSignature(c));
  });
});
