import { epochMillisToIsoDateInZone } from "./isoDate";

/**
 * Epoch millis to the app's one date rendering, `YYYY-MM-DD` — never `toLocaleString()`
 * (deterministic across test/CI locales; the `dataSourceState.ts` `formatEpochMillis`
 * convention, ported from Lettuce's `formatDate` with that one difference). The calendar day is
 * read in `timeZone` (UTC by default; reports pass the configured `metrics.settings.time_zone`, so
 * a completion at 22:30Z shows as the next day in Warsaw). `null`/`undefined` (an open sprint has
 * no completion date) renders as the fallback.
 */
export function formatDate(epochMillis: number | null | undefined, fallback = "—", timeZone = "UTC"): string {
  if (epochMillis == null) return fallback;
  return epochMillisToIsoDateInZone(epochMillis, timeZone);
}

/**
 * Epoch millis as `YYYY-MM-DD HH:mm` read in `timeZone` (the print header's generation stamp). `sv-SE` because its
 * date-time format IS that one; the parts are read individually rather than trusting the formatting.
 */
export function formatDateTime(epochMillis: number, timeZone = "UTC"): string {
  const parts = new Intl.DateTimeFormat("sv-SE", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(new Date(epochMillis));
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((p) => p.type === type)?.value ?? "";
  return `${part("year")}-${part("month")}-${part("day")} ${part("hour")}:${part("minute")}`;
}
