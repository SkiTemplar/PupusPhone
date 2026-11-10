import { describe, expect, it } from 'vitest';
import { SentenceChunker, cleanForSpeech } from '../src/chunker.ts';

function chunkAll(deltas: readonly string[]): string[] {
  const chunker = new SentenceChunker();
  return [...deltas.flatMap((d) => chunker.push(d)), ...chunker.flush()];
}

describe('SentenceChunker', () => {
  it('emits a sentence only once its terminator is followed by whitespace', () => {
    const chunker = new SentenceChunker();
    expect(chunker.push('Hola, ')).toEqual([]);
    expect(chunker.push('ya está.')).toEqual([]);
    expect(chunker.push(' Siguiente')).toEqual(['Hola, ya está.']);
    expect(chunker.flush()).toEqual(['Siguiente']);
  });

  it('handles Spanish question and exclamation marks, including inverted openers', () => {
    expect(chunkAll(['¿Pongo ', 'Queen? ¡Hecho! Ya suena.'])).toEqual(['¿Pongo Queen?', '¡Hecho!', 'Ya suena.']);
  });

  it('does not split decimals or times split across deltas', () => {
    expect(chunkAll(['La temperatura es de 3', '.5 grados. Son las 10', '.30 h aprox. y llueve.'])).toEqual([
      'La temperatura es de 3.5 grados.',
      'Son las 10.30 h aprox. y llueve.',
    ]);
  });

  it('keeps abbreviations and initials inside the sentence', () => {
    expect(chunkAll(['El Sr. García y la Dra. López llegan a las 5. Vale.'])).toEqual([
      'El Sr. García y la Dra. López llegan a las 5.',
      'Vale.',
    ]);
    expect(chunkAll(['Libro de J. K. Rowling, p. ej. el primero. Fin'])).toEqual([
      'Libro de J. K. Rowling, p. ej. el primero.',
      'Fin',
    ]);
    expect(chunkAll(['Vive en EE. UU. desde 2020. Bien.'])).toEqual(['Vive en EE. UU. desde 2020.', 'Bien.']);
  });

  it('treats ellipsis, closing quotes and newlines as boundaries', () => {
    expect(chunkAll(['Dice «llego tarde». Bueno… ', 'vale\nOtra línea'])).toEqual([
      'Dice «llego tarde».',
      'Bueno…',
      'vale',
      'Otra línea',
    ]);
  });

  it('splits very long runs at a comma to keep TTS latency low', () => {
    const chunker = new SentenceChunker({ softLimit: 40 });
    const out = chunker.push('Tienes mensajes de Ana, de Luis y de Marta, todos de esta mañana sin leer');
    expect(out).toEqual(['Tienes mensajes de Ana, de Luis y de Marta,']);
    expect(chunker.flush()).toEqual(['todos de esta mañana sin leer']);
  });

  it('cleans markdown and emoji before speaking', () => {
    expect(chunkAll(['**Listo** 🎵. ', '- Pon `Queen`\n'])).toEqual(['Listo.', 'Pon Queen']);
    expect(cleanForSpeech('## Título\n[enlace](https://x.y) _fin_')).toBe('Título enlace fin');
  });

  it('drops empty fragments and resets state', () => {
    const chunker = new SentenceChunker();
    expect(chunker.push('   \n\n')).toEqual([]);
    chunker.push('pendiente');
    chunker.reset();
    expect(chunker.flush()).toEqual([]);
  });
});
