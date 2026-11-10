import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { z } from 'zod';
import { renderScreen } from '../render.ts';
import { findTextMatches, textSignature, visibleLabels, type TextMatch } from '../text-match.ts';
import type { ScreenTree, TreeNode } from '../types.ts';
import { act, failure, findNode, guarded, nodeId, observeParam, sleep, text, type ToolContext } from './context.ts';

const TAP_TEXT = `Tap the element whose visible text or description contains "query", in one call (no read_screen
needed). Case- and accent-insensitive ("cancion" matches "Canción"). Ranking: exact match first, then
tappable, then top-to-bottom; "index" picks another candidate. Use this whenever you know the label; use tap
with node_id or x,y only for unlabeled elements. On no match it returns the visible labels so you can retry.`;

const WAIT_FOR_TEXT = `Wait until an element whose text or description contains "query" is on screen (e.g. after
opening an app or a search). Polls every ~400 ms; returns the screen as soon as it appears, or an error with
the last screen on timeout.`;

const SCROLL_TO_TEXT = `Scroll a list until an element whose text or description contains "query" is visible,
in one call. Without node_id the first scrollable container is used. Stops when the text appears, when the
list stops changing (end reached) or after max_scrolls. Returns the screen; then use tap_text to tap it.`;

const POLL_MS = 400;
const MAX_LISTED = 5;
const DIRECTIONS = ['up', 'down', 'left', 'right'] as const;

const queryParam = z.string().min(1).max(200).describe('Text to look for, e.g. "Mamá" or "Reproducir".');
const exactParam = z
  .boolean()
  .optional()
  .describe('Default false (substring). True requires the whole text to equal the query, ignoring case and accents.');

function nodeLabel(match: TextMatch): string {
  return `node ${match.node.id} "${match.label}"`;
}

function listMatches(matches: readonly TextMatch[]): string {
  const listed = matches.slice(0, MAX_LISTED).map((m, i) => `${i}: [${m.node.id}] "${m.label}"`);
  const more = matches.length > MAX_LISTED ? `, … ${matches.length - MAX_LISTED} more` : '';
  return `${listed.join(', ')}${more}`;
}

function noMatch(tree: ScreenTree, query: string, clickableOnly: boolean): CallToolResult {
  const labels = visibleLabels(tree).map((l) => `"${l}"`).join(', ');
  const plainHits = clickableOnly ? findTextMatches(tree, query) : [];
  const hint = plainHits.length > 0
    ? ` ${plainHits.length} non-tappable element(s) contain it (${listMatches(plainHits)}); retry with clickable_only=false.`
    : '';
  return failure(`no element matches "${query}" on screen (app=${tree.package ?? 'unknown'}).${hint}\nVisible labels: ${labels || '(none)'}`);
}

async function freshTree(ctx: ToolContext): Promise<ScreenTree> {
  const tree = await ctx.client.tree(true);
  ctx.lastTree = tree;
  return tree;
}

function registerTapText(server: McpServer, ctx: ToolContext): void {
  server.registerTool(
    'tap_text',
    {
      title: 'Tap by text',
      description: TAP_TEXT,
      inputSchema: {
        query: queryParam,
        exact: exactParam,
        index: z.number().int().min(0).max(50).optional().describe('Default 0: which ranked candidate to tap when several match.'),
        clickable_only: z.boolean().optional().describe('Default true: ignore matches with nothing tappable (itself or an ancestor).'),
        observe: observeParam,
      },
    },
    guarded('tap_text', async ({ query, exact, index, clickable_only, observe }) => {
      const clickableOnly = clickable_only ?? true;
      const tree = await freshTree(ctx);
      const matches = findTextMatches(tree, query, { exact: exact ?? false, clickableOnly });
      if (matches.length === 0) return noMatch(tree, query, clickableOnly);
      const chosen = matches[index ?? 0];
      if (!chosen) return failure(`index ${index} out of range: ${matches.length} match(es) for "${query}": ${listMatches(matches)}`);
      const others = matches.length > 1 ? ` (${matches.length} matches: ${listMatches(matches)}; pass index to pick another)` : '';
      return act(ctx, `tapped ${nodeLabel(chosen)}${others}`, () => ctx.client.post('/input/tap', { nodeId: chosen.node.id }), observe);
    }),
  );
}

