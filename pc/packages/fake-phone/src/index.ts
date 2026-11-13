import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import type { AddressInfo } from 'node:net';
import { FakePhone } from './phone.ts';
import { defaultScenario } from './scenario.ts';
import type { ApiResult, Scenario } from './types.ts';

export { FakePhone } from './phone.ts';
export { DeviceServices } from './services.ts';
export { defaultScenario } from './scenario.ts';
export { flattenScreen } from './tree.ts';
export type * from './types.ts';

const MAX_BODY_BYTES = 64 * 1024;

export interface FakePhoneOptions {
  readonly token: string;
  /** 0 picks a free port (tests). */
  readonly port?: number;
  readonly host?: string;
  readonly scenario?: Scenario;
  /** Artificial latency per request, to emulate Tailscale round trips. */
  readonly latencyMs?: number;
}

export interface RunningFakePhone {
  readonly phone: FakePhone;
  readonly url: string;
  readonly port: number;
  close(): Promise<void>;
}

function tokenMatches(header: string | undefined, token: string): boolean {
  if (!header?.startsWith('Bearer ')) return false;
  const given = Buffer.from(header.slice('Bearer '.length));
  const expected = Buffer.from(token);
  return given.length === expected.length && timingSafeEqual(given, expected);
}

function send(res: ServerResponse, result: ApiResult): void {
  const payload = JSON.stringify(result.body);
  res.writeHead(result.status, { 'content-type': 'application/json; charset=utf-8' });
  res.end(payload);
}

function readBody(req: IncomingMessage): Promise<unknown> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    req.on('data', (chunk: Buffer) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(new Error('body_too_large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8');
      if (raw.trim() === '') return resolve(undefined);
      try {
        resolve(JSON.parse(raw));
      } catch {
        reject(new Error('invalid_json'));
      }
    });
    req.on('error', reject);
  });
}

async function route(phone: FakePhone, opts: FakePhoneOptions, req: IncomingMessage, res: ServerResponse): Promise<void> {
  if (!tokenMatches(req.headers.authorization, opts.token)) return send(res, { status: 401, body: { error: 'unauthorized' } });
  const url = new URL(req.url ?? '/', 'http://fake-phone');
  let body: unknown;
  try {
    body = await readBody(req);
  } catch (err) {
    return send(res, { status: 400, body: { error: err instanceof Error ? err.message : 'bad_body' } });
  }
  if (opts.latencyMs) await new Promise((r) => setTimeout(r, opts.latencyMs));
  const query = Object.fromEntries(url.searchParams.entries());
  send(res, phone.handle({ method: req.method ?? 'GET', path: url.pathname, query, body }));
}

/** Starts the fake Device API over HTTP. */
export async function startFakePhone(opts: FakePhoneOptions): Promise<RunningFakePhone> {
  if (!opts.token) throw new Error('fake-phone requires a token');
  const phone = new FakePhone(opts.scenario ?? defaultScenario());
  const server: Server = createServer((req, res) => {
    route(phone, opts, req, res).catch((err: unknown) => {
      send(res, { status: 500, body: { error: err instanceof Error ? err.message : 'internal_error' } });
    });
  });
  const host = opts.host ?? '127.0.0.1';
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(opts.port ?? 0, host, () => resolve());
  });
  const { port } = server.address() as AddressInfo;
  return {
    phone,
    port,
    url: `http://${host}:${port}`,
    close: () => new Promise((resolve, reject) => {
      server.closeAllConnections();
      server.close((err) => (err ? reject(err) : resolve()));
    }),
  };
}
