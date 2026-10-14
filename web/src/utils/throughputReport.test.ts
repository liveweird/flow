import { describe, expect, test } from "vitest";
import type { ReportFilters } from "../api/reports";
import { FILTERS, THROUGHPUT_TEAM, THROUGHPUT_USERS } from "../test/reportFixtures";
import {
  throughputBuckets,
  throughputPlot,
  throughputSeries,
  throughputShareRows,
  throughputSprints,
  throughputTotals,
} from "./throughputReport";

const buckets = [
  { bucketStart: "2026-09-07", deliveredMd: 12.5, deliveredItems: 5, byUser: [] },
  { bucketStart: "2026-10-01", deliveredMd: 1, deliveredItems: 1, byUser: [] },
];

describe("throughputReport", () => {
  test("weeks are labelled by their first day, months by year-month", () => {
    expect(throughputBuckets(buckets, "WEEK").rows.map((r) => r.label)).toEqual(["2026-09-07", "2026-10-01"]);
    expect(throughputBuckets(buckets, "MONTH").rows.map((r) => r.label)).toEqual(["2026-09", "2026-10"]);
  });

  test("totals sum MD and items; nothing delivered is zero", () => {
    expect(throughputTotals(buckets)).toEqual({ md: 13.5, items: 6 });
    expect(throughputTotals([])).toEqual({ md: 0, items: 0 });
  });

  describe("the cumulative helper", () => {
    test("a whole team keeps each slot's own figures beside the running totals; there are no people", () => {
      const series = throughputBuckets(THROUGHPUT_TEAM.byBucket, "WEEK");
      expect(series.people).toEqual([]);
      expect(series.rows.map((r) => [r.deliveredMd, r.cumulativeMd, r.deliveredItems, r.cumulativeItems])).toEqual([
        [12.5, 12.5, 5, 5],
        [0, 12.5, 0, 5],
        [15.5, 28, 6, 11],
      ]);
      expect(series.rows.every((r) => r.shares.length === 0)).toBe(true);
    });

    test("running sums are free of binary noise", () => {
      const slots = [0.1, 0.2, 0.3].map((md, i) => ({ label: `s${i}`, deliveredMd: md, deliveredItems: 1, byUser: [] }));
      expect(throughputSeries(slots).rows.map((r) => r.cumulativeMd)).toEqual([0.1, 0.3, 0.6]);
    });

    test("people are numbered in request order and carry their own running totals, which add up to the slot's", () => {
      const series = throughputBuckets(THROUGHPUT_USERS.byBucket, "WEEK");
      expect(series.people).toEqual([
        { accountId: "acc-ann", label: "Ann Author", position: 1, key: "p0" },
        { accountId: "acc-bob", label: "Bob Builder", position: 2, key: "p1" },
      ]);
      expect(series.rows.map((r) => r.shares.map((s) => s.cumulativeMd))).toEqual([
        [6, 3],
        [6, 3],
        [10.5, 6],
      ]);
      expect(series.rows.map((r) => r.shares.map((s) => s.cumulativeItems))).toEqual([
        [3, 1],
        [3, 1],
        [5, 2],
      ]);
      for (const row of series.rows) {
        expect(row.shares.reduce((sum, s) => sum + s.cumulativeMd, 0)).toBeCloseTo(row.cumulativeMd, 6);
        expect(row.shares.reduce((sum, s) => sum + s.cumulativeItems, 0)).toBe(row.cumulativeItems);
      }
    });

    test("a person a slot does not list counts 0 there, and one who first appears later is still in the stack", () => {
      const series = throughputSeries([
        { label: "a", deliveredMd: 2, deliveredItems: 1, byUser: [{ accountId: "x", label: "X", deliveredMd: 2, deliveredItems: 1 }] },
        {
          label: "b",
          deliveredMd: 3,
          deliveredItems: 2,
          byUser: [
            { accountId: "y", label: "Y", deliveredMd: 1, deliveredItems: 1 },
            { accountId: "x", label: "X", deliveredMd: 2, deliveredItems: 1 },
          ],
        },
      ]);
      expect(series.people.map((p) => p.accountId)).toEqual(["x", "y"]);
      expect(series.rows[0].shares.map((s) => s.cumulativeMd)).toEqual([2, 0]);
      expect(series.rows[1].shares.map((s) => s.cumulativeMd)).toEqual([4, 1]);
    });

    test("no slots give an empty series", () => {
      expect(throughputSeries([])).toEqual({ rows: [], people: [] });
      expect(throughputPlot(throughputSeries([]), true)).toEqual([]);
    });
  });

  test("the plot carries the per-slot figure or the running total under md, items and each person's key", () => {
    const series = throughputBuckets(THROUGHPUT_USERS.byBucket, "WEEK");
    expect(throughputPlot(series, false)[2]).toEqual({ label: "2026-09-21", md: 7.5, items: 3, p0: 4.5, p1: 3 });
    expect(throughputPlot(series, true)[2]).toEqual({ label: "2026-09-21", md: 16.5, items: 7, p0: 10.5, p1: 6 });
    const team = throughputBuckets(THROUGHPUT_TEAM.byBucket, "WEEK");
    expect(throughputPlot(team, true).map((r) => r.md)).toEqual([12.5, 12.5, 28]);
    expect(throughputPlot(team, false).map((r) => r.md)).toEqual([12.5, 0, 15.5]);
  });

  test("the sprint series runs in completion order, whatever the order served, with the team's sprints labelled by name", () => {
    const series = throughputSprints([...THROUGHPUT_USERS.bySprint].reverse(), FILTERS);
    expect(series.rows.map((r) => [r.label, r.deliveredMd, r.cumulativeMd])).toEqual([
      ["Alpha 1", 6, 6],
      ["Alpha 2", 7.5, 13.5],
    ]);
    expect(series.people.map((p) => p.position)).toEqual([1, 2]);
  });

  test("sprints of several teams are labelled with their team", () => {
    const teams: ReportFilters = FILTERS;
    const series = throughputSprints(
      [
        { ...THROUGHPUT_USERS.bySprint[0], teamId: 1 },
        { ...THROUGHPUT_USERS.bySprint[1], teamId: 2, sprintId: 99 },
      ],
      teams,
    );
    expect(series.rows.map((r) => r.label)).toEqual(["Alpha · Alpha 1", "Beta · Alpha 2"]);
  });

  test("share rows are one per slot and person, numbered as in the chart", () => {
    const rows = throughputShareRows(throughputBuckets(THROUGHPUT_USERS.byBucket, "WEEK"));
    expect(rows).toHaveLength(6);
    expect(rows[1]).toMatchObject({ label: "2026-09-07", person: { position: 2 }, figures: { deliveredMd: 3, cumulativeMd: 3 } });
    expect(rows[5]).toMatchObject({ label: "2026-09-21", person: { position: 2 }, figures: { deliveredMd: 3, cumulativeMd: 6 } });
    expect(new Set(rows.map((r) => r.key)).size).toBe(6);
    expect(throughputShareRows({ rows: [], people: [] })).toEqual([]);
  });
});
