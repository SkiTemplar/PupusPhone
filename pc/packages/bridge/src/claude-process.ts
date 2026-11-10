import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import type { ClaudeLaunch } from './claude-args.ts';
import { log } from './log.ts';
import { LineSplitter, parseLine, type ClaudeEvent } from './stream-parser.ts';

export interface ClaudeProcessHandlers {
  onEvent(event: ClaudeEvent): void;
  onSpawn(): void;
  onExit(code: number | null, signal: NodeJS.Signals | null): void;
}

export interface ProcessOptions {
  readonly cwd: string;
  readonly env: Readonly<Record<string, string>>;
}

/** One running `claude -p --input-format stream-json` process with line-delimited JSON on stdin/stdout. */
export class ClaudeProcess {
  private readonly child: ChildProcessWithoutNullStreams;
  private exited = false;

  constructor(launch: ClaudeLaunch, options: ProcessOptions, handlers: ClaudeProcessHandlers) {
    this.child = spawn(launch.command, [...launch.args], {
      cwd: options.cwd,
      env: options.env,
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
    });
    const lines = new LineSplitter();
    this.child.stdout.setEncoding('utf8');
    this.child.stdout.on('data', (chunk: string) => {
      for (const line of lines.push(chunk)) this.dispatch(line, handlers);
    });
    this.child.stderr.setEncoding('utf8');
    this.child.stderr.on('data', (chunk: string) => log.debug(`claude stderr: ${chunk.trim()}`));
    this.child.stdin.on('error', (err) => log.warn(`claude stdin error: ${err.message}`));
    this.child.on('spawn', () => handlers.onSpawn());
    this.child.on('error', (err) => {
      log.error(`cannot start claude (${launch.command}): ${err.message}`);
      this.markExited(handlers, null, null);
    });
    this.child.on('exit', (code, signal) => this.markExited(handlers, code, signal));
  }

  get alive(): boolean {
    return !this.exited;
  }

  get pid(): number | undefined {
    return this.child.pid;
  }

  sendUser(text: string): void {
    this.write({ type: 'user', message: { role: 'user', content: [{ type: 'text', text }] }, parent_tool_use_id: null });
  }

  /** Asks Claude Code to stop the current turn; it answers with a control_response and an error result. */
  interrupt(): string {
    const requestId = `int-${randomUUID()}`;
    this.write({ type: 'control_request', request_id: requestId, request: { subtype: 'interrupt' } });
    return requestId;
  }

  kill(): void {
    if (this.exited) return;
    this.child.stdin.end();
    this.child.kill();
  }

  private write(message: unknown): void {
    if (this.exited || !this.child.stdin.writable) {
      log.warn('dropping message: claude process is not running');
      return;
    }
    this.child.stdin.write(`${JSON.stringify(message)}\n`);
  }

  private dispatch(line: string, handlers: ClaudeProcessHandlers): void {
    for (const event of parseLine(line)) {
      if (event.kind === 'invalid') log.warn(`unparseable claude line: ${event.line}`);
      else handlers.onEvent(event);
    }
  }

  private markExited(handlers: ClaudeProcessHandlers, code: number | null, signal: NodeJS.Signals | null): void {
    if (this.exited) return;
    this.exited = true;
    handlers.onExit(code, signal);
  }
}
