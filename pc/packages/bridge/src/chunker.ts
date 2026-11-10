/**
 * Splits streamed text into complete sentences for TTS. A sentence ends at . ! ? … or a newline
 * followed by whitespace; Spanish abbreviations, initials and decimals (3.5) do not end a sentence.
 * Very long runs without a full stop are split at a comma/semicolon/colon to keep latency low.
 */

const ABBREVIATIONS = new Set([
  'sr', 'sra', 'srta', 'sres', 'dr', 'dra', 'd', 'dña', 'ud', 'uds', 'vd', 'vds', 'etc', 'p', 'ej', 'pág', 'págs',
  'núm', 'nº', 'n', 'tel', 'tlf', 'av', 'avda', 'c', 'pza', 'aprox', 'min', 'máx', 'mín', 'km', 'kg', 'h',
  'ee', 'uu', 'vs', 'cía', 'dto', 'admón', 'art', 'cap', 'fig', 'prof', 'lic', 'ing', 'st',
]);

const TERMINATORS = new Set(['.', '!', '?', '…']);
const CLOSERS = new Set(['"', "'", ')', ']', '»', '”', '’']);
const SOFT_BREAKS = new Set([',', ';', ':']);

export interface ChunkerOptions {
  /** Split at a soft break once the pending sentence exceeds this length. */
  readonly softLimit?: number;
}

function wordBefore(text: string, index: number): string {
  let start = index;
  while (start > 0 && /[\p{L}\p{N}º]/u.test(text[start - 1] ?? '')) start--;
  return text.slice(start, index);
}

function isAbbreviation(text: string, dot: number): boolean {
  const word = wordBefore(text, dot);
  if (word === '') return false;
  if (ABBREVIATIONS.has(word.toLocaleLowerCase('es'))) return true;
  return word.length === 1 && /\p{Lu}/u.test(word);
}

/** Index just after the end of the first complete sentence, or -1. */
function findBoundary(text: string): number {
  for (let i = 0; i < text.length; i++) {
    const ch = text[i] ?? '';
    if (ch === '\n') return i + 1;
    if (!TERMINATORS.has(ch)) continue;
    let end = i + 1;
    while (end < text.length && (TERMINATORS.has(text[end] ?? '') || CLOSERS.has(text[end] ?? ''))) end++;
    if (end >= text.length) return -1;
    if (!/\s/.test(text[end] ?? '')) continue;
    if (ch === '.' && end === i + 1 && isAbbreviation(text, i)) continue;
    return end;
  }
  return -1;
}

function findSoftBoundary(text: string, limit: number): number {
  if (text.length < limit) return -1;
  for (let i = text.length - 2; i >= Math.floor(limit / 2); i--) {
    if (SOFT_BREAKS.has(text[i] ?? '') && /\s/.test(text[i + 1] ?? '')) return i + 1;
  }
  return -1;
}

/** Removes markdown and emoji that a TTS engine would read aloud literally. */
export function cleanForSpeech(text: string): string {
  return text
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`([^`]*)`/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/^\s{0,3}(#{1,6}|[-*+]|\d+[.)])\s+/gm, '')
    .replace(/(\*\*|__|\*|_|~~)(?=\S)([^*_~]*?\S)\1/g, '$2')
    .replace(/[\p{Extended_Pictographic}\u{FE0F}\u{200D}]/gu, '')
    .replace(/\s+/g, ' ')
    .replace(/\s+([.,;:!?…])/g, '$1')
    .trim();
}

export class SentenceChunker {
  private buffer = '';
  private readonly softLimit: number;

  constructor(options: ChunkerOptions = {}) {
    this.softLimit = options.softLimit ?? 220;
  }

  /** Adds a text delta and returns the sentences completed by it (already cleaned for speech). */
  push(delta: string): string[] {
    this.buffer += delta;
    const sentences: string[] = [];
    for (;;) {
      let cut = findBoundary(this.buffer);
      if (cut < 0) cut = findSoftBoundary(this.buffer, this.softLimit);
      if (cut < 0) break;
      const sentence = cleanForSpeech(this.buffer.slice(0, cut));
      this.buffer = this.buffer.slice(cut);
      if (sentence !== '') sentences.push(sentence);
    }
    return sentences;
  }

  /** Returns whatever is pending as a final sentence (end of an assistant message). */
  flush(): string[] {
    const rest = cleanForSpeech(this.buffer);
    this.buffer = '';
    return rest === '' ? [] : [rest];
  }

  reset(): void {
    this.buffer = '';
  }
}
