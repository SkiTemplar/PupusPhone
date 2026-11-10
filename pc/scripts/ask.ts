/**
 * Text client for a running bridge: sends each argument as one utterance, in order, and prints the
 * frames with their latency. Useful to test the real phone without speaking.
 * Usage: PUPUS_BRIDGE_TOKEN=... npm run ask -- "abre Spotify" "pon Bohemian Rhapsody"
 * Env: PUPUS_BRIDGE_URL (default ws://127.0.0.1:8766/voice), PUPUS_ASK_TIMEOUT_MS (default 120000).
 */
import { WebSocket } from 'ws';
import type { ServerFrame } from '@pupusphone/bridge';

const out = (line: string): void => {
  process.stderr.write(`${line}\n`);
};

function requireToken(): string {
  const token = process.env['PUPUS_BRIDGE_TOKEN'];
  if (!token) throw new Error('PUPUS_BRIDGE_TOKEN is required');
  return token;
}

function askOne(ws: WebSocket, id: string, text: string, timeoutMs: number): Promise<void> {
  return new Promise((resolve, reject) => {
    const sentAt = Date.now();
    const timer = setTimeout(() => finish(new Error(`no done for "${text}" within ${timeoutMs} ms`)), timeoutMs);
    const onMessage = (data: WebSocket.RawData): void => {
      const frame = JSON.parse(data.toString()) as ServerFrame;
      out(`  +${Date.now() - sentAt}ms ${JSON.stringify(frame)}`);
      if (frame.type === 'done' && frame.id === id) finish();
      if (frame.type === 'error' && frame.id === id) finish(new Error(frame.message));
    };
    const finish = (err?: Error): void => {
      clearTimeout(timer);
      ws.off('message', onMessage);
      if (err) reject(err);
      else resolve();
    };
    ws.on('message', onMessage);
    out(`> ${text}`);
    ws.send(JSON.stringify({ type: 'utterance', id, text }));
  });
}

function waitReady(ws: WebSocket): Promise<void> {
  return new Promise((resolve, reject) => {
    ws.once('error', reject);
    ws.on('message', function onReady(data) {
      const frame = JSON.parse(data.toString()) as ServerFrame;
      if (frame.type !== 'ready') return;
      ws.off('message', onReady);
      out(`ready (${frame.model})`);
      resolve();
    });
    ws.on('open', () => ws.send(JSON.stringify({ type: 'hello', client: 'ask', version: '0' })));
  });
}

async function main(): Promise<void> {
  const utterances = process.argv.slice(2);
  if (utterances.length === 0) throw new Error('pass at least one utterance');
  const base = process.env['PUPUS_BRIDGE_URL'] ?? 'ws://127.0.0.1:8766/voice';
  const timeoutMs = Number(process.env['PUPUS_ASK_TIMEOUT_MS'] ?? 120_000);
  const ws = new WebSocket(`${base}?token=${encodeURIComponent(requireToken())}`);
  try {
    await waitReady(ws);
    for (const [index, text] of utterances.entries()) {
      await askOne(ws, `ask${index + 1}`, text, timeoutMs);
    }
  } finally {
    ws.close();
  }
}

main().catch((err: unknown) => {
  out(`ask failed: ${err instanceof Error ? err.message : String(err)}`);
  process.exit(1);
});
