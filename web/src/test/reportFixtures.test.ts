import { describe, expect, test } from "vitest";
import type { Distribution } from "../api/reports";
import {
  ADJUSTMENTS,
  ADJUSTMENTS_EMPTY,
  CONSISTENCY_UNIT,
  EPIC_ACCURACY,
  EPIC_ACCURACY_EMPTY,
  FILTERS,
  TASK_ACCURACY,
  TASK_ACCURACY_EMPTY,
  TASK_ACCURACY_HIDDEN,
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

// ---- Estimation ---------------------------------------------------------------------------

const MIN = FILTERS.minSampleSize;

/** hidden ⇔ n < min; a hidden distribution carries counts only; a visible histogram sums to n. */
function expectWellFormed(d: Distribution) {
  expect(d.hidden).toBe(d.n < MIN);
  if (d.hidden) {
    expect(d.histogram).toEqual([]);
    expect(d.p50 ?? null).toBeNull();
  } else {
    expect(d.histogram.reduce((sum, b) => sum + b.count, 0)).toBe(d.n);
    expect(d.p50).not.toBeNull();
  }
}

describe("estimation fixtures hold the documented partitions", () => {
  test("task accuracy: n + exclusions == population per view, groups sum to the whole, hidden ⇔ below the minimum", () => {
    for (const report of [TASK_ACCURACY, TASK_ACCURACY_HIDDEN, TASK_ACCURACY_EMPTY]) {
      const x = report.excluded;
      expect(report.atStart.n + x.noWorklogs + x.neverStarted + x.unestimatedAtStart).toBe(x.population);
      expect(report.atDone.n + x.noWorklogs + x.unestimatedAtDone).toBe(x.population);
      for (const d of [report.atStart, report.atDone, ...report.groups.flatMap((g) => [g.atStart, g.atDone])]) {
        expectWellFormed(d);
      }
      for (const g of report.groups) {
        expect(g.atStart.n + g.excluded.noWorklogs + g.excluded.neverStarted + g.excluded.unestimatedAtStart).toBe(g.excluded.population);
        expect(g.atDone.n + g.excluded.noWorklogs + g.excluded.unestimatedAtDone).toBe(g.excluded.population);
      }
      if (report.groups.length > 0) {
        const sum = (pick: (g: (typeof report.groups)[number]) => number) => report.groups.reduce((s, g) => s + pick(g), 0);
        expect(sum((g) => g.atStart.n)).toBe(report.atStart.n);
        expect(sum((g) => g.atDone.n)).toBe(report.atDone.n);
        for (const key of ["population", "noWorklogs", "neverStarted", "unestimatedAtStart", "unestimatedAtDone"] as const) {
          expect(sum((g) => g.excluded[key])).toBe(x[key]);
        }
      }
    }
  });

  test("epic accuracy: the same partitions, and the listed rows agree with the distributions", () => {
    for (const report of [EPIC_ACCURACY, EPIC_ACCURACY_EMPTY]) {
      const x = report.excluded;
      expect(report.atStart.n + x.noActual + x.neverStarted + x.unestimatedAtStart).toBe(x.population);
      expect(report.atDone.n + x.noActual + x.unestimatedAtDone).toBe(x.population);
      for (const d of [report.atStart, report.atDone, ...report.groups.flatMap((g) => [g.atStart, g.atDone])]) {
        expectWellFormed(d);
      }
      // Not truncated: every DONE epic is listed, and a row carries a ratio exactly when it is in the distribution.
      expect(report.epics).toHaveLength(x.population);
      expect(report.epics.filter((e) => e.ratio !== null)).toHaveLength(report.atStart.n);
      expect(report.epics.filter((e) => e.ratioAtDone !== null)).toHaveLength(report.atDone.n);
      if (report.groups.length > 0) {
        const sum = (pick: (g: (typeof report.groups)[number]) => number) => report.groups.reduce((s, g) => s + pick(g), 0);
        expect(sum((g) => g.atStart.n)).toBe(report.atStart.n);
        expect(sum((g) => g.atDone.n)).toBe(report.atDone.n);
        for (const key of ["population", "noActual", "neverStarted", "unestimatedAtStart", "unestimatedAtDone"] as const) {
          expect(sum((g) => g.excluded[key])).toBe(x[key]);
        }
      }
    }
  });

  test("adjustments: change n + estimated late + unestimated == population, late ≤ changed ≤ started, share = changed ÷ started", () => {
    const kinds = (report: typeof ADJUSTMENTS) => [
      report.tasks,
      report.epics,
      ...report.groups.flatMap((g) => (g.epics ? [g.tasks, g.epics] : [g.tasks])),
    ];
    for (const f of [...kinds(ADJUSTMENTS), ...kinds(ADJUSTMENTS_EMPTY)]) {
      expect(f.changeDistribution.n + f.changeExcluded.estimatedLate + f.changeExcluded.unestimated).toBe(f.changeExcluded.population);
      expectWellFormed(f.changeDistribution);
      expect(f.estimatedLate).toBeLessThanOrEqual(f.changedAfterStart);
      expect(f.changedAfterStart).toBeLessThanOrEqual(f.started);
      // The share is withheld (null) below the minimum sample, and is the plain fraction otherwise.
      if (f.started < MIN) expect(f.share).toBeNull();
      else expect(f.share).toBeCloseTo(f.changedAfterStart / f.started, 6);
    }
    // Groups sum to the whole, for both kinds.
    for (const key of ["started", "changedAfterStart", "estimatedLate"] as const) {
      expect(ADJUSTMENTS.groups.reduce((s, g) => s + g.tasks[key], 0)).toBe(ADJUSTMENTS.tasks[key]);
      expect(ADJUSTMENTS.groups.reduce((s, g) => s + (g.epics?.[key] ?? 0), 0)).toBe(ADJUSTMENTS.epics[key]);
    }
    expect(ADJUSTMENTS.groups.reduce((s, g) => s + g.tasks.changeExcluded.population, 0)).toBe(ADJUSTMENTS.tasks.changeExcluded.population);
  });
});
