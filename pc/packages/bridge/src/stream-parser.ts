/**
 * Parser for Claude Code `--output-format stream-json --verbose --include-partial-messages` lines.
 * Only the events the bridge needs are surfaced; everything else maps to `ignore`.
 */

export type ClaudeEvent =
  | { readonly kind: 'init'; readonly sessionId: string; readonly model: string; readonly tools: readonly string[] }
  | { readonly kind: 'text'; readonly text: string }
  /** A tool_use content block started streaming: text held so far was narration. */
  | { readonly kind: 'tool_start' }
  /** End of a text content block: flush the sentence chunker. */
  | { readonly kind: 'text_end' }
  | { readonly kind: 'tool'; readonly id: string; readonly name: string; readonly input: Readonly<Record<string, unknown>> }
  | {
      readonly kind: 'result';
      readonly subtype: string;
      readonly isError: boolean;
      readonly durationMs: number | undefined;
      readonly sessionId: string | undefined;
      readonly text: string | undefined;
    }
  | { readonly kind: 'control_response'; readonly requestId: string; readonly success: boolean; readonly error: string | undefined }
  | { readonly kind: 'invalid'; readonly line: string }
  | { readonly kind: 'ignore' };

type Json = Record<string, unknown>;

const IGNORE: ClaudeEvent = { kind: 'ignore' };

function isObject(value: unknown): value is Json {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function str(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function parseStreamEvent(msg: Json): ClaudeEvent {
  if (msg['parent_tool_use_id'] !== null && msg['parent_tool_use_id'] !== undefined) return IGNORE;
  const event = msg['event'];
  if (!isObject(event)) return IGNORE;
  if (event['type'] === 'content_block_delta') {
    const delta = event['delta'];
    if (isObject(delta) && delta['type'] === 'text_delta' && typeof delta['text'] === 'string') {
      return { kind: 'text', text: delta['text'] };
    }
  }
  if (event['type'] === 'content_block_start') {
    const block = event['content_block'];
    if (isObject(block) && block['type'] === 'tool_use') return { kind: 'tool_start' };
  }
  if (event['type'] === 'content_block_stop') return { kind: 'text_end' };
  return IGNORE;
}

/** Full assistant messages carry complete tool_use inputs (partial deltas only carry JSON fragments). */
function parseAssistant(msg: Json): ClaudeEvent[] {
  if (msg['parent_tool_use_id'] !== null && msg['parent_tool_use_id'] !== undefined) return [];
  const message = msg['message'];
  if (!isObject(message) || !Array.isArray(message['content'])) return [];
  const events: ClaudeEvent[] = [];
  for (const block of message['content'] as unknown[]) {
    if (!isObject(block) || block['type'] !== 'tool_use') continue;
    const input = isObject(block['input']) ? block['input'] : {};
    events.push({ kind: 'tool', id: str(block['id']) ?? '', name: str(block['name']) ?? 'unknown', input });
  }
  return events;
}

function parseResult(msg: Json): ClaudeEvent {
  return {
    kind: 'result',
    subtype: str(msg['subtype']) ?? 'unknown',
    isError: msg['is_error'] === true || str(msg['subtype']) !== 'success',
    durationMs: typeof msg['duration_ms'] === 'number' ? msg['duration_ms'] : undefined,
    sessionId: str(msg['session_id']),
    text: str(msg['result']),
  };
}

function parseControlResponse(msg: Json): ClaudeEvent {
  const response = msg['response'];
  if (!isObject(response)) return IGNORE;
  return {
    kind: 'control_response',
    requestId: str(response['request_id']) ?? '',
    success: response['subtype'] === 'success',
    error: str(response['error']),
  };
}

function parseSystem(msg: Json): ClaudeEvent {
  if (msg['subtype'] !== 'init') return IGNORE;
  const tools = Array.isArray(msg['tools']) ? msg['tools'].filter((t): t is string => typeof t === 'string') : [];
  return { kind: 'init', sessionId: str(msg['session_id']) ?? '', model: str(msg['model']) ?? '', tools };
}

/** Parses one stdout line into zero or more bridge events. */
export function parseLine(line: string): ClaudeEvent[] {
  const trimmed = line.trim();
  if (trimmed === '') return [];
  let msg: unknown;
  try {
    msg = JSON.parse(trimmed);
  } catch {
    return [{ kind: 'invalid', line: trimmed.slice(0, 200) }];
  }
  if (!isObject(msg)) return [{ kind: 'invalid', line: trimmed.slice(0, 200) }];
  switch (msg['type']) {
    case 'stream_event':
      return [parseStreamEvent(msg)].filter((e) => e.kind !== 'ignore');
    case 'assistant':
      return parseAssistant(msg);
    case 'result':
      return [parseResult(msg)];
    case 'control_response':
      return [parseControlResponse(msg)];
    case 'system':
      return [parseSystem(msg)].filter((e) => e.kind !== 'ignore');
    default:
      return [];
  }
}

/** Accumulates stdout chunks and yields complete lines. */
export class LineSplitter {
  private pending = '';

  push(chunk: string): string[] {
    this.pending += chunk;
    const lines = this.pending.split('\n');
    this.pending = lines.pop() ?? '';
    return lines.map((l) => l.replace(/\r$/, '')).filter((l) => l !== '');
  }
}
