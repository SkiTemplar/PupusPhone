/** Minimal stderr logger: stdout is reserved for the MCP stdio transport. */
type Level = 'debug' | 'info' | 'warn' | 'error';

const ORDER: Readonly<Record<Level, number>> = { debug: 10, info: 20, warn: 30, error: 40 };

function threshold(): number {
  const raw = (process.env['PUPUS_LOG_LEVEL'] ?? 'info') as Level;
  return ORDER[raw] ?? ORDER.info;
}

function write(level: Level, message: string): void {
  if (ORDER[level] < threshold()) return;
  process.stderr.write(`${new Date().toISOString()} [pupusphone-mcp] ${level}: ${message}\n`);
}

export const log = {
  debug: (m: string) => write('debug', m),
  info: (m: string) => write('info', m),
  warn: (m: string) => write('warn', m),
  error: (m: string) => write('error', m),
};
