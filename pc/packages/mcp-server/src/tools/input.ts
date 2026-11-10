import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { center } from '../render.ts';
import { act, coord, describeNode, failure, findNode, guarded, nodeId, observeParam, type ToolContext } from './context.ts';

const TAP = `Tap the screen. Prefer node_id (from the latest screen read: clicks the node or its first clickable
ancestor, robust to layout shifts); use x,y only for elements without a node. Prefer tap_text when you know the
label: it finds and taps in one call. Returns the updated screen.`;

const LONG_PRESS = 'Long-press a point (x,y) or the centre of a node (node_id). Returns the updated screen.';

const SWIPE = `Swipe from (x1,y1) to (x2,y2) in physical pixels. For lists prefer scroll; use swipe for carousels,
pull-to-refresh, dismissing or seek bars.`;

const SCROLL = `Scroll a scrollable container one page. direction down/right = forward. Without node_id the first
visible scrollable node is used. Returns the updated screen.`;

const TYPE_TEXT = `Set the text of an editable field (replaces its content). Without node_id the focused input is
used; tap the field first if nothing is focused. submit=true presses the keyboard action (search/send) afterwards.
Never type into a field that would send a message without the user's explicit confirmation.`;

const PRESS = `Press a global system button: back, home, recents, notifications (open shade, also to read
notifications), quick_settings, lock_screen. For music use the media tool instead.`;

const GLOBAL_ACTIONS = ['back', 'home', 'recents', 'notifications', 'quick_settings', 'lock_screen'] as const;
const DIRECTIONS = ['up', 'down', 'left', 'right'] as const;

const duration = z.number().int().min(1).max(10000).optional();

function registerTap(server: McpServer, ctx: ToolContext): void {
  server.registerTool(
    'tap',
    {
      title: 'Tap',
      description: TAP,
      inputSchema: { node_id: nodeId.optional(), x: coord.optional(), y: coord.optional(), observe: observeParam },
    },
    guarded('tap', async ({ node_id, x, y, observe }) => {
      if (node_id !== undefined) {
        return act(ctx, `tapped ${describeNode(ctx, node_id)}`, () => ctx.client.post('/input/tap', { nodeId: node_id }), observe);
      }
      if (x === undefined || y === undefined) return failure('Provide node_id, or both x and y.');
      return act(ctx, `tapped ${x},${y}`, () => ctx.client.post('/input/tap', { x, y }), observe);
    }),
  );
}

function registerGestures(server: McpServer, ctx: ToolContext): void {
  server.registerTool(
    'long_press',
    {
      title: 'Long press',
      description: LONG_PRESS,
      inputSchema: { node_id: nodeId.optional(), x: coord.optional(), y: coord.optional(), duration_ms: duration, observe: observeParam },
    },
    guarded('long_press', async ({ node_id, x, y, duration_ms, observe }) => {
      const point = node_id !== undefined ? center(findNode(ctx, node_id)) : x !== undefined && y !== undefined ? { x, y } : null;
      if (!point) return failure('Provide node_id, or both x and y.');
      const body = { ...point, durationMs: duration_ms };
      return act(ctx, `long-pressed ${point.x},${point.y}`, () => ctx.client.post('/input/long_press', body), observe);
    }),
  );

  server.registerTool(
    'swipe',
    {
      title: 'Swipe',
      description: SWIPE,
      inputSchema: { x1: coord, y1: coord, x2: coord, y2: coord, duration_ms: duration, observe: observeParam },
    },
    guarded('swipe', async ({ x1, y1, x2, y2, duration_ms, observe }) => {
      const body = { x1, y1, x2, y2, durationMs: duration_ms };
      return act(ctx, `swiped ${x1},${y1} -> ${x2},${y2}`, () => ctx.client.post('/input/swipe', body), observe);
    }),
  );

  server.registerTool(
    'scroll',
    {
      title: 'Scroll',
      description: SCROLL,
      inputSchema: { direction: z.enum(DIRECTIONS), node_id: nodeId.optional(), observe: observeParam },
    },
    guarded('scroll', async ({ direction, node_id, observe }) =>
      act(ctx, `scrolled ${direction}`, () => ctx.client.post('/input/scroll', { direction, nodeId: node_id }), observe)),
  );
}

function registerKeys(server: McpServer, ctx: ToolContext): void {
  server.registerTool(
    'type_text',
    {
      title: 'Type text',
      description: TYPE_TEXT,
      inputSchema: {
        text: z.string().max(2000),
        node_id: nodeId.optional(),
        submit: z.boolean().optional().describe('Press the IME action (search/send/go) after typing.'),
        observe: observeParam,
      },
    },
    guarded('type_text', async ({ text, node_id, submit, observe }) =>
      act(ctx, `typed ${JSON.stringify(text)}${submit ? ' and submitted' : ''}`, () =>
        ctx.client.post('/input/text', { text, nodeId: node_id, submit }), observe)),
  );

  server.registerTool(
    'press',
    {
      title: 'Press system button',
      description: PRESS,
      inputSchema: { action: z.enum(GLOBAL_ACTIONS), observe: observeParam },
    },
    guarded('press', async ({ action, observe }) =>
      act(ctx, `pressed ${action}`, () => ctx.client.post('/input/global', { action }), observe)),
  );
}

export function registerInputTools(server: McpServer, ctx: ToolContext): void {
  registerTap(server, ctx);
  registerGestures(server, ctx);
  registerKeys(server, ctx);
}
