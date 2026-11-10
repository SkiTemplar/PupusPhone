/** Minimal boundary validation for request bodies (kept dependency-free). */

export class BadRequest extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'BadRequest';
  }
}

export type Body = Readonly<Record<string, unknown>>;

export function asBody(value: unknown): Body {
  if (value === undefined || value === null) return {};
  if (typeof value !== 'object' || Array.isArray(value)) throw new BadRequest('body must be a JSON object');
  return value as Body;
}

export function int(body: Body, key: string): number {
  const v = body[key];
  if (typeof v !== 'number' || !Number.isInteger(v)) throw new BadRequest(`${key} must be an integer`);
  return v;
}

export function optInt(body: Body, key: string): number | undefined {
  return body[key] === undefined ? undefined : int(body, key);
}

export function str(body: Body, key: string): string {
  const v = body[key];
  if (typeof v !== 'string') throw new BadRequest(`${key} must be a string`);
  return v;
}

export function optStr(body: Body, key: string): string | undefined {
  return body[key] === undefined ? undefined : str(body, key);
}

export function optBool(body: Body, key: string): boolean | undefined {
  const v = body[key];
  if (v === undefined) return undefined;
  if (typeof v !== 'boolean') throw new BadRequest(`${key} must be a boolean`);
  return v;
}

export function oneOf<T extends string>(body: Body, key: string, allowed: readonly T[]): T {
  const v = str(body, key);
  if (!(allowed as readonly string[]).includes(v)) throw new BadRequest(`${key} must be one of ${allowed.join('|')}`);
  return v as T;
}

export function optStringMap(body: Body, key: string): Readonly<Record<string, string>> | undefined {
  const v = body[key];
  if (v === undefined) return undefined;
  if (typeof v !== 'object' || v === null || Array.isArray(v)) throw new BadRequest(`${key} must be an object`);
  for (const value of Object.values(v)) {
    if (typeof value !== 'string') throw new BadRequest(`${key} values must be strings`);
  }
  return v as Record<string, string>;
}

export function rangeNumber(raw: string | undefined, fallback: number, min: number, max: number, key: string): number {
  if (raw === undefined) return fallback;
  const n = Number(raw);
  if (!Number.isFinite(n) || n < min || n > max) throw new BadRequest(`${key} must be between ${min} and ${max}`);
  return n;
}
