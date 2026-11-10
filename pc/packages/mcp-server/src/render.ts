import type { ScreenTree, TreeNode } from './types.ts';

const FLAG_ABBREV: Readonly<Record<string, string>> = {
  clickable: 'click',
  scrollable: 'scroll',
  editable: 'edit',
  focused: 'focus',
  checked: 'checked',
  selected: 'sel',
};

export interface RenderOptions {
  /** Max characters per text/desc value before truncation. */
  readonly maxText?: number;
  /** Max node lines; the rest is summarised in one line. */
  readonly maxNodes?: number;
}

function clean(value: string, max: number): string {
  const flat = value.replace(/\s+/g, ' ').trim().replace(/"/g, '\\"');
  return flat.length > max ? `${flat.slice(0, max - 1)}…` : flat;
}

/** Pure layout containers (no text, desc, resId or flags) add noise: they are hidden and do not indent. */
function isVisible(node: TreeNode): boolean {
  return node.text !== null || node.desc !== null || node.resId !== null || node.flags.length > 0;
}

export function center(node: TreeNode): { x: number; y: number } {
  const [l, t, r, b] = node.bounds;
  return { x: Math.round((l + r) / 2), y: Math.round((t + b) / 2) };
}

function renderLine(node: TreeNode, depth: number, maxText: number): string {
  const parts = [`[${node.id}]`, node.cls];
  const text = node.text ? clean(node.text, maxText) : null;
  const desc = node.desc ? clean(node.desc, maxText) : null;
  if (text) parts.push(`"${text}"`);
  if (desc && desc !== text) parts.push(`desc:"${desc}"`);
  if (node.resId) parts.push(`#${node.resId}`);
  const c = center(node);
  parts.push(`@${c.x},${c.y}`);
  const flags = node.flags.map((f) => FLAG_ABBREV[f] ?? f);
  if (flags.length > 0) parts.push(flags.join(','));
  return '  '.repeat(depth) + parts.join(' ');
}

function depths(nodes: readonly TreeNode[]): ReadonlyMap<number, number> {
  const byId = new Map(nodes.map((n) => [n.id, n] as const));
  const result = new Map<number, number>();
  for (const node of nodes) {
    const parent = byId.get(node.parent);
    const parentDepth = parent ? (result.get(parent.id) ?? 0) : -1;
    const step = parent && isVisible(parent) ? 1 : 0;
    result.set(node.id, parent ? parentDepth + step : 0);
  }
  return result;
}

/**
 * Compact, token-cheap rendering of the screen tree: one line per meaningful node,
 * `[id] Class "text" desc:"…" #resId @centerX,centerY flags`, indented by depth.
 */
export function renderScreen(tree: ScreenTree, options: RenderOptions = {}): string {
  const maxText = options.maxText ?? 80;
  const maxNodes = options.maxNodes ?? 250;
  const visible = tree.nodes.filter(isVisible);
  const depthOf = depths(tree.nodes);
  const header = `app=${tree.package ?? 'unknown'} screen=${tree.screen.width}x${tree.screen.height} nodes=${visible.length}`;
  if (visible.length === 0) return `${header}\n(no interactive or text nodes; try screenshot)`;
  const lines = visible.slice(0, maxNodes).map((n) => renderLine(n, depthOf.get(n.id) ?? 0, maxText));
  if (visible.length > maxNodes) lines.push(`… ${visible.length - maxNodes} more nodes omitted; scroll or use screenshot`);
  return [header, ...lines].join('\n');
}
