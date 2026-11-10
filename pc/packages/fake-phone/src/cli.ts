#!/usr/bin/env node
/**
 * Runs the fake phone on PUPUS_FAKE_PHONE_PORT (default 8765).
 * Token: PUPUS_PHONE_TOKEN; if missing, a random one is generated and printed to stderr.
 */
import { randomBytes } from 'node:crypto';
import { startFakePhone } from './index.ts';

function log(message: string): void {
  process.stderr.write(`[fake-phone] ${message}\n`);
}

async function main(): Promise<void> {
  const token = process.env['PUPUS_PHONE_TOKEN'] || randomBytes(32).toString('hex');
  const port = Number(process.env['PUPUS_FAKE_PHONE_PORT'] ?? 8765);
  const host = process.env['PUPUS_FAKE_PHONE_HOST'] ?? '127.0.0.1';
  const latencyMs = Number(process.env['PUPUS_FAKE_PHONE_LATENCY_MS'] ?? 0);
  if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error(`invalid port ${port}`);
  const running = await startFakePhone({ token, port, host, latencyMs });
  log(`listening on ${running.url}`);
  if (!process.env['PUPUS_PHONE_TOKEN']) log(`generated token: ${token}`);
  let seen = 0;
  const timer = setInterval(() => {
    const actions = running.phone.actions;
    for (const a of actions.slice(seen)) log(`${a.method} ${a.path} ${a.body === undefined ? '' : JSON.stringify(a.body)}`);
    seen = actions.length;
  }, 200);
  const stop = (): void => {
    clearInterval(timer);
    running.close().then(() => process.exit(0), () => process.exit(1));
  };
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
}

main().catch((err: unknown) => {
  log(`fatal: ${err instanceof Error ? err.message : String(err)}`);
  process.exit(1);
});
