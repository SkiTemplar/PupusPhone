import type { McpConfig } from './config.ts';
import type { ScreenTree } from './types.ts';

/** Error with a message written for the model: what failed and what to do next. */
export class PhoneError extends Error {
  readonly status: number | undefined;
  constructor(message: string, status?: number) {
    super(message);
    this.name = 'PhoneError';
    this.status = status;
  }
}

const UNREACHABLE =
  'Cannot reach the phone at {url}. Ask the user to enable the PupusPhone accessibility service ' +
  '(Settings > Accessibility > PupusPhone) and check that Tailscale is connected on both devices.';

function causeCode(err: unknown): string | undefined {
  if (!(err instanceof Error)) return undefined;
  const cause = (err as Error & { cause?: unknown }).cause;
  if (cause && typeof cause === 'object' && 'code' in cause) return String((cause as { code: unknown }).code);
  return undefined;
}

function networkError(err: unknown, url: string, timeoutMs: number): PhoneError {
  if (err instanceof Error && (err.name === 'TimeoutError' || err.name === 'AbortError')) {
    return new PhoneError(
      `The phone did not answer within ${timeoutMs} ms. It may be asleep or Tailscale may be down; ` +
        'ask the user to wake the phone and retry once.',
    );
  }
  const code = causeCode(err);
  if (code === 'ECONNREFUSED' || code === 'ECONNRESET' || code === 'EHOSTUNREACH' || code === 'ENOTFOUND' || code === 'ENETUNREACH') {
    return new PhoneError(UNREACHABLE.replace('{url}', url));
  }
  return new PhoneError(`Network error talking to the phone: ${err instanceof Error ? err.message : String(err)}`);
}

function httpError(status: number, error: string, path: string): PhoneError {
  if (status === 401) {
    return new PhoneError('The phone rejected the token (401). PUPUS_PHONE_TOKEN must match the token shown in the PupusPhone app settings.', 401);
  }
  if (status === 404 && error === 'node_not_found') {
    return new PhoneError('Node id not found: ids are only valid for the latest read_screen. Call read_screen and use a fresh id.', 404);
  }
  return new PhoneError(`Phone error ${status} on ${path}: ${error}`, status);
}

export type Query = Readonly<Record<string, string | number | boolean | undefined>>;

/** Thin typed client for the Device API (docs/protocol.md section 1). */
export class PhoneClient {
  private readonly config: McpConfig;

  constructor(config: McpConfig) {
    this.config = config;
  }

  async request<T>(method: 'GET' | 'POST', path: string, body?: unknown, query?: Query): Promise<T> {
    const url = new URL(path, this.config.phoneUrl);
    for (const [k, v] of Object.entries(query ?? {})) if (v !== undefined) url.searchParams.set(k, String(v));
    let res: Response;
    try {
      res = await fetch(url, {
        method,
        headers: { authorization: `Bearer ${this.config.token}`, 'content-type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(this.config.timeoutMs),
      });
    } catch (err) {
      throw networkError(err, this.config.phoneUrl, this.config.timeoutMs);
    }
    const text = await res.text();
    let json: unknown;
    try {
      json = text === '' ? {} : JSON.parse(text);
    } catch {
      throw new PhoneError(`Phone returned non-JSON response (${res.status}) on ${path}`, res.status);
    }
    if (!res.ok) {
      const error = typeof json === 'object' && json !== null && 'error' in json ? String((json as { error: unknown }).error) : text;
      throw httpError(res.status, error, path);
    }
    return json as T;
  }

  tree(visibleOnly = true): Promise<ScreenTree> {
    return this.request<ScreenTree>('GET', '/screen/tree', undefined, { visibleOnly });
  }

  post(path: string, body: unknown): Promise<{ ok: boolean }> {
    return this.request<{ ok: boolean }>('POST', path, body);
  }
}
