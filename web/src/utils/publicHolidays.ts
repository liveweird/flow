import type { PublicHoliday } from "../api/metrics";
import { nowEpochMillis } from "./isoDate";

/** The current year in the BROWSER's local time (the page opens on the year the viewer is in; not the UTC `todayIsoDate`). */
export const localYear = (): string => String(new Date(nowEpochMillis()).getFullYear());

/** The year of a `YYYY-MM-DD` date, as the four-digit string the year `Select` holds. */
export const yearOf = (iso: string): string => iso.slice(0, 4);

/**
 * The year picker's options, ascending: every year the dictionary holds, plus the current year (the
 * default view, even when it has no holiday yet) and the year being viewed (so deleting its last
 * holiday never leaves the picker without its own value).
 */
export function holidayYears(holidays: ReadonlyArray<PublicHoliday>, currentYear: string, viewedYear: string): string[] {
  return [...new Set([...holidays.map((h) => yearOf(h.date)), currentYear, viewedYear])].sort();
}

/** The holidays of one year, in the date order the server answers them in. */
export const holidaysOfYear = (holidays: ReadonlyArray<PublicHoliday>, year: string): PublicHoliday[] =>
  holidays.filter((h) => yearOf(h.date) === year);

const weekdayFormatters = new Map<string, Intl.DateTimeFormat>();

/** The full weekday name of a calendar date in `language`, first letter upper-cased (Polish names are lower-case in Intl; UTC arithmetic: the date is a plain day, never an instant). */
export function weekdayName(iso: string, language: string): string {
  let formatter = weekdayFormatters.get(language);
  if (formatter === undefined) {
    formatter = new Intl.DateTimeFormat(language, { weekday: "long", timeZone: "UTC" });
    weekdayFormatters.set(language, formatter);
  }
  const name = formatter.format(new Date(`${iso}T00:00:00Z`));
  return name.charAt(0).toLocaleUpperCase(language) + name.slice(1);
}
