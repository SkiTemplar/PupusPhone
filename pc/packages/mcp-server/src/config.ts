export interface McpConfig {
  /** Base URL of the Device API, e.g. http://100.x.y.z:8765 (Tailscale) or http://127.0.0.1:8765 (Termux). */
  readonly phoneUrl: string;
  readonly token: string;
  /** Per-request timeout. */
  readonly timeoutMs: number;
  /** Delay before re-reading the screen after an action when `observe` is true. */
  readonly settleMs: number;
  /** JSON file for the remember/forget tools (PUPUS_MEMORY_FILE); unset = memory tools disabled. */
  readonly memoryFile?: string | undefined;
  /** Country calling code added to numbers without one (whatsapp_send), e.g. "34". */
  readonly defaultCountryCode: string;
}

const COUNTRY_CODE_RE = /^[0-9]{1,4}$/;

function countryCode(raw: string | undefined): string {
  if (raw === undefined || raw === '') return '34';
  if (!COUNTRY_CODE_RE.test(raw)) throw new ConfigError(`PUPUS_DEFAULT_COUNTRY_CODE must be 1-4 digits without "+", got "${raw}"`);
  return raw;
}

export class ConfigError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ConfigError';
  }
}

function positiveInt(raw: string | undefined, fallback: number, name: string): number {
  if (raw === undefined || raw === '') return fallback;
  const n = Number(raw);
  if (!Number.isInteger(n) || n <= 0) throw new ConfigError(`${name} must be a positive integer, got "${raw}"`);
  return n;
}

/** Reads and validates the MCP server configuration from environment variables. */
export function loadConfig(env: Readonly<Record<string, string | undefined>> = process.env): McpConfig {
  const phoneUrl = env['PUPUS_PHONE_URL'];
  const token = env['PUPUS_PHONE_TOKEN'];
  if (!phoneUrl) throw new ConfigError('PUPUS_PHONE_URL is not set (e.g. http://<phone-tailscale-ip>:8765)');
  if (!token) throw new ConfigError('PUPUS_PHONE_TOKEN is not set (copy it from the PupusPhone app settings)');
  let parsed: URL;
  try {
    parsed = new URL(phoneUrl);
  } catch {
    throw new ConfigError(`PUPUS_PHONE_URL is not a valid URL: "${phoneUrl}"`);
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new ConfigError('PUPUS_PHONE_URL must use http or https');
  }
  return {
    phoneUrl: parsed.origin,
    token,
    timeoutMs: positiveInt(env['PUPUS_PHONE_TIMEOUT_MS'], 8000, 'PUPUS_PHONE_TIMEOUT_MS'),
    settleMs: positiveInt(env['PUPUS_SETTLE_MS'], 700, 'PUPUS_SETTLE_MS'),
    memoryFile: env['PUPUS_MEMORY_FILE'] || undefined,
    defaultCountryCode: countryCode(env['PUPUS_DEFAULT_COUNTRY_CODE']),
  };
}
