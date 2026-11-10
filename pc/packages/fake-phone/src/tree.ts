import type { NodeDef, ScreenDef, ScreenSize, ScreenTree, TapEffect, TreeNode } from './types.ts';

export interface FlatScreen {
  readonly tree: ScreenTree;
  /** Tap effect per node id (only scripted nodes). */
  readonly effects: ReadonlyMap<number, TapEffect>;
}

interface Acc {
  readonly nodes: TreeNode[];
  readonly effects: Map<number, TapEffect>;
}

function visit(def: NodeDef, parent: number, acc: Acc, overrides: ReadonlyMap<string, string>): void {
  const id = acc.nodes.length;
  const override = def.resId === undefined ? undefined : overrides.get(def.resId);
  acc.nodes.push({
    id,
    parent,
    cls: def.cls,
    text: override ?? def.text ?? null,
    desc: def.desc ?? null,
    resId: def.resId ?? null,
    bounds: def.bounds,
    flags: def.flags ?? [],
  });
  if (def.onTap) acc.effects.set(id, def.onTap);
  for (const child of def.children ?? []) visit(child, id, acc, overrides);
}

/**
 * Flattens a scripted screen into the preorder node list of the Device API.
 * `textOverrides` maps resId → text typed by the client.
 */
export function flattenScreen(
  screen: ScreenDef,
  size: ScreenSize,
  textOverrides: ReadonlyMap<string, string> = new Map(),
): FlatScreen {
  const acc: Acc = { nodes: [], effects: new Map() };
  visit(screen.root, -1, acc, textOverrides);
  return { tree: { package: screen.package, screen: size, nodes: acc.nodes }, effects: acc.effects };
}

export function centerOf(node: TreeNode): { x: number; y: number } {
  const [l, t, r, b] = node.bounds;
  return { x: Math.round((l + r) / 2), y: Math.round((t + b) / 2) };
}

function contains(node: TreeNode, x: number, y: number): boolean {
  const [l, t, r, b] = node.bounds;
  return x >= l && x < r && y >= t && y < b;
}

/** Deepest clickable node containing the point, or undefined. */
export function hitTest(nodes: readonly TreeNode[], x: number, y: number): TreeNode | undefined {
  let found: TreeNode | undefined;
  for (const node of nodes) {
    if (contains(node, x, y) && node.flags.includes('clickable')) found = node;
  }
  return found;
}

/** Walks up from `id` to the first clickable ancestor (inclusive). */
export function clickableAncestor(nodes: readonly TreeNode[], id: number): TreeNode | undefined {
  let current = nodes[id];
  while (current) {
    if (current.flags.includes('clickable')) return current;
    current = current.parent >= 0 ? nodes[current.parent] : undefined;
  }
  return undefined;
}
