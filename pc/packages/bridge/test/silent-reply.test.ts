import { describe, expect, it } from 'vitest';
import { scanOkMarker } from '../src/silent-reply.ts';

describe('scanOkMarker', () => {
  it.each(['[ok]', '[OK]', ' [ok] ', '[ok].', '¡[ok]!', '[ ok ]', '"[ok]"'])('treats %j as a silent reply', (s) => {
    expect(scanOkMarker(s)).toEqual({ speech: null, hasMarker: true });
  });

  it('keeps a normal sentence untouched', () => {
    expect(scanOkMarker('Listo, suena Queen.')).toEqual({ speech: 'Listo, suena Queen.', hasMarker: false });
  });

  it('does not take ok without brackets as the marker', () => {
    expect(scanOkMarker('Ok.')).toEqual({ speech: 'Ok.', hasMarker: false });
  });

  it('speaks what is left when the marker comes with real text', () => {
    expect(scanOkMarker('[ok] Ya está.')).toEqual({ speech: 'Ya está.', hasMarker: true });
  });
});
