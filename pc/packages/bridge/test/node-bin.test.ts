import { describe, expect, it } from 'vitest';
import { resolveNodeBin } from '../src/claude-args.ts';

describe('resolveNodeBin', () => {
  it('keeps a normal node executable path', () => {
    expect(resolveNodeBin('/usr/bin/node', {})).toBe('/usr/bin/node');
  });

  it('maps the Android linker used by termux-exec to the Termux node binary', () => {
    const env = { PREFIX: '/data/data/com.termux/files/usr' };
    expect(resolveNodeBin('/apex/com.android.runtime/bin/linker64', env)).toBe('/data/data/com.termux/files/usr/bin/node');
  });

  it('falls back to node on PATH when PREFIX is unknown', () => {
    expect(resolveNodeBin('/system/bin/linker64', {})).toBe('node');
  });

  it('honours an explicit PUPUS_NODE_BIN', () => {
    expect(resolveNodeBin('/system/bin/linker64', { PUPUS_NODE_BIN: '/opt/node' })).toBe('/opt/node');
  });
});
