import type { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { z } from 'zod';
import { chooseContact, describeContact, isPhoneNumber, pickNumber, toDialable, toInternational } from '../contacts.ts';
import { findTextMatches, normalize, visibleLabels, type TextMatch } from '../text-match.ts';
import type { CallResponse, Contact, ScreenTree } from '../types.ts';
import { act, failure, guarded, sleep, text, type ToolContext } from './context.ts';

const CALL = `Place a phone call in ONE call. contact = a phone number or a contact name as the user said it
(looked up in the contacts; a mobile number is preferred). If the name matches several contacts or none,
nothing is dialed and the candidates are returned: ask the user and retry with the full name or the number.
ONLY use after the user has confirmed by voice who to call.`;

const WHATSAPP_SEND = `Send a WhatsApp message in ONE call: opens the chat with the text prefilled and taps Send.
contact = phone number or contact name, resolved like call (several or no matching contacts: nothing is sent and
the candidates are returned). ONLY use after the user has explicitly confirmed by voice both the recipient and
the exact text. Returns the chat screen after sending.`;

const WHATSAPP_PACKAGE = 'com.whatsapp';
const SEND_LABELS = ['Enviar', 'Send'] as const;
const SEND_WAIT_MS = 6000;
const POLL_MS = 400;

const contactParam = z
  .string()
  .trim()
  .min(1)
  .max(64)
  .describe('Phone number (e.g. "+34 612 345 678") or contact name as the user said it (e.g. "Mamá").');

interface Recipient {
  readonly name: string | null;
  readonly number: string;
}

type Resolution = { readonly ok: true; readonly recipient: Recipient } | { readonly ok: false; readonly result: CallToolResult };

function describeRecipient(r: Recipient): string {
  return r.name ? `${r.name} (${r.number})` : r.number;
}

function fromContact(contact: Contact, nothingDone: string): Resolution {
  const picked = pickNumber(contact);
  const number = picked ? toDialable(picked.number) : null;
  if (!number) return { ok: false, result: failure(`${contact.name} has no usable phone number. ${nothingDone}`) };
  return { ok: true, recipient: { name: contact.name, number } };
}

/** Number as given, or the single contact matching the name; otherwise a failure listing the candidates. */
async function resolveRecipient(ctx: ToolContext, contact: string, nothingDone: string): Promise<Resolution> {
  if (isPhoneNumber(contact)) return { ok: true, recipient: { name: null, number: contact } };
  const { contacts } = await ctx.client.request<{ contacts: Contact[] }>('GET', '/contacts', undefined, { q: contact });
  if (contacts.length === 0) {
    const message = `no contact matches "${contact}". Ask the user for the name as saved in the phone or for the number. ${nothingDone}`;
    return { ok: false, result: failure(message) };
  }
  const chosen = chooseContact(contacts, contact);
  if (Array.isArray(chosen)) {
    const list = chosen.map(describeContact).join('; ');
    const message = `${chosen.length} contacts match "${contact}": ${list}. Ask the user which one and retry with the full name or the number. ${nothingDone}`;
    return { ok: false, result: failure(message) };
  }
  return fromContact(chosen, nothingDone);
}

function registerCall(server: McpServer, ctx: ToolContext): void {
  server.registerTool(
    'call',
    { title: 'Call', description: CALL, inputSchema: { contact: contactParam } },
    guarded('call', async ({ contact }) => {
      const resolved = await resolveRecipient(ctx, contact, 'Nothing was dialed.');
      if (!resolved.ok) return resolved.result;
      const who = describeRecipient(resolved.recipient);
      const res = await ctx.client.request<CallResponse>('POST', '/call', { number: resolved.recipient.number });
      if (!res.ok) return failure(`the phone could not start the call to ${who}.`);
      if (res.mode === 'call') return text(`calling ${who}`);
      return text(`dialer opened for ${who}, user must tap Call (PupusPhone has no permission to call directly)`);
    }),
  );
}

function findSendButton(tree: ScreenTree): TextMatch | undefined {
  if (tree.package !== WHATSAPP_PACKAGE) return undefined;
  for (const label of SEND_LABELS) {
    const target = normalize(label);
    const hit = findTextMatches(tree, label, { exact: true, clickableOnly: true })
      .find((m) => m.node.desc !== null && normalize(m.node.desc) === target);
    if (hit) return hit;
  }
  return undefined;
}

/** Polls the screen until WhatsApp shows its send button or `waitMs` elapses. */
async function waitForSendButton(ctx: ToolContext, waitMs: number): Promise<{ tree: ScreenTree; button: TextMatch | undefined }> {
  const deadline = Date.now() + waitMs;
  await sleep(ctx.config.settleMs);
  for (;;) {
    const tree = await ctx.client.tree(true);
    ctx.lastTree = tree;
    const button = findSendButton(tree);
    if (button || Date.now() + POLL_MS > deadline) return { tree, button };
    await sleep(POLL_MS);
  }
}

function buttonMissing(tree: ScreenTree, who: string, waitMs: number): CallToolResult {
  const labels = visibleLabels(tree).map((l) => `"${l}"`).join(', ');
  return failure(
    `opened the WhatsApp chat with ${who} but no "Enviar"/"Send" button appeared within ${waitMs} ms ` +
      `(app=${tree.package ?? 'unknown'}). The message was NOT sent; tell the user.\nVisible labels: ${labels || '(none)'}`,
  );
}

function registerWhatsappSend(server: McpServer, ctx: ToolContext): void {
  server.registerTool(
    'whatsapp_send',
    {
      title: 'Send WhatsApp message',
      description: WHATSAPP_SEND,
      inputSchema: {
        contact: contactParam,
        text: z.string().trim().min(1).max(1000).describe('Exact message text the user confirmed.'),
        wait_ms: z.number().int().min(100).max(15000).optional().describe('Rarely needed: how long to wait for the send button, default 6000.'),
      },
    },
    guarded('whatsapp_send', async ({ contact, text: message, wait_ms }) => {
      const resolved = await resolveRecipient(ctx, contact, 'Nothing was sent.');
      if (!resolved.ok) return resolved.result;
      const who = describeRecipient(resolved.recipient);
      const digits = toInternational(resolved.recipient.number, ctx.config.defaultCountryCode);
      if (!digits) return failure(`${who} is not a valid number for WhatsApp. Nothing was sent.`);
      const data = `https://wa.me/${digits}?text=${encodeURIComponent(message)}`;
      const { ok } = await ctx.client.post('/intent', { data, package: WHATSAPP_PACKAGE });
      if (!ok) return failure(`WhatsApp could not open the chat with ${who}. Nothing was sent.`);
      const waitMs = wait_ms ?? SEND_WAIT_MS;
      const { tree, button } = await waitForSendButton(ctx, waitMs);
      if (!button) return buttonMissing(tree, who, waitMs);
      return act(ctx, `sent WhatsApp message to ${who}`, () => ctx.client.post('/input/tap', { nodeId: button.node.id }), true);
    }),
  );
}

/** call and whatsapp_send: both resolve a contact name to a single number before acting. */
export function registerMessagingTools(server: McpServer, ctx: ToolContext): void {
  registerCall(server, ctx);
  registerWhatsappSend(server, ctx);
}
