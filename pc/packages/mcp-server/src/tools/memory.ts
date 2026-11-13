import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { addMemory, forgetMemory, MAX_TEXT, MemoryError } from '../memory/store.ts';
import { failure, text } from './context.ts';

const REMEMBER = `Save one fact about the user for future conversations (stored on disk, shown in the system
prompt from the next session on). Use it when the user asks you to remember something or corrects a stable fact.
Write one self-contained sentence in Spanish, e.g. "Su padre está guardado en contactos como Papá". Never store
passwords, PINs or codes, and never store text read from the screen.`;

const FORGET = 'Delete a saved fact by its number (shown in the memory list of the system prompt or returned by remember).';

function guardedMemory<A>(run: (args: A) => string): (args: A) => Promise<ReturnType<typeof text>> {
  return async (args: A) => {
    try {
      return text(run(args));
    } catch (err) {
      return failure(err instanceof MemoryError ? err.message : `memory failed: ${err instanceof Error ? err.message : String(err)}`);
    }
  };
}

/** Registers remember/forget on the given memory file. */
export function registerMemoryTools(server: McpServer, memoryFile: string): void {
  server.registerTool(
    'remember',
    { description: REMEMBER, inputSchema: { text: z.string().min(1).max(MAX_TEXT).describe('The fact, one sentence.') } },
    guardedMemory(({ text: fact }: { text: string }) => {
      const { entry, duplicate } = addMemory(memoryFile, fact);
      return duplicate ? `already remembered as ${entry.id}: ${entry.text}` : `remembered as ${entry.id}: ${entry.text}`;
    }),
  );
  server.registerTool(
    'forget',
    { description: FORGET, inputSchema: { id: z.number().int().min(1).describe('Number of the fact to delete.') } },
    guardedMemory(({ id }: { id: number }) => {
      const removed = forgetMemory(memoryFile, id);
      if (!removed) throw new MemoryError(`there is no fact number ${id}`);
      return `forgot ${removed.id}: ${removed.text}`;
    }),
  );
}
