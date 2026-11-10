import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { z } from 'zod';
import type { McpConfig } from '../config.ts';
import { log } from '../log.ts';
import { PhoneError, type PhoneClient } from '../phone-client.ts';
import { renderScreen } from '../render.ts';
import type { ScreenTree, TreeNode } from '../types.ts';

/** Shared state for tool handlers: the client and the latest tree (node ids are valid until the next read). */
export interface ToolContext {
  readonly client: PhoneClient;
  readonly config: McpConfig;
  lastTree: ScreenTree | null;
}

export const coord = z.number().int().min(0).max(20000);
export const nodeId = z.number().int().min(0).max(100000);

export const observeParam = z
  .boolean()
  .optional()
  .describe('Default true: wait a short settle delay and return the updated screen in the same result, so no read_screen call is needed after the action. Use false only when chaining several actions blindly.');

export function text(value: string): CallToolResult {
  return { content: [{ type: 'text', text: value }] };
}

export function failure(value: string): CallToolResult {
  return { content: [{ type: 'text', text: value }], isError: true };
}

export const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

export async function readScreen(ctx: ToolContext, visibleOnly = true): Promise<string> {
  const tree = await ctx.client.tree(visibleOnly);
  ctx.lastTree = tree;
  return renderScreen(tree);
}

/** Runs an action and, when `observe` is not false, appends the refreshed screen. */
export async function act(
  ctx: ToolContext,
  summary: string,
  run: () => Promise<{ ok: boolean }>,
  observe: boolean | undefined,
): Promise<CallToolResult> {
  const { ok } = await run();
  const head = ok ? `ok: ${summary}` : `not done (phone returned ok=false): ${summary}`;
  if (observe === false) return text(head);
  await sleep(ctx.config.settleMs);
  try {
    return text(`${head}\n\n${await readScreen(ctx)}`);
  } catch (err) {
    const reason = err instanceof Error ? err.message : String(err);
    return text(`${head}\n\n(could not refresh the screen: ${reason})`);
  }
}

/** Wraps a handler so PhoneError and unexpected failures become tool errors instead of protocol errors. */
export function guarded<A>(name: string, handler: (args: A) => Promise<CallToolResult>): (args: A) => Promise<CallToolResult> {
  return async (args: A) => {
    try {
      return await handler(args);
    } catch (err) {
      if (err instanceof PhoneError) return failure(err.message);
      const message = err instanceof Error ? err.message : String(err);
      log.error(`${name} failed: ${message}`);
      return failure(`${name} failed: ${message}`);
    }
  };
}

export function findNode(ctx: ToolContext, id: number): TreeNode {
  const node = ctx.lastTree?.nodes.find((n) => n.id === id);
  if (!node) throw new PhoneError(`Node ${id} is not in the latest screen. Call read_screen and use a fresh id.`);
  return node;
}

export function describeNode(ctx: ToolContext, id: number): string {
  const node = ctx.lastTree?.nodes.find((n) => n.id === id);
  const label = node?.text ?? node?.desc ?? node?.resId;
  return label ? `node ${id} "${label}"` : `node ${id}`;
}
