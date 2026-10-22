import { describe, expect, test } from "vitest";
import type { ReportFilters } from "../api/reports";
import { FILTERS, THROUGHPUT_TEAM, THROUGHPUT_USERS } from "../test/reportFixtures";
import {
  throughputBuckets,
  throughputCostColumn,
  throughputCumulativeColumns,
  throughputPlot,
  throughputSeries,
  throughputShareRows,
  throughputSprints,
  throughputTotals,
} from "./throughputReport";

const buckets = [
  { bucketStart: "2026-09-07", deliveredMd: 12.5, deliveredItems: 5, actualCostMd: 14, byUser: [] },
  { bucketStart: "2026-10-01", deliveredMd: 1, deliveredItems: 1, actualCostMd: 2, byUser: [] },
];

describe("throughputReport", () => {
  test("every slot carries its drill id: a bucket's first day, a sprint's team and id", () => {
    expect(throughputBuckets(buckets, "MONTH").rows.map((r) => r.id)).toEqual(["2026-09-07", "2026-10-01"]);
    const sprints = throughputSprints(THROUGHPUT_USERS.bySprint, FILTERS);
    expect(sprints.rows.map((r) => r.id)).toEqual(THROUGHPUT_USERS.bySprint.map((s) => `${s.teamId}:${s.sprintId}`));
    expect(throughputPlot(throughputBuckets(buckets, "WEEK"), false).map((r) => r.id)).toEqual(["2026-09-07", "2026-10-01"]);
  });

  test("weeks are labelled by their first day, months by year-month", () => {
    expect(throughputBuckets(buckets, "WEEK").rows.map((r) => r.label)).toEqual(["2026-09-07", "2026-10-01"]);
    expect(throughputBuckets(buckets, "MONTH").rows.map((r) => r.label)).toEqual(["2026-09", "2026-10"]);
  });

  test("totals sum MD and items; nothing delivered is zero", () => {
    expect(throughputTotals(buckets)).toEqual({ md: 13.5, items: 6, costMd: 16 });
    expect(throughputTotals([])).toEqual({ md: 0, items: 0, costMd: 0 });
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
      // The actual cost accumulates like the delivered scope: the slot's own time beside the running total.
      expect(series.rows.map((r) => [r.actualCostMd, r.cumulativeCostMd])).toEqual([
        [13.5, 13.5],
        [0, 13.5],
        [16.5, 30],
      ]);
      expect(series.rows.every((r) => r.shares.length === 0)).toBe(true);
    });

    test("running sums are free of binary noise", () => {
      const slots = [0.1, 0.2, 0.3].map((md, i) => ({ id: `s${i}`, label: `s${i}`, deliveredMd: md, deliveredItems: 1, actualCostMd: md, byUser: [] }));
      expect(throughputSeries(slots).rows.map((r) => r.cumulativeMd)).toEqual([0.1, 0.3, 0.6]);
      expect(throughputSeries(slots).rows.map((r) => r.cumulativeCostMd)).toEqual([0.1, 0.3, 0.6]);
    });

    test("people are numbered in request order and carry their own running totals, which add up to the slot's", () => {
      const series = throughputBuckets(THROUGHPUT_USERS.byBucket, "WEEK");
      expect(series.people).toEqual([
        { accountId: "acc-ann", label: "Ann Author", position: 1, key: "p0", costKey: "a0" },
        { accountId: "acc-bob", label: "Bob Builder", position: 2, key: "p1", costKey: "a1" },
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
      // Each person's own logged time accumulates too (by worklog author, not by assignee).
      expect(series.rows.map((r) => r.shares.map((s) => s.cumulativeCostMd))).toEqual([
        [7, 4],
        [7, 4],
        [12.5, 8],
      ]);
      for (const row of series.rows) {
        expect(row.shares.reduce((sum, s) => sum + s.cumulativeCostMd, 0)).toBeCloseTo(row.cumulativeCostMd, 6);
        expect(row.shares.reduce((sum, s) => sum + s.cumulativeMd, 0)).toBeCloseTo(row.cumulativeMd, 6);
        expect(row.shares.reduce((sum, s) => sum + s.cumulativeItems, 0)).toBe(row.cumulativeItems);
      }
    });

    test("a person a slot does not list counts 0 there, and one who first appears later is still in the stack", () => {
      const series = throughputSeries([
        {
          id: "a",
          label: "a",
          deliveredMd: 2,
          deliveredItems: 1,
          actualCostMd: 4,
          byUser: [{ accountId: "x", label: "X", deliveredMd: 2, deliveredItems: 1, actualCostMd: 4 }],
        },
        {
          id: "b",
          label: "b",
          deliveredMd: 3,
          deliveredItems: 2,
          actualCostMd: 5,
          byUser: [
            { accountId: "y", label: "Y", deliveredMd: 1, deliveredItems: 1, actualCostMd: 1 },
            { accountId: "x", label: "X", deliveredMd: 2, deliveredItems: 1, actualCostMd: 4 },
          ],
        },
      ]);
      expect(series.people.map((p) => p.accountId)).toEqual(["x", "y"]);
      expect(series.rows[0].shares.map((s) => s.cumulativeMd)).toEqual([2, 0]);
      expect(series.rows[1].shares.map((s) => s.cumulativeMd)).toEqual([4, 1]);
      expect(series.rows[0].shares.map((s) => s.cumulativeCostMd)).toEqual([4, 0]);
      expect(series.rows[1].shares.map((s) => s.cumulativeCostMd)).toEqual([8, 1]);
    });

    test("no slots give an empty series", () => {
      expect(throughputSeries([])).toEqual({ rows: [], people: [] });
      expect(throughputPlot(throughputSeries([]), true)).toEqual([]);
    });
  });

  test("the plot carries the per-slot figure or the running total under md, ac, items and each person's key and costKey", () => {
    const series = throughputBuckets(THROUGHPUT_USERS.byBucket, "WEEK");
    expect(throughputPlot(series, false)[2]).toEqual({ id: "2026-09-21", label: "2026-09-21", md: 7.5, ac: 9.5, items: 3, p0: 4.5, p1: 3, a0: 5.5, a1: 4 });
    expect(throughputPlot(series, true)[2]).toEqual({ id: "2026-09-21", label: "2026-09-21", md: 16.5, ac: 20.5, items: 7, p0: 10.5, p1: 6, a0: 12.5, a1: 8 });
    const team = throughputBuckets(THROUGHPUT_TEAM.byBucket, "WEEK");
    expect(throughputPlot(team, true).map((r) => r.md)).toEqual([12.5, 12.5, 28]);
    expect(throughputPlot(team, false).map((r) => r.md)).toEqual([12.5, 0, 15.5]);
    expect(throughputPlot(team, false).map((r) => r.ac)).toEqual([13.5, 0, 16.5]);
    expect(throughputPlot(team, true).map((r) => r.ac)).toEqual([13.5, 13.5, 30]);
  });

  test("the actual-cost column prints MD only and the cumulative columns add the running cost", () => {
    const t = ((key: string) => key) as never;
    const figures = { deliveredMd: 1, deliveredItems: 1, actualCostMd: 2.5, cumulativeMd: 3, cumulativeItems: 2, cumulativeCostMd: 7.25 };
    const cost = throughputCostColumn<typeof figures>(t, (row) => row);
    expect([cost.key, cost.header, cost.render(figures)]).toEqual(["actualCostMd", "reports.throughput.column.actualCostMd", "2.5"]);
    expect(throughputCumulativeColumns<typeof figures>(t, (row) => row).map((column) => [column.key, column.render(figures)])).toEqual([
      ["cumulativeMd", "3"],
      ["cumulativeItems", 2],
      ["cumulativeCostMd", "7.25"],
    ]);
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
    expect(rows[1]).toMatchObject({ slotId: "2026-09-07", label: "2026-09-07", person: { position: 2 }, figures: { deliveredMd: 3, cumulativeMd: 3 } });
    expect(rows[5]).toMatchObject({ label: "2026-09-21", person: { position: 2 }, figures: { deliveredMd: 3, cumulativeMd: 6 } });
    expect(new Set(rows.map((r) => r.key)).size).toBe(6);
    expect(throughputShareRows({ rows: [], people: [] })).toEqual([]);
  });
});
