/**
 * End-to-end smoke run with the REAL Claude Code CLI (spends a little of the subscription):
 * fake-phone + bridge + claude, one utterance over WebSocket, prints frames and latency.
 * Usage: npm run smoke -- ["utterance"]   (env PUPUS_MODEL to pick the model)
 */
import { randomBytes } from 'node:crypto';
import { WebSocket } from 'ws';
import { startFakePhone } from '@pupusphone/fake-phone';
import { loadBridgeConfig, startBridge, type ServerFrame } from '@pupusphone/bridge';

const utterance = process.argv[2] ?? '¿Qué app tengo abierta?';
const out = (line: string): void => {
  process.stderr.write(`${line}\n`);
};

async function main(): Promise<void> {
  const phoneToken = randomBytes(32).toString('hex');
  const bridgeToken = randomBytes(24).toString('hex');
  const fake = await startFakePhone({ token: phoneToken, port: 0 });
  fake.phone.goTo('spotify');
  const config = loadBridgeConfig({
    ...process.env,
    PUPUS_BRIDGE_TOKEN: bridgeToken,
    PUPUS_BRIDGE_HOST: '127.0.0.1',
    PUPUS_BRIDGE_PORT: '0',
    PUPUS_PHONE_URL: fake.url,
    PUPUS_PHONE_TOKEN: phoneToken,
  });
  const bridge = await startBridge(config);
  const ws = new WebSocket(`ws://127.0.0.1:${bridge.port}/voice?token=${bridgeToken}`);
  let sentAt = 0;
  let firstSay = 0;
  const finished = new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('no done within 90 s')), 90_000);
    ws.on('message', (data) => {
      const frame = JSON.parse(data.toString()) as ServerFrame;
      const t = sentAt ? Date.now() - sentAt : 0;
      out(`+${t}ms ${JSON.stringify(frame)}`);
      if (frame.type === 'ready' && !sentAt) {
        sentAt = Date.now();
        ws.send(JSON.stringify({ type: 'utterance', id: 'u1', text: utterance }));
      }
      if (frame.type === 'say' && !firstSay) firstSay = t;
      if (frame.type === 'done') {
        clearTimeout(timer);
        resolve();
      }
    });
    ws.on('error', reject);
  });
  ws.on('open', () => ws.send(JSON.stringify({ type: 'hello', client: 'smoke', version: '0' })));
  try {
    await finished;
    const trees = fake.phone.requests('GET', '/screen/tree').length;
    out(`first say: ${firstSay} ms; tree requests: ${trees}; phone actions: ${fake.phone.actions.map((a) => a.path).join(', ')}`);
  } finally {
    ws.close();
    await bridge.close();
    await fake.close();
  }
}

main().catch((err: unknown) => {
  out(`smoke failed: ${err instanceof Error ? err.message : String(err)}`);
  process.exit(1);
});
