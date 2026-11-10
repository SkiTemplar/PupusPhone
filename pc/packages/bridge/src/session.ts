import { SentenceChunker } from './chunker.ts';
import type { ClaudeProcessHandlers } from './claude-process.ts';
import { log } from './log.ts';
import type { ServerFrame } from './protocol.ts';
import { toolStatus } from './status.ts';
import type { ClaudeEvent } from './stream-parser.ts';

export interface ProcessLike {
  readonly alive: boolean;
  sendUser(text: string): void;
  interrupt(): string;
  kill(): void;
}

export type SpawnFn = (resumeSessionId: string | undefined, handlers: ClaudeProcessHandlers) => ProcessLike;

export interface SessionOptions {
  readonly model: string;
  readonly spawn: SpawnFn;
  readonly send: (frame: ServerFrame) => void;
  readonly turnTimeoutMs: number;
  readonly interruptTimeoutMs: number;
  /** Respawn delays after consecutive crashes. */
  readonly backoffMs?: readonly number[];
}

interface Turn {
  readonly id: string;
  readonly startedAt: number;
  readonly chunker: SentenceChunker;
  cancelled: boolean;
  timers: ReturnType<typeof setTimeout>[];
}

interface Pending {
  readonly id: string;
  readonly text: string;
  readonly receivedAt: number;
}

const DEFAULT_BACKOFF = [500, 1000, 2000, 5000, 10000, 30000];
const MSG_TURN_FAILED = 'No he podido terminar la orden.';
const MSG_TIMEOUT = 'He tardado demasiado y lo he dejado.';
const MSG_RESTARTED = 'El asistente se ha reiniciado, repíteme la orden.';

/**
 * Owns the persistent Claude process and maps one utterance to one turn.
 * Invariant: every utterance receives exactly one `done` (possibly preceded by `error`).
 * A new utterance or `cancel` interrupts the running turn via a stream-json control request;
 * if the interrupt does not land in time, the process is killed and respawned with `--resume`.
 */
export class VoiceSession {
  private readonly opts: SessionOptions;
  private proc: ProcessLike | null = null;
  private generation = 0;
  private ready = false;
  private sessionId: string | undefined;
  private turn: Turn | null = null;
  private pending: Pending | null = null;
  private crashes = 0;
  private respawnTimer: ReturnType<typeof setTimeout> | undefined;
  private stopped = false;

  constructor(opts: SessionOptions) {
    this.opts = opts;
  }

  get isReady(): boolean {
    return this.ready && this.proc?.alive === true;
  }

  get busy(): boolean {
    return this.turn !== null;
  }

  start(): void {
    this.spawnProcess(undefined);
  }

  stop(): void {
    this.stopped = true;
    clearTimeout(this.respawnTimer);
    if (this.turn) this.endTurn();
    this.proc?.kill();
    this.proc = null;
  }

  utterance(id: string, text: string): void {
    if (text.trim() === '') return this.opts.send({ type: 'done', id, durationMs: 0 });
    if (this.pending) this.dropPending();
    this.pending = { id, text, receivedAt: Date.now() };
    if (this.turn) return this.cancelTurn();
    this.startPending();
  }

  cancel(id: string): void {
    if (this.pending?.id === id) return this.dropPending();
    if (this.turn?.id === id) this.cancelTurn();
  }

  /** New conversation: drops queued and running work and starts a fresh process (no resume). */
  reset(): void {
    if (this.pending) this.dropPending();
    if (this.turn) this.endTurn();
    this.sessionId = undefined;
    this.crashes = 0;
    this.replaceProcess(undefined);
  }

  private spawnProcess(resume: string | undefined): void {
    const gen = ++this.generation;
    this.ready = false;
    log.info(resume ? `starting claude (resume ${resume})` : 'starting claude (new conversation)');
    this.proc = this.opts.spawn(resume, {
      onSpawn: () => gen === this.generation && this.onSpawn(),
      onEvent: (event) => gen === this.generation && this.onEvent(event),
      onExit: (code, signal) => gen === this.generation && this.onExit(code, signal),
    });
  }

  private replaceProcess(resume: string | undefined): void {
    clearTimeout(this.respawnTimer);
    const old = this.proc;
    this.spawnProcess(resume);
    old?.kill();
  }

  private onSpawn(): void {
    this.ready = true;
    this.opts.send({ type: 'ready', model: this.opts.model });
    this.startPending();
  }

