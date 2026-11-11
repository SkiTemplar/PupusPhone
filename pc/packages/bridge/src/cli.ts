#!/usr/bin/env node
/** pupusphone-bridge: voice WebSocket server. Configuration via environment variables (see pc/README.md). */
import { startBridge } from './bridge.ts';
import { loadBridgeConfig } from './config.ts';
import { log } from './log.ts';

async function main(): Promise<void> {
  let stop = (): void => undefined;
  const bridge = await startBridge(loadBridgeConfig(), { onIdle: () => stop() });
  stop = (): void => {
    log.info('shutting down');
    bridge.close().then(() => process.exit(0), () => process.exit(1));
  };
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
}

main().catch((err: unknown) => {
  log.error(err instanceof Error ? err.message : String(err));
  process.exit(1);
});
