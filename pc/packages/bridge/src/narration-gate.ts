/**
 * Holds spoken sentences until it is clear they are an answer for the user and not the model
 * narrating its own steps between tool calls ("Results are empty; wait and read.").
 * - Text followed by a tool call is dropped.
 * - Text is released when the turn ends, or early once it is long enough to be a real answer,
 *   so long replies (reading messages aloud) still start speaking before the message completes.
 */
export const DEFAULT_RELEASE_CHARS = 240;

export class NarrationGate {
  private held: string[] = [];
  private heldChars = 0;
  private released = false;
  private readonly releaseChars: number;

  constructor(releaseChars: number = DEFAULT_RELEASE_CHARS) {
    this.releaseChars = releaseChars;
  }

  /** Adds sentences; returns those that can be spoken now. */
  push(sentences: readonly string[]): string[] {
    if (sentences.length === 0) return [];
    if (this.released) return [...sentences];
    this.held = [...this.held, ...sentences];
    this.heldChars += sentences.reduce((sum, s) => sum + s.length, 0);
    if (this.heldChars < this.releaseChars) return [];
    this.released = true;
    return this.take();
  }

  /** A tool call started: anything still held was narration. Returns what was dropped. */
  dropForTool(): string[] {
    const dropped = this.take();
    this.released = false;
    return dropped;
  }

  /** The turn ended: everything held is the answer. */
  flush(): string[] {
    const out = this.take();
    this.released = false;
    return out;
  }

  private take(): string[] {
    const out = this.held;
    this.held = [];
    this.heldChars = 0;
    return out;
  }
}
