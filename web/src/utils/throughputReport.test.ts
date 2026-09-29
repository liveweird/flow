import { describe, expect, test } from "vitest";
import { throughputChartRows, throughputTotals } from "./throughputReport";

const buckets = [
  { bucketStart: "2026-09-07", deliveredMd: 12.5, deliveredItems: 5 },
  { bucketStart: "2026-10-01", deliveredMd: 1, deliveredItems: 1 },
];

describe("throughputReport", () => {
  test("weeks are labelled by their first day, months by year-month", () => {
    expect(throughputChartRows(buckets, "WEEK").map((r) => r.label)).toEqual(["2026-09-07", "2026-10-01"]);
    expect(throughputChartRows(buckets, "MONTH").map((r) => r.label)).toEqual(["2026-09", "2026-10"]);
    expect(throughputChartRows(buckets, "WEEK")[0]).toEqual({ label: "2026-09-07", deliveredMd: 12.5, deliveredItems: 5 });
  });

  test("totals sum MD and items; nothing delivered is zero", () => {
    expect(throughputTotals(buckets)).toEqual({ md: 13.5, items: 6 });
    expect(throughputTotals([])).toEqual({ md: 0, items: 0 });
  });
});
