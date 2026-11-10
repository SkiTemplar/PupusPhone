#!/usr/bin/env node
/** pupusphone-mcp: stdio MCP server. Configuration via PUPUS_PHONE_URL, PUPUS_PHONE_TOKEN, PUPUS_PHONE_TIMEOUT_MS. */
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { loadConfig } from './config.ts';
import { log } from './log.ts';
import { createPhoneServer } from './server.ts';

async function main(): Promise<void> {
  const config = loadConfig();
  const server = createPhoneServer(config);
  await server.connect(new StdioServerTransport());
  log.info(`ready, phone at ${config.phoneUrl}`);
}

main().catch((err: unknown) => {
  log.error(err instanceof Error ? err.message : String(err));
  process.exit(1);
});
