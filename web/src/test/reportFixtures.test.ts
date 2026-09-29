import { describe, expect, test } from "vitest";
import {
  CONSISTENCY_UNIT,
  THROUGHPUT_MONTHS,
  THROUGHPUT_UNIT,
  VELOCITY_UNIT,
} from "./reportFixtures";

// The fixtures stand in for server responses, so they must obey the invariants the server does —
// a fixture that quietly violates one would let a page test pass on data no report can return.

type Figures = (typeof CONSISTENCY_UNIT.sprints)[number];

function expectPartition(figures: Omit<Figures, "sprintId" | "name" | "teamId" | "completedAt" | "snapshot" | "drift">) {
  // A17: the final scope is exactly what was delivered, carried over or dropped …
  expect(figures.finalMd).toBeCloseTo(figures.deliveredMd + figures.carriedOverMd + figures.droppedMd, 6);
  expect(figures.finalItems).toBe(figures.deliveredItems + figures.carriedOverItems + figures.droppedItems);
  // … and what was committed or added (MD too, since nothing here was re-estimated).
  expect(figures.finalMd).toBeCloseTo(figures.committedMd + figures.addedMd, 6);
  expect(figures.finalItems).toBe(figures.committedItems + figures.addedItems);
}

describe("report fixtures hold the server's invariants", () => {
  test("every consistency sprint, and its frozen snapshot, satisfies the A17 partition", () => {
    for (const sprint of CONSISTENCY_UNIT.sprints) {
      expectPartition(sprint);
      if (sprint.snapshot) expectPartition(sprint.snapshot);
    }
  });

  test("consistency groups (UNIT) sum to the sprints, figure by figure", () => {
    const keys = Object.keys(CONSISTENCY_UNIT.sprints[0]).filter((key) => /(Md|Items)$/.test(key)) as (keyof Figures)[];
    for (const key of keys) {
      const sprintsTotal = CONSISTENCY_UNIT.sprints.reduce((sum, s) => sum + (s[key] as number), 0);
      const groupsTotal = CONSISTENCY_UNIT.groups.reduce((sum, g) => sum + (g[key as keyof typeof g] as number), 0);
      expect(groupsTotal).toBeCloseTo(sprintsTotal, 6);
    }
  });

  test("throughput groups sum to the period buckets (and the month variant is its own consistent set)", () => {
    for (const report of [THROUGHPUT_UNIT]) {
      const groupsMd = report.groups.reduce((s, g) => s + g.deliveredMd, 0);
      const bucketsMd = report.byBucket.reduce((s, b) => s + b.deliveredMd, 0);
      expect(groupsMd).toBeCloseTo(bucketsMd, 6);
      expect(report.groups.reduce((s, g) => s + g.deliveredItems, 0)).toBe(
        report.byBucket.reduce((s, b) => s + b.deliveredItems, 0),
      );
    }
    expect(THROUGHPUT_MONTHS.byBucket.every((b) => b.bucketStart.endsWith("-01"))).toBe(true);
  });

  test("velocity groups sum the sprints' final scope", () => {
    const groupsFinal = VELOCITY_UNIT.groups.reduce((s, g) => s + g.finalMd, 0);
    const sprintsFinal = VELOCITY_UNIT.sprints.reduce((s, x) => s + x.finalMd, 0);
    expect(groupsFinal).toBeCloseTo(sprintsFinal, 6);
  });
});
