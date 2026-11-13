import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { normalize } from '../text-match.ts';
import type { ScreenTree, TreeNode } from '../types.ts';
import { failure, sleep, text, type ToolContext } from './context.ts';

/*
 * The google.navigation:q= intent geocodes free text poorly ("Puerta del Sol, Madrid" went to a
 * business called "VIP Arena Puerta del Sol"). The documented Maps URL resolves it like the search
 * box. navigate opens the route preview, reads the destination Maps picked, then taps Start, so the
 * assistant can tell the user where it is going. Verified on the real app 2026-11-13.
 */

export const MAPS = 'com.google.android.apps.maps';
export type TravelMode = 'driving' | 'walking' | 'bicycling';

const PREVIEW_POLLS = 10;
const MAX_POLL_MS = 400;
const SWAP_RE = /swap start|invertir|intercambiar/i;
const START_LABELS = ['start', 'iniciar', 'empezar', 'comenzar'];

export function directionsUrl(destination: string, mode: TravelMode, navigateNow: boolean): string {
  const base = `https://www.google.com/maps/dir/?api=1&destination=${encodeURIComponent(destination)}&travelmode=${mode}`;
  return navigateNow ? `${base}&dir_action=navigate` : base;
}

function center(n: TreeNode): number {
  return (n.bounds[1] + n.bounds[3]) / 2;
}

/** Id of the window root a node hangs from (heads-up notifications are separate roots). */
function rootOf(tree: ScreenTree, node: TreeNode): number {
  const byId = new Map(tree.nodes.map((n) => [n.id, n]));
  let current = node;
  for (let guard = 0; guard < tree.nodes.length; guard++) {
    const parent = byId.get(current.parent);
    if (!parent || parent.id === current.id) return current.id;
    current = parent;
  }
  return current.id;
}

/**
 * The destination row sits on the same line as the "swap start and destination" button, in the
 * same window (a WhatsApp heads-up over the header was misread as the destination on 2026-11-13).
 */
export function previewDestination(tree: ScreenTree): string | undefined {
  const swap = tree.nodes.find((n) => SWAP_RE.test(n.desc ?? ''));
  if (!swap) return undefined;
  const root = rootOf(tree, swap);
  const row = tree.nodes.find(
    (n) =>
      n.text &&
      n.bounds[2] <= swap.bounds[0] &&
      center(n) >= swap.bounds[1] &&
      center(n) <= swap.bounds[3] &&
      rootOf(tree, n) === root,
  );
  return row?.text ?? undefined;
}

export function startButton(tree: ScreenTree): TreeNode | undefined {
  return tree.nodes.find((n) => n.flags.includes('clickable') && START_LABELS.includes(normalize(n.text ?? n.desc ?? '')));
}

async function waitForPreview(ctx: ToolContext): Promise<{ destination: string; start: TreeNode } | undefined> {
  for (let i = 0; i < PREVIEW_POLLS; i++) {
    await sleep(Math.min(MAX_POLL_MS, Math.max(1, ctx.config.settleMs)));
    const tree = await ctx.client.tree(true);
    ctx.lastTree = tree;
    if (tree.package !== MAPS) continue;
    const destination = previewDestination(tree);
    const start = startButton(tree);
    if (destination && start) return { destination, start };
  }
  return undefined;
}

/** Opens the route preview, reads the resolved destination and starts navigation. */
export async function navigateWithMaps(ctx: ToolContext, destination: string, mode: TravelMode): Promise<CallToolResult> {
  const preview = await ctx.client.post('/intent', { data: directionsUrl(destination, mode, false), package: MAPS });
  if (!preview.ok) return failure(`Google Maps could not open directions to ${destination} (phone returned ok=false).`);
  const found = await waitForPreview(ctx);
  if (found) {
    await ctx.client.post('/input/tap', { nodeId: found.start.id });
    return text(`ok: navigating to "${found.destination}" as resolved by Google Maps (${mode}). Tell the user the place name; if it is not what they asked for, offer to try a more precise address.`);
  }
  // Screen not readable (phone locked or another layout): start navigation directly with the same URL.
  const direct = await ctx.client.post('/intent', { data: directionsUrl(destination, mode, true), package: MAPS });
  if (!direct.ok) return failure(`Google Maps could not start navigation to ${destination} (phone returned ok=false).`);
  return text(`ok: navigation to ${destination} started (${mode}), destination not verified because the screen could not be read.`);
}
