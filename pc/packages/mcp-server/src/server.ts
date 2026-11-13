import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { McpConfig } from './config.ts';
import { PhoneClient } from './phone-client.ts';
import { registerAppTools } from './tools/apps.ts';
import type { ToolContext } from './tools/context.ts';
import { registerInputTools } from './tools/input.ts';
import { registerMemoryTools } from './tools/memory.ts';
import { registerScreenTools } from './tools/screen.ts';
import { registerShortcutTools } from './tools/shortcuts.ts';
import { registerTextTools } from './tools/text.ts';

export const SERVER_NAME = 'phone';
export const SERVER_VERSION = '0.1.0';

const INSTRUCTIONS = `Controls the user's Android phone (PupusPhone). Fastest path first: one-call shortcuts
(play_music, now_playing, read_notifications, find_contact, call, whatsapp_send, navigate) beat everything else;
then open_url deep links and media keys beat UI navigation; launch_app accepts a visible name; tap_text taps by label without a prior read_screen. Action tools return the updated screen, so a
separate read_screen is rarely needed. Never unlock the phone or change security settings, and confirm with the
user before sending a message or placing a call.`;

/** Builds the MCP server with every phone tool registered. */
export function createPhoneServer(config: McpConfig, client: PhoneClient = new PhoneClient(config)): McpServer {
  const server = new McpServer({ name: SERVER_NAME, version: SERVER_VERSION }, { instructions: INSTRUCTIONS });
  const ctx: ToolContext = { client, config, lastTree: null };
  registerScreenTools(server, ctx);
  registerInputTools(server, ctx);
  registerTextTools(server, ctx);
  registerAppTools(server, ctx);
  registerShortcutTools(server, ctx);
  if (config.memoryFile) registerMemoryTools(server, config.memoryFile);
  return server;
}
