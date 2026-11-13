import { normalize } from './text-match.ts';
import type { Contact, ContactNumber } from './types.ts';

/** Same shape the Device API accepts on POST /call. */
export const PHONE_NUMBER_RE = /^\+?[0-9 ()-]{3,20}$/;

const MOBILE_LABEL_RE = /movil|mobile|cell/;
const MIN_INTERNATIONAL_DIGITS = 8;
const MAX_INTERNATIONAL_DIGITS = 15;

/** True when the user gave a phone number rather than a contact name. */
export function isPhoneNumber(value: string): boolean {
  const trimmed = value.trim();
  return PHONE_NUMBER_RE.test(trimmed) && (trimmed.match(/[0-9]/g)?.length ?? 0) >= 3;
}

/** A stored number reduced to what POST /call accepts ("+34 612.345.678" → "+34 612345678"), or null. */
export function toDialable(number: string): string | null {
  const cleaned = number.trim().replace(/[^0-9+ ()-]/g, '');
  return isPhoneNumber(cleaned) ? cleaned : null;
}

/** Mobile-looking number first (label contains móvil/mobile/cell), else the first one. */
export function pickNumber(contact: Contact): ContactNumber | undefined {
  return contact.numbers.find((n) => n.label !== null && MOBILE_LABEL_RE.test(normalize(n.label))) ?? contact.numbers[0];
}

/**
 * The single contact the query refers to, or the list of candidates when it is not unique.
 * A unique full-name match (ignoring case and accents) wins over partial matches.
 */
export function chooseContact(contacts: readonly Contact[], query: string): Contact | Contact[] {
  if (contacts.length === 1 && contacts[0]) return contacts[0];
  const needle = normalize(query);
  const exact = contacts.filter((c) => normalize(c.name) === needle);
  if (exact.length === 1 && exact[0]) return exact[0];
  return [...contacts];
}

export function describeNumber(n: ContactNumber): string {
  return n.label ? `${n.number} (${n.label})` : n.number;
}

/** "Mamá: 91 555 12 34 (Casa), 600 11 22 33 (Móvil)". */
export function describeContact(contact: Contact): string {
  return `${contact.name}: ${contact.numbers.map(describeNumber).join(', ') || '(no number)'}`;
}

/**
 * International digits for wa.me links: "+34 612 345 678" → "34612345678", "0044 20…" → "4420…",
 * "600 11 22 33" → "<countryCode>600112233". Returns null when the result is not a plausible E.164 number.
 */
export function toInternational(number: string, countryCode: string): string | null {
  const compact = number.trim().replace(/[\s().-]/g, '');
  if (!/^\+?[0-9]+$/.test(compact)) return null;
  let digits: string;
  if (compact.startsWith('+')) digits = compact.slice(1);
  else if (compact.startsWith('00')) digits = compact.slice(2);
  else digits = `${countryCode}${compact}`;
  return digits.length >= MIN_INTERNATIONAL_DIGITS && digits.length <= MAX_INTERNATIONAL_DIGITS ? digits : null;
}
