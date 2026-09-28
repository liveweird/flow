/**
 * `YYYY-MM-DD` ISO-date helpers shared by the metrics settings form (holidays) and D1's dated
 * Jira-user team membership (valid-from/valid-to). Every conversion is UTC — the same
 * deterministic-across-test/CI-locales convention `utils/dataSourceState.ts`'s `formatEpochMillis`
 * already uses (`toISOString()`, never `toLocaleString()`/the local time zone).
 */

const ISO_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/** A real calendar date, not just the `YYYY-MM-DD` shape (rejects e.g. `2024-02-30`). */
export function isValidIsoDate(value: string): boolean {
  if (!ISO_DATE_PATTERN.test(value)) return false;
  const [year, month, day] = value.split("-").map(Number);
  const parsed = new Date(Date.UTC(year, month - 1, day));
  return parsed.getUTCFullYear() === year && parsed.getUTCMonth() === month - 1 && parsed.getUTCDate() === day;
}

/** A `YYYY-MM-DD` string to its UTC midnight epoch millis. Caller validates the shape first. */
export function isoDateToEpochMillis(value: string): number {
  const [year, month, day] = value.split("-").map(Number);
  return Date.UTC(year, month - 1, day);
}

/** Epoch millis to `YYYY-MM-DD`, sliced from the UTC ISO string. */
export function epochMillisToIsoDate(epochMillis: number): string {
  return new Date(epochMillis).toISOString().slice(0, 10);
}

/** Today's date (UTC) as `YYYY-MM-DD`. */
export function todayIsoDate(): string {
  return epochMillisToIsoDate(Date.now());
}

/** Today's UTC midnight, as epoch millis — "end membership" sets `validTo` to this. */
export function startOfTodayEpochMillis(): number {
  return isoDateToEpochMillis(todayIsoDate());
}

/**
 * `Date.now()` behind a named function — components read "now" through this (never the bare
 * global), so the impure read is an explicit, greppable call site rather than inline in a render
 * body (react-hooks/purity).
 */
export function nowEpochMillis(): number {
  return Date.now();
}
