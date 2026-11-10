/**
 * Silent replies: when an order is done and there is nothing worth saying (play music, next
 * song, open an app…), the model answers exactly `[ok]` so the phone does not talk over the music.
 */
const OK_MARKER = /\[\s*ok\s*\]/giu;
const ONLY_PUNCTUATION = /^[\s\p{P}]*$/u;

export interface MarkerScan {
  /** Sentence to speak with the marker removed, or null when nothing is left to say. */
  readonly speech: string | null;
  readonly hasMarker: boolean;
}

/** Removes the `[ok]` marker (any case, inner spaces, surrounding punctuation) from a sentence. */
export function scanOkMarker(sentence: string): MarkerScan {
  const stripped = sentence.replace(OK_MARKER, '');
  const hasMarker = stripped !== sentence;
  if (!hasMarker) return { speech: sentence, hasMarker };
  const rest = stripped.trim();
  return { speech: ONLY_PUNCTUATION.test(rest) ? null : rest, hasMarker };
}
