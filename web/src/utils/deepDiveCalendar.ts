import type { DeepDiveReport } from "../api/reports";
import { DAY_MS, epochMillisToIsoDate, isoDateToEpochMillis } from "./isoDate";

/**
 * The Deep dive's time columns: the calendar side of the matrix model. A report's range is partitioned
 * into columns, always PARTITIONING the range's days. A month column expands into ISO weeks CLIPPED to
 * the month (a week crossing a month or year boundary is two columns, one per side) and a week into
 * its days; `expandedColumns` mixes the three grains per an expansion set, `grainColumns` is the
 * uniform case. `buildDeepDiveMatrix` (`deepDiveMatrix.ts`) aggregates over any such partition.
 */

export type DeepDiveGrain = "month" | "week" | "day";

export interface TimeColumn {
  /** Unique within a partition and stable across drills: `month:2026-09`, `week:<first day>`, `day:<date>`. */
  id: string;
  kind: DeepDiveGrain;
  /** The id of the column this one expands from (`null` for a month). */
  parentId: string | null;
  /** First and last day, as inclusive offsets from `range.from`. */
  fromDay: number;
  toDay: number;
  fromDate: string;
  toDate: string;
  /** A language-neutral caption: `2026-09`, the ISO week `2026-W37`, or the date. */
  label: string;
  /** The number of days the column spans. */
  days: number;
  /** True only when EVERY day of the column is a non-working day (the client hatches it). */
  nonWorking: boolean;
  /** True for a month or week that spans more than one day (what a drill header may open). */
  expandable: boolean;
}

/** The part of a report the time columns depend on. */
export type CalendarInput = Pick<DeepDiveReport, "range" | "nonWorkingDays">;

interface Calendar {
  fromEpochDay: number;
  days: number;
  nonWorking: ReadonlySet<number>;
}

export function calendarOf(input: CalendarInput): Calendar {
  const fromEpochDay = Math.round(
    isoDateToEpochMillis(input.range.from) / DAY_MS,
  );
  const toEpochDay = Math.round(isoDateToEpochMillis(input.range.to) / DAY_MS);
  return {
    fromEpochDay,
    days: Math.max(1, toEpochDay - fromEpochDay + 1),
    nonWorking: new Set(input.nonWorkingDays),
  };
}

const dateOf = (cal: Calendar, offset: number) =>
  epochMillisToIsoDate((cal.fromEpochDay + offset) * DAY_MS);

/** Monday = 0 … Sunday = 6 (epoch day 0, 1970-01-01, was a Thursday). */
const weekdayOf = (cal: Calendar, offset: number) =>
  (((cal.fromEpochDay + offset + 3) % 7) + 7) % 7;

/** The ISO week (`2026-W37`) a date belongs to: the year and number of the Thursday of its Monday-start week. */
function isoWeekLabel(cal: Calendar, offset: number): string {
  const thursday = cal.fromEpochDay + offset + (3 - weekdayOf(cal, offset));
  const year = new Date(thursday * DAY_MS).getUTCFullYear();
  const dayOfYear = thursday - Date.UTC(year, 0, 1) / DAY_MS;
  return `${year}-W${String(Math.floor(dayOfYear / 7) + 1).padStart(2, "0")}`;
}

function column(
  cal: Calendar,
  kind: DeepDiveGrain,
  parentId: string | null,
  fromDay: number,
  toDay: number,
): TimeColumn {
  const fromDate = dateOf(cal, fromDay);
  let nonWorking = true;
  for (let d = fromDay; d <= toDay && nonWorking; d += 1)
    nonWorking = cal.nonWorking.has(d);
  const days = toDay - fromDay + 1;
  const label =
    kind === "month"
      ? fromDate.slice(0, 7)
      : kind === "week"
        ? isoWeekLabel(cal, fromDay)
        : fromDate;
  const id = kind === "month" ? `month:${label}` : `${kind}:${fromDate}`;
  return {
    id,
    kind,
    parentId,
    fromDay,
    toDay,
    fromDate,
    toDate: dateOf(cal, toDay),
    label,
    days,
    nonWorking,
    expandable: kind !== "day" && days > 1,
  };
}

function monthColumns(cal: Calendar): TimeColumn[] {
  const columns: TimeColumn[] = [];
  for (let start = 0; start < cal.days;) {
    const [year, month] = dateOf(cal, start).split("-").map(Number);
    // Date.UTC(year, month, 0) is the last day of the 1-based `month`.
    const lastOfMonth = Date.UTC(year, month, 0) / DAY_MS - cal.fromEpochDay;
    const end = Math.min(cal.days - 1, lastOfMonth);
    columns.push(column(cal, "month", null, start, end));
    start = end + 1;
  }
  return columns;
}

function children(cal: Calendar, parent: TimeColumn): TimeColumn[] {
  if (parent.kind === "day") return [];
  const out: TimeColumn[] = [];
  for (let start = parent.fromDay; start <= parent.toDay;) {
    const end =
      parent.kind === "week"
        ? start
        : Math.min(parent.toDay, start + (6 - weekdayOf(cal, start)));
    out.push(
      column(
        cal,
        parent.kind === "month" ? "week" : "day",
        parent.id,
        start,
        end,
      ),
    );
    start = end + 1;
  }
  return out;
}

function partition(
  cal: Calendar,
  shouldExpand: (column: TimeColumn) => boolean,
): TimeColumn[] {
  const out: TimeColumn[] = [];
  const visit = (col: TimeColumn) => {
    // `expandable` is only what a drill header may open; a uniform grain still reaches its own kind on a one-day month.
    if (col.kind !== "day" && shouldExpand(col))
      children(cal, col).forEach(visit);
    else out.push(col);
  };
  monthColumns(cal).forEach(visit);
  return out;
}

/** One grain throughout: months, ISO weeks clipped to their month, or single days. */
export function grainColumns(
  report: CalendarInput,
  grain: DeepDiveGrain,
): TimeColumn[] {
  return partition(
    calendarOf(report),
    (col) => grain === "day" || (grain === "week" && col.kind === "month"),
  );
}

/** The drill state's partition: months, with the columns whose id is in `expanded` replaced by their children. */
export function expandedColumns(
  report: CalendarInput,
  expanded: ReadonlySet<string>,
): TimeColumn[] {
  return partition(calendarOf(report), (col) => expanded.has(col.id));
}