  private startPending(): void {
    if (!this.pending || this.turn || !this.isReady || !this.proc) return;
    const { id, text, receivedAt } = this.pending;
    this.pending = null;
    const turn: Turn = { id, startedAt: receivedAt, chunker: new SentenceChunker(), cancelled: false, timers: [] };
    turn.timers.push(setTimeout(() => this.onTurnTimeout(id), this.opts.turnTimeoutMs));
    this.turn = turn;
    this.proc.sendUser(text);
  }

  private dropPending(): void {
    if (!this.pending) return;
    this.opts.send({ type: 'done', id: this.pending.id, durationMs: Date.now() - this.pending.receivedAt });
    this.pending = null;
  }

  private cancelTurn(): void {
    const turn = this.turn;
    if (!turn || turn.cancelled) return;
    turn.cancelled = true;
    if (!this.proc?.alive) return this.endTurn();
    this.proc.interrupt();
    turn.timers.push(setTimeout(() => this.forceRestart(turn), this.opts.interruptTimeoutMs));
  }

  /** The interrupt did not produce a result in time: kill and resume the same conversation. */
  private forceRestart(turn: Turn): void {
    if (this.turn !== turn) return;
    log.warn(`interrupt of turn ${turn.id} timed out; restarting claude with --resume`);
    this.endTurn();
    this.replaceProcess(this.sessionId);
  }

  private onTurnTimeout(id: string): void {
    const turn = this.turn;
    if (!turn || turn.id !== id || turn.cancelled) return;
    this.opts.send({ type: 'error', id, message: MSG_TIMEOUT });
    this.cancelTurn();
  }

  private endTurn(): void {
    const turn = this.turn;
    if (!turn) return;
    for (const timer of turn.timers) clearTimeout(timer);
    this.turn = null;
    this.opts.send({ type: 'done', id: turn.id, durationMs: Date.now() - turn.startedAt });
  }

  private activeTurn(): Turn | null {
    return this.turn && !this.turn.cancelled ? this.turn : null;
  }

  private speak(turn: Turn, sentences: readonly string[]): void {
    for (const text of sentences) this.opts.send({ type: 'say', id: turn.id, text });
  }

  private onEvent(event: ClaudeEvent): void {
    const turn = this.activeTurn();
    switch (event.kind) {
      case 'init':
        this.sessionId = event.sessionId || this.sessionId;
        log.debug(`claude session ${event.sessionId} model ${event.model} tools [${event.tools.join(', ')}]`);
        return;
      case 'text':
        if (turn) this.speak(turn, turn.chunker.push(event.text));
        return;
      case 'text_end':
        if (turn) this.speak(turn, turn.chunker.flush());
        return;
      case 'tool':
        if (!turn) return;
        this.speak(turn, turn.chunker.flush());
        this.opts.send({ type: 'status', id: turn.id, text: toolStatus(event.name, event.input) });
        return;
      case 'result':
        return this.onResult(event);
      case 'control_response':
        if (!event.success) log.warn(`control request ${event.requestId} failed: ${event.error ?? 'unknown'}`);
        return;
      default:
        return;
    }
  }

  private onResult(event: Extract<ClaudeEvent, { kind: 'result' }>): void {
    if (event.sessionId) this.sessionId = event.sessionId;
    this.crashes = 0;
    const turn = this.turn;
    if (!turn) return;
    if (!turn.cancelled) {
      this.speak(turn, turn.chunker.flush());
      if (event.isError) {
        log.warn(`turn ${turn.id} ended with ${event.subtype}: ${event.text ?? ''}`);
        this.opts.send({ type: 'error', id: turn.id, message: MSG_TURN_FAILED });
      }
    }
    this.endTurn();
    this.startPending();
  }

  private onExit(code: number | null, signal: NodeJS.Signals | null): void {
    this.ready = false;
    this.proc = null;
    if (this.stopped) return;
    const turn = this.turn;
    if (turn && !turn.cancelled) this.opts.send({ type: 'error', id: turn.id, message: MSG_RESTARTED });
    if (turn) this.endTurn();
    const backoff = this.opts.backoffMs ?? DEFAULT_BACKOFF;
    const delay = backoff[Math.min(this.crashes, backoff.length - 1)] ?? 1000;
    this.crashes++;
    // A resume that keeps failing (corrupt or unknown session) falls back to a fresh conversation.
    if (this.crashes > 2) this.sessionId = undefined;
    log.warn(`claude exited (code ${code ?? 'null'}, signal ${signal ?? 'none'}); respawning in ${delay} ms`);
    this.respawnTimer = setTimeout(() => this.spawnProcess(this.sessionId), delay);
  }
}
