/** Voice channel frames (docs/protocol.md section 2). */

export type ClientFrame =
  | { readonly type: 'hello'; readonly client: string; readonly version: string }
  | { readonly type: 'utterance'; readonly id: string; readonly text: string }
  | { readonly type: 'cancel'; readonly id: string }
  | { readonly type: 'reset' };

export type ServerFrame =
  | { readonly type: 'ready'; readonly model: string }
  | { readonly type: 'say'; readonly id: string; readonly text: string }
  | { readonly type: 'status'; readonly id: string; readonly text: string }
  /** `silent`: the order was carried out and there is nothing to say (the model answered `[ok]`). */
  | { readonly type: 'done'; readonly id: string; readonly durationMs: number; readonly silent?: true }
  | { readonly type: 'error'; readonly id?: string; readonly message: string };

const MAX_ID = 128;
const MAX_TEXT = 4000;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function boundedString(value: unknown, max: number): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= max;
}

/** Validates an incoming frame; returns a human-readable reason when invalid. */
export function parseClientFrame(raw: string): ClientFrame | { readonly invalid: string } {
  let data: unknown;
  try {
    data = JSON.parse(raw);
  } catch {
    return { invalid: 'frame is not valid JSON' };
  }
  if (!isRecord(data)) return { invalid: 'frame must be a JSON object' };
  switch (data['type']) {
    case 'hello':
      return { type: 'hello', client: String(data['client'] ?? ''), version: String(data['version'] ?? '') };
    case 'utterance': {
      const { id, text } = data;
      if (!boundedString(id, MAX_ID)) return { invalid: 'utterance.id must be a non-empty string' };
      if (typeof text !== 'string' || text.length > MAX_TEXT) return { invalid: 'utterance.text must be a string up to 4000 chars' };
      return { type: 'utterance', id, text };
    }
    case 'cancel': {
      const { id } = data;
      if (!boundedString(id, MAX_ID)) return { invalid: 'cancel.id must be a non-empty string' };
      return { type: 'cancel', id };
    }
    case 'reset':
      return { type: 'reset' };
    default:
      return { invalid: `unknown frame type ${JSON.stringify(data['type'])}` };
  }
}
