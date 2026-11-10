import type { ScreenTree, TreeNode } from './types.ts';

/** Options for matching a query against the text/desc of screen nodes. */
export interface MatchOptions {
  /** Whole-string equality after normalization instead of substring containment. */
  readonly exact?: boolean;
  /** Drop matches with no clickable node (itself or an ancestor) to receive a tap. */
  readonly clickableOnly?: boolean;
}

export interface TextMatch {
  readonly node: TreeNode;
  /** The text or desc value that matched (original casing and accents). */
  readonly label: string;
  /** Normalized label equals the normalized query. */
  readonly isExact: boolean;
  /** The node or one of its ancestors is clickable. */
  readonly isClickable: boolean;
}

const DIACRITICS = /\p{M}/gu;

/** Lower-cases, strips diacritics (NFD) and collapses whitespace: "  Canción " → "cancion". */
export function normalize(value: string): string {
  return value.normalize('NFD').replace(DIACRITICS, '').toLowerCase().replace(/\s+/g, ' ').trim();
}

function hasClickable(byId: ReadonlyMap<number, TreeNode>, node: TreeNode): boolean {
  const seen = new Set<number>();
  let current: TreeNode | undefined = node;
  while (current && !seen.has(current.id)) {
    if (current.flags.includes('clickable')) return true;
    seen.add(current.id);
    current = byId.get(current.parent);
  }
  return false;
}

function labelsOf(node: TreeNode): readonly string[] {
  return [node.text, node.desc].filter((v): v is string => v !== null && v.trim() !== '');
}

function matchNode(node: TreeNode, query: string, exact: boolean): { label: string; isExact: boolean } | null {
  let partial: string | null = null;
  for (const label of labelsOf(node)) {
    const value = normalize(label);
    if (value === query) return { label, isExact: true };
    if (!exact && partial === null && value.includes(query)) partial = label;
  }
  return partial === null ? null : { label: partial, isExact: false };
}

/** Exact first, then clickable, then top-to-bottom, then left-to-right, then tree order. */
function compareMatches(a: TextMatch, b: TextMatch): number {
  if (a.isExact !== b.isExact) return a.isExact ? -1 : 1;
  if (a.isClickable !== b.isClickable) return a.isClickable ? -1 : 1;
  const [al, at] = a.node.bounds;
  const [bl, bt] = b.node.bounds;
  if (at !== bt) return at - bt;
  if (al !== bl) return al - bl;
  return a.node.id - b.node.id;
}

/**
 * Nodes whose text or desc contains `query` (case- and accent-insensitive), ranked best first.
 * Returns an empty list for a query that normalizes to the empty string.
 */
export function findTextMatches(tree: ScreenTree, query: string, options: MatchOptions = {}): readonly TextMatch[] {
  const needle = normalize(query);
  if (needle === '') return [];
  const byId = new Map(tree.nodes.map((n) => [n.id, n] as const));
  const matches: TextMatch[] = [];
  for (const node of tree.nodes) {
    const hit = matchNode(node, needle, options.exact ?? false);
    if (!hit) continue;
    const isClickable = hasClickable(byId, node);
    if (options.clickableOnly && !isClickable) continue;
    matches.push({ node, label: hit.label, isExact: hit.isExact, isClickable });
  }
  return [...matches].sort(compareMatches);
}

/** Up to `limit` distinct visible labels, tappable ones first, to suggest retries without another read. */
export function visibleLabels(tree: ScreenTree, limit = 8, maxLength = 40): readonly string[] {
  const byId = new Map(tree.nodes.map((n) => [n.id, n] as const));
  const tappable: string[] = [];
  const other: string[] = [];
  const seen = new Set<string>();
  for (const node of tree.nodes) {
    for (const label of labelsOf(node)) {
      const flat = label.replace(/\s+/g, ' ').trim();
      const key = normalize(flat);
      if (seen.has(key)) continue;
      seen.add(key);
      const short = flat.length > maxLength ? `${flat.slice(0, maxLength - 1)}…` : flat;
      (hasClickable(byId, node) ? tappable : other).push(short);
    }
  }
  return [...tappable, ...other].slice(0, limit);
}

/** Order-independent fingerprint of the visible texts, used to detect that a scroll changed nothing. */
export function textSignature(tree: ScreenTree): string {
  const values = new Set(tree.nodes.flatMap((n) => labelsOf(n).map(normalize)));
  return [...values].sort().join('\u0000');
}
