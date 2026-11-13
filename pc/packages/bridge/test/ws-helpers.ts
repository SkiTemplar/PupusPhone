import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocket } from 'ws';
import { startBridge, type RunningBridge } from '../src/bridge.ts';
import { loadBridgeConfig } from '../src/config.ts';
import type { ServerFrame } from '../src/protocol.ts';

export const BRIDGE_TOKEN = 'bridge-test-token-0123456789';
const FAKE_CLAUDE = fileURLToPath(new URL('./fixtures/fake-claude.mjs', import.meta.url));

export interface TestBridge {
  readonly bridge: RunningBridge;
  readonly workdir: string;
  readFile(name: string): string;
}

export async function startTestBridge(env: Record<string, string> = {}): Promise<TestBridge> {
  const workdir = mkdtempSync(join(tmpdir(), 'pupus-bridge-test-'));
  const config = loadBridgeConfig({
    PUPUS_BRIDGE_TOKEN: BRIDGE_TOKEN,
    PUPUS_BRIDGE_HOST: '127.0.0.1',
    PUPUS_BRIDGE_PORT: '0',
    PUPUS_PHONE_URL: 'http://127.0.0.1:9',
    PUPUS_PHONE_TOKEN: 'phone-token',
    PUPUS_CLAUDE_BIN: FAKE_CLAUDE,
    PUPUS_BRIDGE_WORKDIR: workdir,
    PUPUS_INTERRUPT_TIMEOUT_MS: '300',
    // Never read the real ~/.pupusphone from tests.
    PUPUS_MEMORY_FILE: join(workdir, 'memory.json'),
    PUPUS_PROFILE_FILE: join(workdir, 'profile.md'),
    ...env,
  });
  const bridge = await startBridge(config, {
    env: { ...process.env, PUPUS_CANARY_SECRET: 'must-not-leak', CLAUDE_PLUGIN_ROOT: 'x' },
    backoffMs: [50],
  });
  return { bridge, workdir, readFile: (name) => readFileSync(join(workdir, name), 'utf8') };
}

/** Polls until `read` returns without throwing and satisfies `ok` (files written by the fake claude). */
export async function eventually<T>(read: () => T, ok: (value: T) => boolean = () => true, timeoutMs = 3000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    try {
      const value = read();
      if (ok(value)) return value;
    } catch (err) {
      if (Date.now() > deadline) throw err;
    }
    if (Date.now() > deadline) throw new Error('condition not met in time');
    await new Promise((r) => setTimeout(r, 20));
  }
}

/** WebSocket client that buffers frames and lets tests await specific ones. */
export class VoiceClient {
  readonly frames: ServerFrame[] = [];
  private readonly ws: WebSocket;
  private waiters: Array<() => void> = [];

  private constructor(ws: WebSocket) {
    this.ws = ws;
    ws.on('message', (data) => {
      this.frames.push(JSON.parse(data.toString()) as ServerFrame);
      for (const wake of this.waiters) wake();
    });
  }

  static async connect(port: number, token = BRIDGE_TOKEN, path = '/voice'): Promise<VoiceClient> {
    const ws = new WebSocket(`ws://127.0.0.1:${port}${path}?token=${encodeURIComponent(token)}`);
    await new Promise<void>((resolve, reject) => {
      ws.once('open', () => resolve());
      ws.once('unexpected-response', (_req, res) => reject(new Error(`HTTP ${res.statusCode}`)));
      ws.once('error', reject);
    });
    return new VoiceClient(ws);
  }

  send(frame: unknown): void {
    this.ws.send(typeof frame === 'string' ? frame : JSON.stringify(frame));
  }

  /** Resolves with the first frame (from `from` on) matching the predicate. */
  waitFor(predicate: (f: ServerFrame) => boolean, timeoutMs = 5000, from = 0): Promise<ServerFrame> {
    return new Promise((resolve, reject) => {
      const check = (): boolean => {
        const found = this.frames.slice(from).find(predicate);
        if (!found) return false;
        clearTimeout(timer);
        this.waiters = this.waiters.filter((w) => w !== wake);
        resolve(found);
        return true;
      };
      const wake = (): void => {
        check();
      };
      const timer = setTimeout(() => {
        this.waiters = this.waiters.filter((w) => w !== wake);
        reject(new Error(`timeout; frames so far: ${JSON.stringify(this.frames)}`));
      }, timeoutMs);
      if (!check()) this.waiters.push(wake);
    });
  }

  waitDone(id: string, from = 0): Promise<ServerFrame> {
    return this.waitFor((f) => f.type === 'done' && f.id === id, 5000, from);
  }

  /** Index just after the done frame of `id` (to await frames that follow it). */
  after(id: string): number {
    return this.frames.findIndex((f) => f.type === 'done' && f.id === id) + 1;
  }

  framesFor(id: string): ServerFrame[] {
    return this.frames.filter((f) => 'id' in f && f.id === id);
  }

  close(): void {
    this.ws.close();
  }
}
