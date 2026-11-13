import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { createPhoneServer } from '../src/server.ts';
import type { McpConfig } from '../src/config.ts';

export const TOKEN = 'test-token-0123456789abcdef';

export interface Connected {
  readonly client: Client;
  close(): Promise<void>;
}

/** Connects a real MCP client to the phone server over an in-memory transport pair. */
export async function connect(config: Partial<McpConfig> & Pick<McpConfig, 'phoneUrl'>): Promise<Connected> {
  const server = createPhoneServer({ token: TOKEN, timeoutMs: 2000, settleMs: 5, defaultCountryCode: '34', ...config });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  const client = new Client({ name: 'test', version: '0.0.0' });
  await Promise.all([server.connect(serverTransport), client.connect(clientTransport)]);
  return {
    client,
    close: async () => {
      await client.close();
      await server.close();
    },
  };
}

export async function call(client: Client, name: string, args: Record<string, unknown> = {}): Promise<CallToolResult> {
  return (await client.callTool({ name, arguments: args })) as CallToolResult;
}

export function textOf(result: CallToolResult): string {
  return result.content
    .filter((c): c is { type: 'text'; text: string } => c.type === 'text')
    .map((c) => c.text)
    .join('\n');
}

/** Finds the id of the first rendered node whose line contains `needle`. */
export function idOf(screen: string, needle: string): number {
  const line = screen.split('\n').find((l) => l.includes(needle));
  const match = line?.match(/\[(\d+)\]/);
  if (!match?.[1]) throw new Error(`no node containing ${needle} in:\n${screen}`);
  return Number(match[1]);
}
