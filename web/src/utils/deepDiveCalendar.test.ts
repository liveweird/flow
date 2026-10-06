import { describe, expect, test } from "vitest";
import { MONTH_CROSSING, YEAR_CROSSING, deepDiveReport } from "../test/deepDiveFixtures";
import { expandedColumns, grainColumns, type DeepDiveGrain, type TimeColumn } from "./deepDiveCalendar";

const GRAINS: readonly DeepDiveGrain[] = ["month", "week", "day"];
const spans = (columns: readonly TimeColumn[]) =>
  columns.map((c) => [c.fromDay, c.toDay]);

function expectPartition(columns: readonly TimeColumn[], days: number) {
  expect(columns[0].fromDay).toBe(0);
  expect(columns[columns.length - 1].toDay).toBe(days - 1);
  columns.forEach((c, i) => {
    expect(c.toDay).toBeGreaterThanOrEqual(c.fromDay);
    expect(c.days).toBe(c.toDay - c.fromDay + 1);
    if (i > 0) expect(c.fromDay).toBe(columns[i - 1].toDay + 1);
  });
  expect(new Set(columns.map((c) => c.id)).size).toBe(columns.length);
}

describe("time columns", () => {
  test("every grain partitions the days of a range crossing a month boundary mid-week", () => {
    const report = deepDiveReport(MONTH_CROSSING);
    for (const grain of GRAINS)
      expectPartition(grainColumns(report, grain), 13);
    expect(spans(grainColumns(report, "month"))).toEqual([
      [0, 4],
      [5, 12],
    ]);
    expect(grainColumns(report, "month").map((c) => c.label)).toEqual([
      "2026-08",
      "2026-09",
    ]);
    expect(grainColumns(report, "day")).toHaveLength(13);
  });

  test("weeks are ISO weeks clipped to their month: the week crossing the boundary is two columns", () => {
    const weeks = grainColumns(deepDiveReport(MONTH_CROSSING), "week");
    expect(spans(weeks)).toEqual([
      [0, 3],
      [4, 4],
      [5, 10],
      [11, 12],
    ]);
    expect(weeks.map((c) => c.label)).toEqual([
      "2026-W35",
      "2026-W36",
      "2026-W36",
      "2026-W37",
    ]);
    expect(weeks.map((c) => c.parentId)).toEqual([
      "month:2026-08",
      "month:2026-08",
      "month:2026-09",
      "month:2026-09",
    ]);
    expect(weeks.map((c) => [c.fromDate, c.toDate])).toEqual([
      ["2026-08-27", "2026-08-30"],
      ["2026-08-31", "2026-08-31"],
      ["2026-09-01", "2026-09-06"],
      ["2026-09-07", "2026-09-08"],
    ]);
  });

  test("a range crossing a year boundary partitions at every grain and labels the ISO week-year", () => {
    const report = deepDiveReport(YEAR_CROSSING);
    for (const grain of GRAINS)
      expectPartition(grainColumns(report, grain), 10);
    const weeks = grainColumns(report, "week");
    expect(spans(weeks)).toEqual([
      [0, 1],
      [2, 4],
      [5, 8],
      [9, 9],
    ]);
    // 2025-12-29 .. 2026-01-04 is ISO week 2026-W01, split by the year/month edge.
    expect(weeks.map((c) => c.label)).toEqual([
      "2025-W52",
      "2026-W01",
      "2026-W01",
      "2026-W02",
    ]);
    expect(grainColumns(report, "month").map((c) => c.id)).toEqual([
      "month:2025-12",
      "month:2026-01",
    ]);
  });

  test("ISO week 53 is labelled with the week-year it belongs to (2026-W53 into 2027-W01, and 2020-W53 on 2021-01-01)", () => {
    const week53 = deepDiveReport({
      range: { from: "2026-12-30", to: "2027-01-05", asOfDay: null },
      nonWorkingDays: [],
    });
    const weeks = grainColumns(week53, "week");
    expect(spans(weeks)).toEqual([
      [0, 1],
      [2, 4],
      [5, 6],
    ]);
    expect(weeks.map((c) => c.label)).toEqual([
      "2026-W53",
      "2026-W53",
      "2027-W01",
    ]);
    expectPartition(weeks, 7);
    const jan = deepDiveReport({
      range: { from: "2021-01-01", to: "2021-01-04", asOfDay: null },
      nonWorkingDays: [],
    });
    expect(
      grainColumns(jan, "week").map((c) => [c.label, c.fromDay, c.toDay]),
    ).toEqual([
      ["2020-W53", 0, 2],
      ["2021-W01", 3, 3],
    ]);
    expect(
      grainColumns(
        deepDiveReport({
          range: { from: "2021-01-01", to: "2021-01-01", asOfDay: null },
        }),
        "week",
      )[0].label,
    ).toBe("2020-W53");
  });

  test("a column is non-working only when every one of its days is", () => {
    const month = deepDiveReport(MONTH_CROSSING);
    expect(
      grainColumns(month, "day").flatMap((c, i) => (c.nonWorking ? [i] : [])),
    ).toEqual([2, 3, 9, 10]);
    expect(grainColumns(month, "week").map((c) => c.nonWorking)).toEqual([
      false,
      false,
      false,
      false,
    ]);
    expect(grainColumns(month, "month").map((c) => c.nonWorking)).toEqual([
      false,
      false,
    ]);
    // The clipped weekend-only week (Sat 12-27 .. Sun 12-28) is non-working as a column.
    expect(
      grainColumns(deepDiveReport(YEAR_CROSSING), "week").map(
        (c) => c.nonWorking,
      ),
    ).toEqual([true, false, false, false]);
  });

  test("a month expands into weeks and a week into days, only where the drill state says so", () => {
    const report = deepDiveReport(MONTH_CROSSING);
    expect(grainColumns(report, "month").map((c) => c.expandable)).toEqual([
      true,
      true,
    ]);
    const september = expandedColumns(report, new Set(["month:2026-09"]));
    expect(september.map((c) => c.id)).toEqual([
      "month:2026-08",
      "week:2026-09-01",
      "week:2026-09-07",
    ]);
    const drilled = expandedColumns(
      report,
      new Set(["month:2026-09", "week:2026-09-07"]),
    );
    expect(drilled.map((c) => c.id)).toEqual([
      "month:2026-08",
      "week:2026-09-01",
      "day:2026-09-07",
      "day:2026-09-08",
    ]);
    expectPartition(drilled, 13);
    // An id whose parent is collapsed is inert; no ids is the month view.
    expect(expandedColumns(report, new Set(["week:2026-09-07"]))).toEqual(
      grainColumns(report, "month"),
    );
    expect(expandedColumns(report, new Set())).toEqual(
      grainColumns(report, "month"),
    );
  });

  test("a one-day column is not expandable and a one-day range is a single column at every grain", () => {
    const report = deepDiveReport();
    for (const grain of GRAINS) {
      const columns = grainColumns(report, grain);
      expect(columns).toHaveLength(1);
      expect(columns[0].kind).toBe(grain);
      expect(columns[0].expandable).toBe(false);
    }
  });

  test("the longest range (1100 days) still partitions at every grain", () => {
    const report = deepDiveReport({
      range: { from: "2023-01-01", to: "2026-01-04", asOfDay: null },
    });
    const days = grainColumns(report, "day");
    expect(days).toHaveLength(1100);
    expectPartition(grainColumns(report, "week"), 1100);
    expectPartition(days, 1100);
  });
});
