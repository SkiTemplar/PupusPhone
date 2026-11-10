import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import type { ScreenTree, Screenshot } from '../types.ts';
import { renderScreen } from '../render.ts';
import { guarded, readScreen, sleep, text, type ToolContext } from './context.ts';

const READ_SCREEN = `Read what is on the phone screen as compact text: one line per node,
"[id] Class "text" desc:"…" #resourceId @x,y flags", indented by nesting. @x,y is the node centre in
physical pixels (usable by tap/long_press). Flags: click, scroll, edit, focus, checked, sel.
Node ids are only valid until the next screen read. Call this before tapping something you have not seen.
Action tools already return the updated screen (observe=true), so do not call this right after them.`;

const SCREENSHOT = `Capture a JPEG of the screen. Costs many more tokens than read_screen: use it only when
read_screen shows no useful text (games, maps, images, canvas UIs). The image is scaled by "scale";
divide image coordinates by scale to get physical pixels for tap.`;

const WAIT = `Wait for the phone UI to settle (e.g. an app is loading). With "text", poll the screen until a
node whose text or description contains it appears, or until timeout. Returns the screen at the end.`;

function containsText(tree: ScreenTree, needle: string): boolean {
  const target = needle.toLocaleLowerCase('es');
  return tree.nodes.some((n) => [n.text, n.desc].some((v) => v?.toLocaleLowerCase('es').includes(target)));
}

export function registerScreenTools(server: McpServer, ctx: ToolContext): void {
  server.registerTool(
    'read_screen',
    {
      title: 'Read screen',
      description: READ_SCREEN,
      inputSchema: {
        visible_only: z.boolean().optional().describe('Default true. False also lists off-screen nodes of scrollable lists.'),
      },
      annotations: { readOnlyHint: true },
    },
    guarded('read_screen', async ({ visible_only }) => text(await readScreen(ctx, visible_only ?? true))),
  );

  server.registerTool(
    'screenshot',
    {
      title: 'Screenshot',
      description: SCREENSHOT,
      inputSchema: {
        scale: z.number().min(0.1).max(1).optional().describe('0.1-1, default 0.4.'),
        quality: z.number().int().min(10).max(100).optional().describe('JPEG quality 10-100, default 50.'),
      },
      annotations: { readOnlyHint: true },
    },
    guarded('screenshot', async ({ scale, quality }) => {
      const s = scale ?? 0.4;
      const shot = await ctx.client.request<Screenshot>('GET', '/screen/shot', undefined, { scale: s, quality: quality ?? 50 });
      return {
        content: [
          { type: 'image', data: shot.base64, mimeType: shot.mime },
          { type: 'text', text: `${shot.width}x${shot.height} px at scale ${s}; physical = image / ${s}.` },
        ],
      };
    }),
  );

  server.registerTool(
    'wait',
    {
      title: 'Wait',
      description: WAIT,
      inputSchema: {
        ms: z.number().int().min(100).max(15000).optional().describe('Maximum wait in ms, default 1500.'),
        text: z.string().min(1).max(200).optional().describe('Stop as soon as this text is visible.'),
      },
      annotations: { readOnlyHint: true },
    },
    guarded('wait', async ({ ms, text: needle }) => {
      const deadline = Date.now() + (ms ?? 1500);
      if (!needle) {
        await sleep(ms ?? 1500);
        return text(await readScreen(ctx));
      }
      for (;;) {
        const tree = await ctx.client.tree(true);
        ctx.lastTree = tree;
        const screen = renderScreen(tree);
        if (containsText(tree, needle)) return text(`found "${needle}"\n\n${screen}`);
        if (Date.now() + 400 > deadline) return text(`timeout: "${needle}" not visible\n\n${screen}`);
        await sleep(400);
      }
    }),
  );
}
