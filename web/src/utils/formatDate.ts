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
