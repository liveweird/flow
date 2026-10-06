import { DAY_MS, isoDateToEpochMillis } from "./isoDate";

/**
 * The URL/selection parsing primitives shared by `reportFilter.ts` and `deepDiveFilter.ts`: both
 * mirror the same server limits (the `from`/`to` span cap, the free-text length cap, the integer
 * digit bound), so the rules live here once.
 */

/** The widest `from`/`to` span (days, inclusive) the server accepts. */
export const MAX_SPAN_DAYS = 1100;
/** The longest free-text value the server accepts for a filter param. */
export const MAX_TEXT = 200;

const INTEGER_TEXT = /^\d{1,15}$/;

/** True for a plain non-negative integer of at most 15 digits (inside `Number.MAX_SAFE_INTEGER`). */
export function isIntegerText(value: string): boolean {
  return INTEGER_TEXT.test(value);
}

/** A bounded non-negative integer from text (or a number's text); `undefined` unless it is digits only and within `[min, max]`. */
export function parseBoundedInteger(
  value: number | string | null | undefined,
  min: number,
  max = Number.MAX_SAFE_INTEGER,
): number | undefined {
  const text = typeof value === "number" ? String(value) : value;
  if (text == null || !isIntegerText(text)) return undefined;
  const n = Number(text);
  return n >= min && n <= max ? n : undefined;
}

/** A `from`/`to` pair the server would accept: ordered, and no wider than its span cap (an open end always passes). */
export function validRange(from: string | undefined, to: string | undefined): boolean {
  if (from === undefined || to === undefined) return true;
  const span = (isoDateToEpochMillis(to) - isoDateToEpochMillis(from)) / DAY_MS;
  return span >= 0 && span + 1 <= MAX_SPAN_DAYS;
}
