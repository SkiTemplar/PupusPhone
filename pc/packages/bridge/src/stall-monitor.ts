/**
 * Detects that the whole process stopped running for a while: on Android the ROM may freeze
 * Termux or let the CPU sleep, and then the phone's WebSocket pings go unanswered. A timer that
 * should fire every `intervalMs` and fires much later is the evidence; the gap is logged so a
 * dropped connection can be matched with a frozen bridge.
 */
export const DEFAULT_STALL_INTERVAL_MS = 5_000;
export const DEFAULT_STALL_THRESHOLD_MS = 15_000;

/** Pure part: given the previous and current tick times, returns the stall length or null. */
export function measureStall(previousMs: number, nowMs: number, intervalMs: number, thresholdMs: number): number | null {
  const lateBy = nowMs - previousMs - intervalMs;
  return lateBy >= thresholdMs ? nowMs - previousMs : null;
}

export interface StallMonitorOptions {
  readonly intervalMs?: number;
  readonly thresholdMs?: number;
  readonly now?: () => number;
  readonly onStall: (stalledMs: number) => void;
}

/** Starts the monitor; returns a function that stops it. The timer does not keep Node alive. */
export function startStallMonitor(opts: StallMonitorOptions): () => void {
  const intervalMs = opts.intervalMs ?? DEFAULT_STALL_INTERVAL_MS;
  const thresholdMs = opts.thresholdMs ?? DEFAULT_STALL_THRESHOLD_MS;
  const now = opts.now ?? Date.now;
  let previous = now();
  const timer = setInterval(() => {
    const current = now();
    const stalled = measureStall(previous, current, intervalMs, thresholdMs);
    previous = current;
    if (stalled !== null) opts.onStall(stalled);
  }, intervalMs);
  timer.unref();
  return () => clearInterval(timer);
}
