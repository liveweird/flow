import { describe, expect, test } from "vitest";
import type { Distribution } from "../api/reports";
import {
  ADJUSTMENTS,
  AGING_EMPTY,
  AGING_HIDDEN,
  AGING_TEAM,
  AGING_TRUNCATED,
  AGING_UNIT,
  BLOCKED_EMPTY,
  BLOCKED_NONE,
  BLOCKED_TEAM_BOTH,
  BLOCKED_TIME,
  BACKLOG,
  BACKLOG_NO_PACE,
  BACKLOG_NOT_DERIVED,
  BACKLOG_PARTIAL_WINDOW,
  BACKLOG_ZERO_PACE,
  ADJUSTMENTS_EMPTY,
  ADJUSTMENTS_USER,
  ADJUSTMENTS_USERS,
  COST_MATRIX_EMPTY,
  COST_MATRIX_NO_SHARE,
  COST_MATRIX_SPRINTS,
  COST_MATRIX_TASK_VIEW,
  COST_MATRIX_TEAM,
  COST_MATRIX_THIRDS,
  COST_MATRIX_UNIT,
  COST_MATRIX_USER,
  CYCLE_TIME,
  DATA_QUALITY,
  DATA_QUALITY_CAPPED,
  DATA_QUALITY_CLEAN,
  DATA_QUALITY_TEAM,
  DATA_QUALITY_USER,
  CYCLE_TIME_ALL_HIDDEN,
  CYCLE_TIME_EMPTY,
  CYCLE_TIME_MONTHS,
  CONSISTENCY_SLICED,
  CONSISTENCY_TEAM,
  CYCLE_TIME_USER,
  CYCLE_TIME_USERS,
  CONSISTENCY_UNIT,
  EPIC_ACCURACY,
  EPIC_ACCURACY_EMPTY,
  EPIC_ACCURACY_HIDDEN,
  FILTERS,
  REPORTED_TIME,
  REPORTED_TIME_EMPTY,
  REPORTED_TIME_HIDDEN,
  TASK_ACCURACY,
  TASK_ACCURACY_EMPTY,
  TASK_ACCURACY_HIDDEN,
  TASK_ACCURACY_USER,
  TASK_ACCURACY_USERS,
  THROUGHPUT_MONTHS,
  THROUGHPUT_TEAM,
  THROUGHPUT_UNIT,
  THROUGHPUT_USER,
  THROUGHPUT_USERS,
  COMMITMENTS_UNIT,
  WIP_COLUMN,
  WIP_EPICS,
  WIP_NOT_DERIVED,
  WIP_STAGE,
  WIP_STATUS,
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

  test("the TEAM-level consistency fixtures hold the partition and their assignee groups sum to the sprint", () => {
    for (const report of [CONSISTENCY_TEAM, CONSISTENCY_SLICED]) {
      const [sprint] = report.sprints;
      expectPartition(sprint);
      if (sprint.snapshot) expectPartition(sprint.snapshot);
      const keys = Object.keys(sprint).filter((key) => /(Md|Items)$/.test(key)) as (keyof Figures)[];
      for (const key of keys) {
        const groupsTotal = report.groups.reduce((sum, g) => sum + (g[key as keyof typeof g] as number), 0);
        expect(groupsTotal).toBeCloseTo(sprint[key] as number, 6);
      }
    }
    // A sliced figure is live-only.
    expect(CONSISTENCY_SLICED.sprints.every((s) => s.snapshot === null && !s.drift)).toBe(true);
  });

  test("throughput groups sum to the period buckets (and the month variant is its own consistent set)", () => {
    for (const report of [THROUGHPUT_UNIT, THROUGHPUT_TEAM, THROUGHPUT_USER, THROUGHPUT_USERS]) {
      const groupsMd = report.groups.reduce((s, g) => s + g.deliveredMd, 0);
      const bucketsMd = report.byBucket.reduce((s, b) => s + b.deliveredMd, 0);
      expect(groupsMd).toBeCloseTo(bucketsMd, 6);
      expect(report.groups.reduce((s, g) => s + g.deliveredItems, 0)).toBe(
        report.byBucket.reduce((s, b) => s + b.deliveredItems, 0),
      );
    }
    // The people split each bucket and sprint exactly: byUser sums to its slot.
    for (const slot of [...THROUGHPUT_USERS.byBucket, ...THROUGHPUT_USERS.bySprint]) {
      expect(slot.byUser.reduce((s, u) => s + u.deliveredMd, 0)).toBeCloseTo(slot.deliveredMd, 6);
      expect(slot.byUser.reduce((s, u) => s + u.deliveredItems, 0)).toBe(slot.deliveredItems);
    }
    expect(THROUGHPUT_MONTHS.byBucket.every((b) => b.bucketStart.endsWith("-01"))).toBe(true);
  });

  test("commitments groups sum the sprints' final scope", () => {
    const groupsFinal = COMMITMENTS_UNIT.groups.reduce((s, g) => s + g.finalMd, 0);
    const sprintsFinal = COMMITMENTS_UNIT.sprints.reduce((s, x) => s + x.finalMd, 0);
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
    for (const report of [TASK_ACCURACY, TASK_ACCURACY_HIDDEN, TASK_ACCURACY_EMPTY, TASK_ACCURACY_USERS, TASK_ACCURACY_USER]) {
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

  test("task accuracy per person: the shares add up to every bucket and to each person's group n; the measures are null exactly when hidden", () => {
    for (const report of [TASK_ACCURACY_USERS, TASK_ACCURACY_USER]) {
      for (const [view, measures] of [
        ["atStart", report.accuracyAtStart],
        ["atDone", report.accuracyAtDone],
      ] as const) {
        const distribution = report[view];
        const shares = distribution.byUser ?? [];
        expect(shares.map((share) => share.accountId)).toEqual(report.groups.map((g) => g.accountId));
        distribution.histogram.forEach((bucket, i) => expect(shares.reduce((sum, share) => sum + share.counts[i], 0)).toBe(bucket.count));
        shares.forEach((share, i) => expect(share.counts.reduce((sum, count) => sum + count, 0)).toBe(report.groups[i][view].n));
        expect(measures === null).toBe(distribution.hidden);
      }
    }
    for (const report of [TASK_ACCURACY, TASK_ACCURACY_HIDDEN, TASK_ACCURACY_EMPTY]) {
      expect(report.accuracyAtStart === null).toBe(report.atStart.hidden);
      expect(report.accuracyAtDone === null).toBe(report.atDone.hidden);
    }
  });

  test("epic accuracy: the same partitions, and the listed rows agree with the distributions", () => {
    for (const report of [EPIC_ACCURACY, EPIC_ACCURACY_HIDDEN, EPIC_ACCURACY_EMPTY]) {
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
    // The measures follow their view's Distribution: present exactly when it is shown.
    for (const report of [EPIC_ACCURACY, EPIC_ACCURACY_HIDDEN, EPIC_ACCURACY_EMPTY]) {
      expect(report.accuracyAtStart === null).toBe(report.atStart.hidden);
      expect(report.accuracyAtDone === null).toBe(report.atDone.hidden);
    }
    // Tail share rule (AccuracyMeasures.kt): null below six items; the shown epic fixture has six at start and five at done.
    expect(EPIC_ACCURACY.atStart.n).toBe(6);
    expect(EPIC_ACCURACY.accuracyAtStart?.tailShare).not.toBeNull();
    expect(EPIC_ACCURACY.atDone.n).toBe(5);
    expect(EPIC_ACCURACY.accuracyAtDone?.tailShare).toBeNull();
  });

  test("adjustments: change n + unchanged + estimated late + unestimated == population, late ≤ changed ≤ started, share = changed ÷ started", () => {
    const kinds = (report: typeof ADJUSTMENTS) => [
      report.tasks,
      report.epics,
      ...report.groups.flatMap((g) => (g.epics ? [g.tasks, g.epics] : [g.tasks])),
    ];
    for (const f of [...kinds(ADJUSTMENTS), ...kinds(ADJUSTMENTS_EMPTY), ...kinds(ADJUSTMENTS_USERS), ...kinds(ADJUSTMENTS_USER)]) {
      expect(
        f.changeDistribution.n + f.changeExcluded.unchanged + f.changeExcluded.estimatedLate + f.changeExcluded.unestimated,
      ).toBe(f.changeExcluded.population);
      // The backfill is a breakout of the unestimated, not a fourth bucket.
      expect(f.changeExcluded.estimatedAfterDone).toBeLessThanOrEqual(f.changeExcluded.unestimated);
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
    for (const key of ["population", "unchanged"] as const) {
      expect(ADJUSTMENTS.groups.reduce((s, g) => s + g.tasks.changeExcluded[key], 0)).toBe(ADJUSTMENTS.tasks.changeExcluded[key]);
    }
  });

  test("adjustments per person: the groups sum to the tasks block and the shares add up to every bucket and to each person's n", () => {
    for (const report of [ADJUSTMENTS_USERS, ADJUSTMENTS_USER]) {
      for (const key of ["started", "changedAfterStart", "estimatedLate"] as const) {
        expect(report.groups.reduce((s, g) => s + g.tasks[key], 0)).toBe(report.tasks[key]);
      }
      expect(report.groups.reduce((s, g) => s + g.tasks.changeExcluded.population, 0)).toBe(report.tasks.changeExcluded.population);
      expect(report.groups.reduce((s, g) => s + g.tasks.changeDistribution.n, 0)).toBe(report.tasks.changeDistribution.n);
      expect(report.groups.every((g) => g.epics === null)).toBe(true);
      const distribution = report.tasks.changeDistribution;
      const shares = distribution.byUser ?? [];
      expect(shares.map((share) => share.accountId)).toEqual(report.groups.map((g) => g.accountId));
      distribution.histogram.forEach((bucket, i) => expect(shares.reduce((sum, share) => sum + share.counts[i], 0)).toBe(bucket.count));
      shares.forEach((share, i) =>
        expect(share.counts.reduce((sum, count) => sum + count, 0)).toBe(report.groups[i].tasks.changeDistribution.n),
      );
    }
  });
});

describe("cycle time and reported time fixtures hold the documented partitions", () => {
  test("cycle time: both views measure population − neverStarted, groups sum to the whole, hidden ⇔ below the minimum", () => {
    for (const report of [CYCLE_TIME, CYCLE_TIME_ALL_HIDDEN, CYCLE_TIME_MONTHS, CYCLE_TIME_EMPTY, CYCLE_TIME_USERS, CYCLE_TIME_USER]) {
      const measured = report.excluded.population - report.excluded.neverStarted;
      expect(report.workingDays.n).toBe(measured);
      expect(report.elapsedDays.n).toBe(measured);
      for (const d of [report.workingDays, report.elapsedDays, ...report.groups.flatMap((g) => [g.workingDays, g.elapsedDays])]) {
        expectWellFormed(d);
      }
      for (const g of report.groups) {
        expect(g.workingDays.n).toBe(g.excluded.population - g.excluded.neverStarted);
        expect(g.elapsedDays.n).toBe(g.workingDays.n);
      }
      if (report.groups.length > 0) {
        expect(report.groups.reduce((s, g) => s + g.workingDays.n, 0)).toBe(report.workingDays.n);
        expect(report.groups.reduce((s, g) => s + g.excluded.population, 0)).toBe(report.excluded.population);
        expect(report.groups.reduce((s, g) => s + g.excluded.neverStarted, 0)).toBe(report.excluded.neverStarted);
      }
    }
  });

  test("cycle time per person: the shares add up to every bucket and to each person's group n", () => {
    for (const report of [CYCLE_TIME_USERS, CYCLE_TIME_USER]) {
      for (const view of ["workingDays", "elapsedDays"] as const) {
        const distribution = report[view];
        const shares = distribution.byUser ?? [];
        expect(shares.map((share) => share.accountId)).toEqual(report.groups.map((g) => g.accountId));
        distribution.histogram.forEach((bucket, i) => expect(shares.reduce((sum, share) => sum + share.counts[i], 0)).toBe(bucket.count));
        shares.forEach((share, i) => expect(share.counts.reduce((sum, count) => sum + count, 0)).toBe(report.groups[i][view].n));
      }
    }
  });

  test("cycle time trend: the buckets' n sum to the measured tasks; p50/p90 are null exactly when n is below the minimum", () => {
    for (const report of [CYCLE_TIME, CYCLE_TIME_ALL_HIDDEN, CYCLE_TIME_MONTHS]) {
      expect(report.trend.reduce((sum, b) => sum + b.n, 0)).toBe(report.workingDays.n);
    }
    for (const b of [...CYCLE_TIME.trend, ...CYCLE_TIME_MONTHS.trend]) {
      expect(b.p50 === null).toBe(b.n < MIN);
      expect(b.p90 === null).toBe(b.n < MIN);
    }
    // The fixture really has hidden buckets to render as gaps — including an empty one — and visible ones around them.
    expect(CYCLE_TIME.trend.some((b) => b.p50 === null && b.n > 0)).toBe(true);
    expect(CYCLE_TIME.trend.some((b) => b.n === 0)).toBe(true);
    expect(CYCLE_TIME.trend.filter((b) => b.p50 !== null).length).toBeGreaterThanOrEqual(2);
  });

  test("reported time: each measure reconciles to its population, groups sum to the whole", () => {
    for (const report of [REPORTED_TIME, REPORTED_TIME_HIDDEN, REPORTED_TIME_EMPTY]) {
      const x = report.excluded;
      const f = report.flowEfficiencyExcluded;
      expect(report.ratio.n + x.noWorklogs + x.neverStarted + x.zeroCycle).toBe(x.population);
      expect(report.flowEfficiency.n + f.neverStarted + f.zeroCycle).toBe(f.population);
      // Same population, different partitions (no worklog bucket for flow efficiency).
      expect(f.population).toBe(x.population);
      for (const d of [report.ratio, report.flowEfficiency, ...report.groups.flatMap((g) => [g.ratio, g.flowEfficiency])]) {
        expectWellFormed(d);
      }
      for (const g of report.groups) {
        expect(g.ratio.n + g.excluded.noWorklogs + g.excluded.neverStarted + g.excluded.zeroCycle).toBe(g.excluded.population);
        expect(g.flowEfficiency.n + g.flowEfficiencyExcluded.neverStarted + g.flowEfficiencyExcluded.zeroCycle).toBe(
          g.flowEfficiencyExcluded.population,
        );
      }
      if (report.groups.length > 0) {
        expect(report.groups.reduce((s, g) => s + g.ratio.n, 0)).toBe(report.ratio.n);
        expect(report.groups.reduce((s, g) => s + g.flowEfficiency.n, 0)).toBe(report.flowEfficiency.n);
        for (const key of ["population", "noWorklogs", "neverStarted", "zeroCycle"] as const) {
          expect(report.groups.reduce((s, g) => s + g.excluded[key], 0)).toBe(x[key]);
        }
        for (const key of ["population", "neverStarted", "zeroCycle"] as const) {
          expect(report.groups.reduce((s, g) => s + g.flowEfficiencyExcluded[key], 0)).toBe(f[key]);
        }
      }
    }
  });

  test("WIP: every point carries every key of the legend, days ascend, and nothing is listed for a report that never derived", () => {
    for (const report of [WIP_STAGE, WIP_STATUS, WIP_COLUMN, WIP_EPICS, WIP_NOT_DERIVED]) {
      const keys = report.keys.map((k) => k.key).sort();
      for (const point of report.series) expect(Object.keys(point.counts).sort()).toEqual(keys);
      const days = report.series.map((p) => p.day);
      expect(days).toEqual([...days].sort());
    }
    expect(WIP_NOT_DERIVED.series).toEqual([]);
    expect(WIP_NOT_DERIVED.meta.derivedAt).toBeNull();
    expect(WIP_NOT_DERIVED.note).toMatch(/^Not derived yet/);
  });

  test("backlog: current is the trend's last day, and backlog in sprints is md over the mean (null for no or a zero mean)", () => {
    for (const report of [BACKLOG, BACKLOG_PARTIAL_WINDOW, BACKLOG_NO_PACE, BACKLOG_ZERO_PACE]) {
      const last = report.trend.at(-1);
      expect(report.current).toMatchObject({ asOfDay: last?.day, items: last?.items, md: last?.md });
      const { md, meanDeliveredMd, backlogInSprints, sprintsUsed, windowSprints } = report.current;
      expect(sprintsUsed).toBeLessThanOrEqual(windowSprints);
      if (meanDeliveredMd == null || meanDeliveredMd === 0) expect(backlogInSprints).toBeNull();
      else expect(backlogInSprints).toBeCloseTo(md / meanDeliveredMd, 6);
    }
    expect(BACKLOG_NOT_DERIVED.trend).toEqual([]);
    expect(BACKLOG_NOT_DERIVED.current.asOfDay).toBeNull();
  });

  test("aging WIP: oldest first, and every band is the highest threshold of the item's OWN kind its age is above (null ⇔ hidden)", () => {
    for (const report of [AGING_UNIT, AGING_TEAM, AGING_HIDDEN, AGING_TRUNCATED, AGING_EMPTY]) {
      const ages = report.items.map((item) => item.ageWorkingDays);
      expect(ages).toEqual([...ages].sort((a, b) => b - a));
      for (const item of report.items) {
        const thresholds = item.itemKind === "EPIC" ? report.epicThresholds : report.thresholds;
        expect(thresholds.hidden).toBe(thresholds.n < MIN);
        if (thresholds.hidden) {
          expect(item.band ?? null).toBeNull();
          for (const entry of thresholds.percentiles) expect(entry.workingDays ?? null).toBeNull();
        } else {
          const above = [...thresholds.percentiles].sort((a, b) => b.percentile - a.percentile).find((entry) => item.ageWorkingDays > (entry.workingDays ?? Infinity));
          expect(item.band).toBe(above ? `P${above.percentile}` : "WITHIN");
        }
      }
    }
  });

  test("blocked time: n + exclusions == population, groups sum to the whole, hidden ⇔ below the minimum", () => {
    for (const report of [BLOCKED_TIME, BLOCKED_TEAM_BOTH, BLOCKED_NONE, BLOCKED_EMPTY]) {
      const x = report.excluded;
      // Every finished item is in the days distribution (zeros included); the share leaves out items without a cycle.
      expect(report.blockedWorkingDays.n).toBe(x.population);
      expect(report.shareOfCycle.n + x.neverStarted + x.zeroCycle).toBe(x.population);
      for (const d of [report.blockedWorkingDays, report.shareOfCycle, ...report.groups.flatMap((g) => [g.blockedWorkingDays, g.shareOfCycle])]) {
        expectWellFormed(d);
      }
      if (report.groups.length > 0) {
        for (const g of report.groups) {
          expect(g.blockedWorkingDays.n).toBe(g.excluded.population);
          expect(g.shareOfCycle.n + g.excluded.neverStarted + g.excluded.zeroCycle).toBe(g.excluded.population);
        }
        // TEAM level with epics: the groups (per assignee) cover the tasks only, so they do not add up to the totals.
        if (report.itemKind === "TASK") {
          expect(report.groups.reduce((sum, g) => sum + g.excluded.population, 0)).toBe(x.population);
          expect(report.groups.reduce((sum, g) => sum + g.shareOfCycle.n, 0)).toBe(report.shareOfCycle.n);
          expect(report.groups.reduce((sum, g) => sum + g.blockedItems, 0)).toBe(report.blockedItems);
        }
      }
      expect(report.blockedItems).toBeLessThanOrEqual(x.population);
      expect(report.topItems.length).toBeLessThanOrEqual(20);
    }
  });
  test("data quality: findings add up, lists respect the cap, and at UNIT level the groups add up to the headline counts", () => {
    const all = [DATA_QUALITY, DATA_QUALITY_CLEAN, DATA_QUALITY_CAPPED, DATA_QUALITY_TEAM, DATA_QUALITY_USER];
    for (const report of all) {
      const findings = [
        report.worklogCoverage.without,
        report.missing.noEstimate,
        report.missing.noEpic,
        report.missing.noWorkCategory,
        report.missing.unassigned,
        report.missing.estimatedLate,
        report.missing.estimatedAfterDone,
        report.outsideSprint,
        report.crossDomain,
      ];
      for (const finding of findings) {
        expect(finding.total).toBe(finding.done + finding.open);
        expect(finding.items.length).toBeLessThanOrEqual(Math.min(50, finding.total));
      }
      const lists = [
        report.missing.epicsWithoutEstimate,
        report.missing.epicsWithoutDates,
        report.missing.epicsOutsidePvHorizon,
        report.epicDrift,
        report.domainsWithoutOwner,
        report.unmappedStatuses,
        report.unmappedBoards,
        report.authorsWithoutTeam,
        report.itemsAboveEpic,
        report.snapshotDrift,
      ];
      for (const list of lists) expect(list.items.length).toBeLessThanOrEqual(Math.min(50, list.total));
      expect(report.worklogCoverage.without.done).toBe(report.worklogCoverage.doneTasks - report.worklogCoverage.withWorklogs);
      expectWellFormed(report.lateLogging.distribution);
    }
    // A hidden lateness distribution is the one below the minimum sample.
    expect(DATA_QUALITY_TEAM.lateLogging.distribution.hidden).toBe(true);
    // (the capped fixture changes a list without its groups, so it is not a UNIT read of its own)
    for (const report of [DATA_QUALITY]) {
      const sum = (pick: (g: (typeof report.groups)[number]) => number) => report.groups.reduce((total, g) => total + pick(g), 0);
      expect(sum((g) => g.tasks.done)).toBe(report.populations.doneTasks);
      expect(sum((g) => g.tasks.openStarted)).toBe(report.populations.openStartedTasks);
      expect(sum((g) => g.tasks.withoutWorklogs)).toBe(report.worklogCoverage.without.total);
      expect(sum((g) => g.tasks.noEstimate.done)).toBe(report.missing.noEstimate.done);
      expect(sum((g) => g.tasks.noEpic.done)).toBe(report.missing.noEpic.done);
      expect(sum((g) => g.tasks.noEpic.open)).toBe(report.missing.noEpic.open);
      expect(sum((g) => g.tasks.noWorkCategory.done + g.tasks.noWorkCategory.open)).toBe(report.missing.noWorkCategory.total);
      expect(sum((g) => g.tasks.unassigned)).toBe(report.missing.unassigned.total);
      expect(sum((g) => g.tasks.estimatedLate.done + g.tasks.estimatedLate.open)).toBe(report.missing.estimatedLate.total);
      expect(sum((g) => g.tasks.estimatedAfterDone)).toBe(report.missing.estimatedAfterDone.done);
      expect(report.missing.estimatedAfterDone.open).toBe(0);
      expect(sum((g) => g.tasks.outsideSprint)).toBe(report.outsideSprint.total);
      expect(sum((g) => g.worklogs.worklogs)).toBe(report.populations.worklogs);
      expect(sum((g) => g.worklogs.over1Day)).toBe(report.lateLogging.over1Day);
      expect(sum((g) => g.worklogs.over7Days)).toBe(report.lateLogging.over7Days);
      expect(sum((g) => g.epics?.epics ?? 0)).toBe(report.populations.epics);
      expect(sum((g) => g.epics?.withoutEstimate ?? 0)).toBe(report.missing.epicsWithoutEstimate.total);
      expect(sum((g) => g.epics?.withoutDates ?? 0)).toBe(report.missing.epicsWithoutDates.total);
      expect(sum((g) => g.epics?.outsidePvHorizon ?? 0)).toBe(report.missing.epicsOutsidePvHorizon.total);
      expect(sum((g) => g.epics?.drifting ?? 0)).toBe(report.epicDrift.total);
    }
    // A member's groups carry no epic counts; a member's own read has no groups at all.
    expect(DATA_QUALITY_TEAM.groups.every((g) => g.epics === null)).toBe(true);
    expect(DATA_QUALITY_USER.groups).toEqual([]);
    // The capped fixture really is capped: 50 rows of 75.
    expect(DATA_QUALITY_CAPPED.missing.noEpic.items).toHaveLength(50);
    expect(DATA_QUALITY_CAPPED.missing.noEpic.total).toBe(75);
  });
});

describe("cost matrix fixtures", () => {
  const ALL = [
    COST_MATRIX_UNIT,
    COST_MATRIX_TASK_VIEW,
    COST_MATRIX_SPRINTS,
    COST_MATRIX_THIRDS,
    COST_MATRIX_TEAM,
    COST_MATRIX_USER,
    COST_MATRIX_NO_SHARE,
    COST_MATRIX_EMPTY,
  ];

  test("cells are dense and in column order; every total is within the rounding bound of the sum it stands for", () => {
    for (const report of ALL) {
      const domains = report.columns.map((column) => column.domain);
      for (const row of report.rows) {
        expect(row.cells.map((cell) => cell.domain)).toEqual(domains);
        // Each figure is its own exact sum rounded once: 0.005 per addend at most.
        expect(Math.abs(row.cells.reduce((sum, cell) => sum + cell.md, 0) - row.totalMd)).toBeLessThanOrEqual(0.005 * row.cells.length + 1e-9);
      }
      report.columns.forEach((column, i) => {
        const sum = report.rows.reduce((total, row) => total + row.cells[i].md, 0);
        expect(Math.abs(sum - column.totalMd)).toBeLessThanOrEqual(0.005 * report.rows.length + 1e-9);
      });
      expect(Math.abs(report.rows.reduce((sum, row) => sum + row.totalMd, 0) - report.totalMd)).toBeLessThanOrEqual(0.005 * report.rows.length + 1e-9);
      expect(report.rows.reduce((sum, row) => sum + row.foreignMd, 0)).toBeCloseTo(report.foreignMd, 6);
    }
  });

  test("a foreign share is the foreign MD over the MD, null exactly when nothing was logged", () => {
    for (const report of ALL) {
      for (const figures of [report, ...report.rows]) {
        if (figures.totalMd === 0) expect(figures.foreignShare).toBeNull();
        else expect(figures.foreignShare).toBeCloseTo(figures.foreignMd / figures.totalMd, 6);
        expect(figures.foreignMd).toBeLessThanOrEqual(figures.totalMd);
      }
    }
  });

  test("unit rows carry `active` (unassigned last, team 0), team and user rows leave it out; the views differ only in the columns", () => {
    expect(COST_MATRIX_UNIT.rows.map((row) => row.teamId)).toEqual([1, 4, 0]);
    expect(COST_MATRIX_UNIT.rows.every((row) => row.active !== undefined)).toBe(true);
    expect(COST_MATRIX_UNIT.rows[1].active).toBe(false);
    for (const report of [COST_MATRIX_TEAM, COST_MATRIX_USER]) expect(report.rows.every((row) => row.active === undefined)).toBe(true);
    expect(COST_MATRIX_TASK_VIEW.totalMd).toBe(COST_MATRIX_UNIT.totalMd);
    expect(COST_MATRIX_TASK_VIEW.rows.map((row) => row.totalMd)).toEqual(COST_MATRIX_UNIT.rows.map((row) => row.totalMd));
    // The thirds fixture IS the rounding rule: three 0.33 cells, a total of 1.
    expect(COST_MATRIX_THIRDS.rows[0].cells.every((cell) => Math.abs(cell.md - 0.33) < 1e-9)).toBe(true);
    expect(COST_MATRIX_THIRDS.rows[0].totalMd).toBe(1);
  });
});
