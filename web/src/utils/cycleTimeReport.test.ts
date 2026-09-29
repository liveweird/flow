import { describe, expect, test } from "vitest";
import { CYCLE_TIME, CYCLE_TIME_ALL_HIDDEN } from "../test/reportFixtures";
import { cycleTrendRows, trendHasPoints } from "./cycleTimeReport";

describe("cycleTimeReport", () => {
  test("a hidden bucket keeps its nulls — a gap, never a zero — and its real n", () => {
    const rows = cycleTrendRows(CYCLE_TIME.trend, "WEEK");
    expect(rows.map((r) => r.label)).toEqual(["2026-09-07", "2026-09-14", "2026-09-21", "2026-09-28"]);
    expect(rows[1]).toEqual({ label: "2026-09-14", p50: null, p90: null, n: 3 });
    expect(rows[3]).toEqual({ label: "2026-09-28", p50: null, p90: null, n: 0 });
    expect(rows.every((r) => r.p50 !== 0 && r.p90 !== 0)).toBe(true);
  });

  test("months are labelled year-month", () => {
    expect(cycleTrendRows([{ bucketStart: "2026-08-01", p50: 4, p90: 8, n: 11 }], "MONTH")[0].label).toBe("2026-08");
  });

  test("a trend is plottable when at least one bucket has a point", () => {
    expect(trendHasPoints(cycleTrendRows(CYCLE_TIME.trend, "WEEK"))).toBe(true);
    expect(trendHasPoints(cycleTrendRows(CYCLE_TIME_ALL_HIDDEN.trend, "WEEK"))).toBe(false);
    expect(trendHasPoints([])).toBe(false);
  });
});
