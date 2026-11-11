import { createServer, type IncomingMessage, type Server } from 'node:http';
import { timingSafeEqual, createHash } from 'node:crypto';
import type { AddressInfo } from 'node:net';
import type { Duplex } from 'node:stream';
import { WebSocketServer, WebSocket, type RawData } from 'ws';
import { log } from './log.ts';
import { parseClientFrame, type ServerFrame } from './protocol.ts';
import type { VoiceSession } from './session.ts';

const MAX_FRAME_BYTES = 16 * 1024;
const VOICE_PATH = '/voice';

/** Constant-time comparison; hashing first makes the comparison length-independent. */
export function tokenEquals(given: string, expected: string): boolean {
  const a = createHash('sha256').update(given).digest();
  const b = createHash('sha256').update(expected).digest();
  return timingSafeEqual(a, b) && given.length === expected.length;
}

export interface VoiceServerOptions {
  readonly host: string;
  readonly port: number;
  readonly token: string;
  readonly model: string;
}

export interface RunningVoiceServer {
  readonly port: number;
  /** Sends a frame to the connected phone (the latest connection wins). */
  broadcast(frame: ServerFrame): void;
  /** True while a phone is connected. */
  hasClient(): boolean;
  attach(session: VoiceSession): void;
  close(): Promise<void>;
}

function reject(socket: Duplex, status: number, reason: string): void {
  socket.write(`HTTP/1.1 ${status} ${reason}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
  socket.destroy();
}

function authorize(req: IncomingMessage, token: string): number {
  const url = new URL(req.url ?? '/', 'http://bridge');
  if (url.pathname !== VOICE_PATH) return 404;
  const given = url.searchParams.get('token') ?? '';
  return tokenEquals(given, token) ? 200 : 401;
}

/** WebSocket voice endpoint (`ws://host:port/voice?token=…`) wired to a VoiceSession. */
export async function startVoiceServer(opts: VoiceServerOptions): Promise<RunningVoiceServer> {
  const http: Server = createServer((_req, res) => {
    res.writeHead(426, { 'content-type': 'text/plain' });
    res.end('WebSocket endpoint: /voice?token=...');
  });
  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_FRAME_BYTES });
  let active: WebSocket | null = null;
  let session: VoiceSession | null = null;

  const send = (frame: ServerFrame): void => {
    if (active?.readyState === WebSocket.OPEN) active.send(JSON.stringify(frame));
  };

  http.on('upgrade', (req, socket, head) => {
    const status = authorize(req, opts.token);
    if (status !== 200) {
      log.warn(`rejected voice connection from ${req.socket.remoteAddress ?? '?'} (${status})`);
      return reject(socket, status, status === 401 ? 'Unauthorized' : 'Not Found');
    }
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
  });

  wss.on('connection', (ws: WebSocket, req: IncomingMessage) => {
    if (active && active !== ws) active.close(4000, 'replaced by a newer connection');
    active = ws;
    const connectedAt = Date.now();
    log.info(`phone connected from ${req.socket.remoteAddress ?? '?'}`);
    ws.on('message', (data: RawData, isBinary: boolean) => onMessage(ws, data, isBinary));
    ws.on('close', (code: number, reason: Buffer) => {
      const seconds = Math.round((Date.now() - connectedAt) / 1000);
      const detail = reason.length > 0 ? ` ${reason.toString()}` : '';
      log.info(`phone disconnected (code ${code}${detail}) after ${seconds} s`);
      if (active === ws) active = null;
    });
    ws.on('error', (err) => log.warn(`websocket error: ${err.message}`));
  });

  function onMessage(ws: WebSocket, data: RawData, isBinary: boolean): void {
    if (ws !== active) return;
    if (isBinary) return send({ type: 'error', message: 'binary frames are not supported' });
    const frame = parseClientFrame(data.toString());
    if ('invalid' in frame) return send({ type: 'error', message: frame.invalid });
    if (!session) return send({ type: 'error', message: 'bridge is starting' });
    if (frame.type === 'hello') {
      log.info(`hello from ${frame.client} ${frame.version}`);
      if (session.isReady) send({ type: 'ready', model: opts.model });
    } else if (frame.type === 'utterance') session.utterance(frame.id, frame.text);
    else if (frame.type === 'cancel') session.cancel(frame.id);
    else session.reset();
  }

  await new Promise<void>((resolve, rejectListen) => {
    http.once('error', rejectListen);
    http.listen(opts.port, opts.host, () => resolve());
  });
  const { port } = http.address() as AddressInfo;
  return {
    port,
    broadcast: send,
    hasClient: () => active?.readyState === WebSocket.OPEN,
    attach: (s) => {
      session = s;
    },
    close: () =>
      new Promise((resolve) => {
        for (const client of wss.clients) client.terminate();
        wss.close();
        http.closeAllConnections();
        http.close(() => resolve());
      }),
  };
}
