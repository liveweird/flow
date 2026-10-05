/**
 * `YYYY-MM-DD` ISO-date helpers shared by the metrics settings form (holidays), the reports (days
 * in the configured zone) and D1's dated Jira-user team membership (valid-from/valid-to, which are
 * calendar days in that same zone). The plain conversions are UTC; the `…InZone` ones take the
 * configured IANA zone. Neither reads the browser's local time zone, so results are the same in every
 * test/CI locale (the `utils/dataSourceState.ts` `formatEpochMillis` convention: never `toLocaleString()`).
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

/**
 * Epoch millis to the `YYYY-MM-DD` calendar date it falls on in an IANA `timeZone` (the reports
 * read and cut their days in `metrics.settings.time_zone`, not UTC). `en-CA` because its date
 * format IS the ISO one; the parts are read individually rather than trusting that formatting.
 */
export function epochMillisToIsoDateInZone(epochMillis: number, timeZone: string): string {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone, year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(
    new Date(epochMillis),
  );
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((p) => p.type === type)?.value ?? "";
  return `${part("year")}-${part("month")}-${part("day")}`;
}

/** Today's date as `YYYY-MM-DD` — in UTC by default, in `timeZone` where the caller has one (reports). */
export function todayIsoDate(timeZone = "UTC"): string {
  return epochMillisToIsoDateInZone(Date.now(), timeZone);
}

/**
 * The first instant of the calendar day containing `epochMillis` in an IANA `timeZone`, as epoch
 * millis — the zone's midnight, which is NOT a fixed offset from UTC midnight (DST). Found by
 * bisecting for the first millisecond whose zone date equals the day's: no offset arithmetic, so a
 * 23/25-hour DST-change day and a zone whose midnight is skipped (the day then starts at the
 * transition) come out right. 36 hours back always lies on an earlier date, and the zone date is
 * monotone in between.
 */
export function startOfDayEpochMillisInZone(epochMillis: number, timeZone: string): number {
  const day = epochMillisToIsoDateInZone(epochMillis, timeZone);
  let before = epochMillis - 36 * 60 * 60 * 1000; // zone date != day
  let from = epochMillis; // zone date == day
  while (from - before > 1) {
    const mid = before + Math.floor((from - before) / 2);
    if (epochMillisToIsoDateInZone(mid, timeZone) === day) from = mid;
    else before = mid;
  }
  return from;
}

/**
 * Today's midnight in `timeZone` (the configured metrics zone — `GET /metrics-settings`'s
 * `timeZone`, the zone the server's `WorkingCalendar` cuts days in), as epoch millis — "end
 * membership" sets `validTo` to this.
 */
export function startOfTodayEpochMillis(timeZone: string): number {
  return startOfDayEpochMillisInZone(Date.now(), timeZone);
}

/**
 * A `YYYY-MM-DD` calendar day in `timeZone` to the epoch millis of that day's first instant there —
 * what a day picked in a form means to the server (days are cut in the configured zone). Caller
 * validates the shape first. Round-trips with [epochMillisToIsoDateInZone].
 */
export function isoDateToEpochMillisInZone(value: string, timeZone: string): number {
  // Noon UTC lands on the target day or a neighbour in any zone (offsets are within ±14 h);
  // one 24 h step puts it on the target day, then the day's start is found from there.
  let anchor = isoDateToEpochMillis(value) + 12 * 60 * 60 * 1000;
  const zoneDay = epochMillisToIsoDateInZone(anchor, timeZone);
  if (zoneDay > value) anchor -= 24 * 60 * 60 * 1000;
  else if (zoneDay < value) anchor += 24 * 60 * 60 * 1000;
  return startOfDayEpochMillisInZone(anchor, timeZone);
}

/**
 * `Date.now()` behind a named function — components read "now" through this (never the bare
 * global), so the impure read is an explicit, greppable call site rather than inline in a render
 * body (react-hooks/purity).
 */
export function nowEpochMillis(): number {
  return Date.now();
}