function registerWaitForText(server: McpServer, ctx: ToolContext): void {
  server.registerTool(
    'wait_for_text',
    {
      title: 'Wait for text',
      description: WAIT_FOR_TEXT,
      inputSchema: {
        query: queryParam,
        timeout_ms: z.number().int().min(100).max(30000).optional().describe('Maximum wait in ms, default 8000.'),
        exact: exactParam,
      },
      annotations: { readOnlyHint: true },
    },
    guarded('wait_for_text', async ({ query, timeout_ms, exact }) => {
      const deadline = Date.now() + (timeout_ms ?? 8000);
      for (;;) {
        const tree = await freshTree(ctx);
        const [first] = findTextMatches(tree, query, { exact: exact ?? false });
        if (first) return text(`found "${query}": ${nodeLabel(first)}\n\n${renderScreen(tree)}`);
        if (Date.now() + POLL_MS > deadline) {
          return failure(`timeout: "${query}" not visible after ${timeout_ms ?? 8000} ms\n\n${renderScreen(tree)}`);
        }
        await sleep(POLL_MS);
      }
    }),
  );
}

/** Finds the same container in a newer tree: ids can shift between reads, class+resId+bounds do not. */
function relocate(tree: ScreenTree, target: TreeNode): TreeNode | undefined {
  const same = (n: TreeNode): boolean =>
    n.cls === target.cls && n.resId === target.resId && n.bounds.every((v, i) => v === target.bounds[i]);
  return tree.nodes.find(same) ?? tree.nodes.find((n) => n.id === target.id && n.flags.includes('scrollable'));
}

function scrollTarget(tree: ScreenTree, pinned: TreeNode | undefined): TreeNode | undefined {
  if (pinned) return relocate(tree, pinned);
  return tree.nodes.find((n) => n.flags.includes('scrollable'));
}

function registerScrollToText(server: McpServer, ctx: ToolContext): void {
  server.registerTool(
    'scroll_to_text',
    {
      title: 'Scroll to text',
      description: SCROLL_TO_TEXT,
      inputSchema: {
        query: queryParam,
        direction: z.enum(DIRECTIONS).optional().describe('Default down. down/right = forward.'),
        max_scrolls: z.number().int().min(1).max(30).optional().describe('Default 8.'),
        node_id: nodeId.optional().describe('Scrollable container from the latest screen; default the first scrollable.'),
        exact: exactParam,
      },
    },
    guarded('scroll_to_text', async ({ query, direction, max_scrolls, node_id, exact }) => {
      const pinned = node_id === undefined ? undefined : findNode(ctx, node_id);
      const maxScrolls = max_scrolls ?? 8;
      let tree = await freshTree(ctx);
      for (let scrolls = 0; ; scrolls++) {
        const [first] = findTextMatches(tree, query, { exact: exact ?? false });
        if (first) return text(`found "${query}" after ${scrolls} scroll(s): ${nodeLabel(first)}\n\n${renderScreen(tree)}`);
        if (scrolls >= maxScrolls) return failure(`"${query}" not found after ${scrolls} scroll(s)\n\n${renderScreen(tree)}`);
        const target = scrollTarget(tree, pinned);
        if (!target) return failure(`no scrollable container on screen to look for "${query}"\n\n${renderScreen(tree)}`);
        const { ok } = await ctx.client.post('/input/scroll', { direction: direction ?? 'down', nodeId: target.id });
        if (!ok) return failure(`could not scroll ${nodeLabelOf(target)}; "${query}" not visible\n\n${renderScreen(tree)}`);
        await sleep(ctx.config.settleMs);
        const before = textSignature(tree);
        tree = await freshTree(ctx);
        if (textSignature(tree) === before) {
          return failure(`"${query}" not found: the list stopped changing after ${scrolls + 1} scroll(s) (end reached)\n\n${renderScreen(tree)}`);
        }
      }
    }),
  );
}

function nodeLabelOf(node: TreeNode): string {
  const label = node.text ?? node.desc ?? node.resId;
  return label ? `node ${node.id} "${label}"` : `node ${node.id}`;
}

export function registerTextTools(server: McpServer, ctx: ToolContext): void {
  registerTapText(server, ctx);
  registerWaitForText(server, ctx);
  registerScrollToText(server, ctx);
}
