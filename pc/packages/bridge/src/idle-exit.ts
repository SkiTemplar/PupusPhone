/**
 * Shuts the bridge down when no phone has been connected for a while. On the phone (Termux) the
 * app may be killed without notice (swiped away): nothing would ever stop the bridge, Claude and
 * the wake lock. Idle time is counted in ticks, not wall-clock time, so a long freeze of the
 * process (one late tick) never counts as idle minutes.
 */
export const DEFAULT_IDLE_TICK_MS = 10_000;

export class IdleCounter {
  private idleTicks = 0;
  private readonly ticksNeeded: number;

  constructor(idleMs: number, tickMs: number) {
    this.ticksNeeded = Math.max(1, Math.ceil(idleMs / tickMs));
  }

  /** Records one tick; returns true once there was no client for `idleMs` worth of ticks. */
  tick(hasClient: boolean): boolean {
    this.idleTicks = hasClient ? 0 : this.idleTicks + 1;
    return this.idleTicks >= this.ticksNeeded;
  }
}

export interface IdleExitOptions {
  readonly idleMs: number;
  readonly tickMs?: number;
  readonly hasClient: () => boolean;
  readonly onIdle: () => void;
}

/** Starts the watcher; returns a function that stops it. Fires `onIdle` at most once. */
export function startIdleExit(opts: IdleExitOptions): () => void {
  const tickMs = opts.tickMs ?? DEFAULT_IDLE_TICK_MS;
  const counter = new IdleCounter(opts.idleMs, tickMs);
  const timer = setInterval(() => {
    if (!counter.tick(opts.hasClient())) return;
    clearInterval(timer);
    opts.onIdle();
  }, tickMs);
  timer.unref();
  return () => clearInterval(timer);
}
