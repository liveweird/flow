// Shared report test fixtures: the reference data every report's filter bar reads, and two
// velocity responses (UNIT and TEAM level). Typed against the generated schema so a spec drift
// breaks the fixtures, not just the page.
import type {
  AdjustmentFigures,
  AgingWipReport,
  BacklogReport,
  BlockedTimeReport,
  Distribution,
  EpicAccuracyRow,
  EpicEstimationAccuracyReport,
  EstimateAdjustmentsReport,
  CycleTimeReport,
  EpicProgressReport,
  ReportedTimeRatioReport,
  ReportFilters,
  SprintConsistencyReport,
  TaskEstimationAccuracyReport,
  ThroughputReport,
  VelocityReport,
  WipReport,
} from "../api/reports";

export const FILTERS: ReportFilters = {
  teams: [
    {
      id: 1,
      name: "Alpha",
      sprints: [
        { sprintId: 11, name: "Alpha 1", state: "closed", startAt: 1_000, completeAt: 2_000 },
        { sprintId: 12, name: "Alpha 2", state: "closed", startAt: 3_000, completeAt: 4_000 },
        { sprintId: 13, name: "Alpha 3", state: "active", startAt: 5_000, completeAt: null },
      ],
      members: [
        { accountId: "acc-ann", displayName: "Ann Author" },
        { accountId: "acc-bob", displayName: "Bob Builder" },
      ],
    },
    {
      id: 2,
      name: "Beta",
      sprints: [{ sprintId: 21, name: "Beta 1", state: "closed", startAt: 1_500, completeAt: 2_500 }],
      members: [{ accountId: "acc-cy", displayName: "Cy Coder" }],
    },
    {
      id: 3,
      name: "Gamma",
      sprints: [{ sprintId: 31, name: "Gamma 1", state: "active", startAt: 6_000, completeAt: null }],
      members: [],
    },
  ],
  domains: [{ domainKey: "FLO", domainName: "Flow" }],
  activityTypes: ["Bug", "Story"],
  workCategories: ["Maintenance"],
  connections: [{ id: 1, name: "Stub" }],
  derivedAt: 1_780_000_000_000,
  configRevision: 4,
  minSampleSize: 5,
  timeZone: "Europe/Warsaw",
};

const META: VelocityReport["meta"] = {
  derivedAt: 1_780_000_000_000,
  configRevision: 4,
  from: "2026-07-02",
  to: "2026-09-29",
  level: "UNIT",
  domainView: "TASK",
  resolvedSprints: [],
  minSampleSize: 5,
};

export const VELOCITY_UNIT: VelocityReport = {
  meta: META,
  sprints: [
    {
      sprintId: 12,
      name: "Alpha 2",
      teamId: 1,
      completedAt: Date.UTC(2026, 8, 10),
      initialMd: 20,
      initialItems: 8,
      finalMd: 24.5,
      finalItems: 9,
      snapshot: { initialMd: 20, initialItems: 8, finalMd: 22, finalItems: 9 },
      drift: true,
    },
    {
      sprintId: 21,
      name: "Beta 1",
      teamId: 2,
      completedAt: Date.UTC(2026, 8, 1),
      initialMd: 10,
      initialItems: 4,
      finalMd: 10,
      finalItems: 4,
      snapshot: { initialMd: 10, initialItems: 4, finalMd: 10, finalItems: 4 },
      drift: false,
    },
  ],
  groups: [
    { teamId: 1, label: "Alpha", initialMd: 20, initialItems: 8, finalMd: 24.5, finalItems: 9 },
    { teamId: 2, label: "Beta", initialMd: 10, initialItems: 4, finalMd: 10, finalItems: 4 },
  ],
};

export const VELOCITY_TEAM: VelocityReport = {
  meta: { ...META, level: "TEAM" },
  sprints: [VELOCITY_UNIT.sprints[0]],
  groups: [
    { accountId: "acc-ann", label: "Ann Author", initialMd: 12, initialItems: 5, finalMd: 14.5, finalItems: 6 },
    { accountId: null, label: null, initialMd: 8, initialItems: 3, finalMd: 10, finalItems: 3 },
  ],
};

export const VELOCITY_EMPTY: VelocityReport = { meta: META, sprints: [], groups: [] };

export const THROUGHPUT_UNIT: ThroughputReport = {
  meta: META,
  bySprint: [
    {
      sprintId: 12,
      name: "Alpha 2",
      teamId: 1,
      completedAt: Date.UTC(2026, 8, 10),
      deliveredMd: 18,
      deliveredItems: 7,
      snapshot: { deliveredMd: 16, deliveredItems: 6 },
      drift: true,
    },
    {
      sprintId: 21,
      name: "Beta 1",
      teamId: 2,
      completedAt: Date.UTC(2026, 8, 1),
      deliveredMd: 10,
      deliveredItems: 4,
      snapshot: { deliveredMd: 10, deliveredItems: 4 },
      drift: false,
    },
  ],
  byBucket: [
    { bucketStart: "2026-09-07", deliveredMd: 12.5, deliveredItems: 5 },
    { bucketStart: "2026-09-14", deliveredMd: 0, deliveredItems: 0 },
    { bucketStart: "2026-09-21", deliveredMd: 15.5, deliveredItems: 6 },
  ],
  groups: [
    { teamId: 1, accountId: null, label: "Alpha", deliveredMd: 18, deliveredItems: 7 },
    { teamId: 2, accountId: null, label: "Beta", deliveredMd: 10, deliveredItems: 4 },
    { teamId: null, accountId: null, label: null, deliveredMd: 0, deliveredItems: 0 },
  ],
};

export const THROUGHPUT_MONTHS: ThroughputReport = {
  ...THROUGHPUT_UNIT,
  byBucket: [
    { bucketStart: "2026-08-01", deliveredMd: 20, deliveredItems: 8 },
    { bucketStart: "2026-09-01", deliveredMd: 8, deliveredItems: 3 },
  ],
};

export const THROUGHPUT_NOTHING: ThroughputReport = {
  meta: META,
  bySprint: [],
  byBucket: [
    { bucketStart: "2026-09-07", deliveredMd: 0, deliveredItems: 0 },
    { bucketStart: "2026-09-14", deliveredMd: 0, deliveredItems: 0 },
  ],
  groups: [],
};

// Both sprints obey the A17 partition (checked by reportFixtures.test.ts): final = delivered +
// carried over + dropped, and final = committed + added (items always, MD when nothing was
// re-estimated). Removed scope sits beside them, in no other bucket.
const ALPHA_LIVE = {
  committedMd: 20,
  committedItems: 8,
  addedMd: 4.5,
  addedItems: 2,
  removedMd: 1,
  removedItems: 1,
  finalMd: 24.5,
  finalItems: 10,
  deliveredMd: 18,
  deliveredItems: 7,
  carriedOverMd: 4,
  carriedOverItems: 2,
  droppedMd: 2.5,
  droppedItems: 1,
};

const ALPHA_FROZEN = {
  committedMd: 20,
  committedItems: 8,
  addedMd: 2,
  addedItems: 2,
  removedMd: 1,
  removedItems: 1,
  finalMd: 22,
  finalItems: 10,
  deliveredMd: 16.5,
  deliveredItems: 7,
  carriedOverMd: 3,
  carriedOverItems: 2,
  droppedMd: 2.5,
  droppedItems: 1,
};

const BETA_LIVE = {
  committedMd: 10,
  committedItems: 4,
  addedMd: 0,
  addedItems: 0,
  removedMd: 0,
  removedItems: 0,
  finalMd: 10,
  finalItems: 4,
  deliveredMd: 10,
  deliveredItems: 4,
  carriedOverMd: 0,
  carriedOverItems: 0,
  droppedMd: 0,
  droppedItems: 0,
};

export const CONSISTENCY_UNIT: SprintConsistencyReport = {
  meta: META,
  sprints: [
    { sprintId: 12, name: "Alpha 2", teamId: 1, completedAt: Date.UTC(2026, 8, 10), ...ALPHA_LIVE, snapshot: ALPHA_FROZEN, drift: true },
    { sprintId: 21, name: "Beta 1", teamId: 2, completedAt: Date.UTC(2026, 8, 1), ...BETA_LIVE, snapshot: null, drift: false },
  ],
  // UNIT level: one group per team, summing that team's sprints — here one sprint each.
  groups: [
    { teamId: 1, label: "Alpha", ...ALPHA_LIVE },
    { teamId: 2, label: "Beta", ...BETA_LIVE },
  ],
};

export const CONSISTENCY_EMPTY: SprintConsistencyReport = { meta: META, sprints: [], groups: [] };

// ---- Estimation (reports 3, 4, 5) -------------------------------------------------------------
// Every fixture here obeys the documented partitions (checked by reportFixtures.test.ts):
//   n + Σ exclusion buckets == population, per view; Σ group n == n; hidden ⇔ n < minSampleSize.

/** A distribution below the minimum sample: counts only. */
const hiddenDistribution = (n: number): Distribution => ({ n, hidden: true, histogram: [] });

/** A visible distribution over equal-width buckets starting at `lo`; `n` is the bucket total. */
const shownDistribution = (
  counts: number[],
  lo: number,
  step: number,
  stats: { p50: number; p90: number; p95: number; mean: number },
): Distribution => ({
  n: counts.reduce((sum, count) => sum + count, 0),
  hidden: false,
  ...stats,
  min: lo,
  max: lo + step * counts.length,
  histogram: counts.map((count, i) => ({ from: lo + i * step, to: lo + (i + 1) * step, count })),
});

export const TASK_ACCURACY: TaskEstimationAccuracyReport = {
  meta: META,
  atStart: shownDistribution([1, 3, 5, 2, 1], 0.5, 0.5, { p50: 1.1, p90: 1.9, p95: 2.2, mean: 1.25 }),
  atDone: shownDistribution([0, 4, 6, 3, 1], 0.5, 0.5, { p50: 1.05, p90: 1.7, p95: 2, mean: 1.15 }),
  excluded: { population: 20, noWorklogs: 3, neverStarted: 2, unestimatedAtStart: 3, unestimatedAtDone: 3 },
  groups: [
    {
      teamId: 1,
      accountId: null,
      label: "Alpha",
      atStart: shownDistribution([1, 2, 3, 1, 1], 0.5, 0.5, { p50: 1.1, p90: 1.8, p95: 2, mean: 1.2 }),
      atDone: shownDistribution([0, 3, 4, 1, 1], 0.5, 0.5, { p50: 1.0, p90: 1.6, p95: 1.9, mean: 1.1 }),
      excluded: { population: 12, noWorklogs: 2, neverStarted: 1, unestimatedAtStart: 1, unestimatedAtDone: 1 },
    },
    {
      teamId: 2,
      accountId: null,
      label: "Beta",
      atStart: hiddenDistribution(4),
      atDone: shownDistribution([0, 1, 2, 1, 1], 0.5, 0.5, { p50: 1.3, p90: 2, p95: 2.2, mean: 1.4 }),
      excluded: { population: 8, noWorklogs: 1, neverStarted: 1, unestimatedAtStart: 2, unestimatedAtDone: 2 },
    },
  ],
};

export const TASK_ACCURACY_HIDDEN: TaskEstimationAccuracyReport = {
  meta: META,
  atStart: hiddenDistribution(3),
  atDone: hiddenDistribution(4),
  excluded: { population: 9, noWorklogs: 1, neverStarted: 1, unestimatedAtStart: 4, unestimatedAtDone: 4 },
  groups: [],
};

export const TASK_ACCURACY_EMPTY: TaskEstimationAccuracyReport = {
  meta: META,
  atStart: hiddenDistribution(0),
  atDone: hiddenDistribution(0),
  excluded: { population: 0, noWorklogs: 0, neverStarted: 0, unestimatedAtStart: 0, unestimatedAtDone: 0 },
  groups: [],
};

/** Ten DONE epics whose per-row ratios agree with the distributions' n (6 at start, 8 at done). */
function epicRows(): EpicAccuracyRow[] {
  const base = { summary: null, ownerTeamId: 1, childSumMd: 12 };
  const row = (i: number, own: Partial<EpicAccuracyRow>): EpicAccuracyRow => ({
    ...base,
    issueKey: `FLO-${100 + i}`,
    summary: i === 5 ? "Checkout revamp" : null,
    doneAt: Date.UTC(2026, 8, 20 - i),
    ownEstimateAtStartMd: 20,
    ownEstimateAtDoneMd: 22,
    actualMd: 24,
    ratio: 1.2,
    ratioAtDone: 1.09,
    ...own,
  });
  return [
    row(0, { actualMd: 0, ratio: null, ratioAtDone: null }), // noActual
    row(1, { ownEstimateAtStartMd: null, ratio: null }), // neverStarted
    row(2, { ownEstimateAtStartMd: null, ratio: null }), // unestimatedAtStart
    row(3, { ownEstimateAtStartMd: null, ratio: null }), // unestimatedAtStart
    row(4, { ownEstimateAtDoneMd: null, ratioAtDone: null }), // unestimatedAtDone
    row(5, {}),
    row(6, {}),
    row(7, {}),
    row(8, {}),
    row(9, {}),
  ];
}

export const EPIC_ACCURACY: EpicEstimationAccuracyReport = {
  meta: { ...META, domainView: "EPIC" },
  atStart: shownDistribution([0, 2, 3, 1], 0.5, 0.5, { p50: 1.2, p90: 1.8, p95: 1.9, mean: 1.25 }),
  atDone: shownDistribution([1, 3, 3, 1], 0.5, 0.5, { p50: 1.1, p90: 1.7, p95: 1.8, mean: 1.15 }),
  excluded: { population: 10, noActual: 1, neverStarted: 1, unestimatedAtStart: 2, unestimatedAtDone: 1 },
  epics: epicRows(),
  epicsTruncated: false,
  groups: [
    {
      teamId: 1,
      label: "Alpha",
      atStart: shownDistribution([0, 1, 3, 1], 0.5, 0.5, { p50: 1.2, p90: 1.7, p95: 1.8, mean: 1.2 }),
      atDone: shownDistribution([1, 1, 2, 1], 0.5, 0.5, { p50: 1.1, p90: 1.7, p95: 1.8, mean: 1.1 }),
      excluded: { population: 7, noActual: 1, neverStarted: 0, unestimatedAtStart: 1, unestimatedAtDone: 1 },
    },
    {
      teamId: null,
      label: null,
      atStart: hiddenDistribution(1),
      atDone: hiddenDistribution(3),
      excluded: { population: 3, noActual: 0, neverStarted: 1, unestimatedAtStart: 1, unestimatedAtDone: 0 },
    },
  ],
};

export const EPIC_ACCURACY_TRUNCATED: EpicEstimationAccuracyReport = { ...EPIC_ACCURACY, epicsTruncated: true };

export const EPIC_ACCURACY_EMPTY: EpicEstimationAccuracyReport = {
  meta: META,
  atStart: hiddenDistribution(0),
  atDone: hiddenDistribution(0),
  excluded: { population: 0, noActual: 0, neverStarted: 0, unestimatedAtStart: 0, unestimatedAtDone: 0 },
  epics: [],
  epicsTruncated: false,
  groups: [],
};

const noAdjustments = (): AdjustmentFigures => ({
  started: 0,
  changedAfterStart: 0,
  share: null,
  estimatedLate: 0,
  changeDistribution: hiddenDistribution(0),
  changeExcluded: { population: 0, estimatedLate: 0, unestimated: 0 },
});

export const ADJUSTMENTS: EstimateAdjustmentsReport = {
  meta: META,
  tasks: {
    started: 30,
    changedAfterStart: 6,
    share: 0.2,
    estimatedLate: 2,
    changeDistribution: shownDistribution([1, 4, 3, 2], -0.25, 0.25, { p50: 0.1, p90: 0.6, p95: 0.7, mean: 0.15 }),
    changeExcluded: { population: 15, estimatedLate: 2, unestimated: 3 },
  },
  epics: {
    started: 3,
    changedAfterStart: 1,
    share: null,
    estimatedLate: 0,
    changeDistribution: hiddenDistribution(2),
    changeExcluded: { population: 4, estimatedLate: 0, unestimated: 2 },
  },
  groups: [
    {
      teamId: 1,
      accountId: null,
      label: "Alpha",
      tasks: {
        started: 20,
        changedAfterStart: 5,
        share: 0.25,
        estimatedLate: 1,
        changeDistribution: shownDistribution([1, 3, 2, 1], -0.25, 0.25, { p50: 0.1, p90: 0.5, p95: 0.7, mean: 0.15 }),
        changeExcluded: { population: 10, estimatedLate: 1, unestimated: 2 },
      },
      epics: {
        started: 3,
        changedAfterStart: 1,
        share: null,
        estimatedLate: 0,
        changeDistribution: hiddenDistribution(2),
        changeExcluded: { population: 4, estimatedLate: 0, unestimated: 2 },
      },
    },
    {
      teamId: 2,
      accountId: null,
      label: "Beta",
      tasks: {
        started: 10,
        changedAfterStart: 1,
        share: 0.1,
        estimatedLate: 1,
        changeDistribution: hiddenDistribution(3),
        changeExcluded: { population: 5, estimatedLate: 1, unestimated: 1 },
      },
      epics: noAdjustments(),
    },
  ],
};

export const ADJUSTMENTS_EMPTY: EstimateAdjustmentsReport = {
  meta: META,
  tasks: noAdjustments(),
  epics: noAdjustments(),
  groups: [],
};

// ---- Cycle time and reported time (reports 7, 8) ------------------------------------------------
// Partitions (checked by reportFixtures.test.ts): cycle time — workingDays.n == elapsedDays.n ==
// population − neverStarted, trend n's sum to it, a bucket's p50/p90 are null exactly when its n is
// below the minimum; reported time — ratio.n + noWorklogs + neverStarted + zeroCycle == population and
// flowEfficiency.n + neverStarted + zeroCycle == population.

export const CYCLE_TIME: CycleTimeReport = {
  meta: META,
  workingDays: shownDistribution([3, 6, 5, 3, 1], 0, 2, { p50: 3.5, p90: 7.5, p95: 9, mean: 4.1 }),
  elapsedDays: shownDistribution([2, 5, 6, 3, 2], 0, 3, { p50: 5, p90: 11, p95: 13, mean: 6 }),
  excluded: { population: 20, neverStarted: 2 },
  trend: [
    { bucketStart: "2026-09-07", p50: 3, p90: 7.5, n: 8 },
    { bucketStart: "2026-09-14", p50: null, p90: null, n: 3 },
    { bucketStart: "2026-09-21", p50: 2.5, p90: 6, n: 7 },
    { bucketStart: "2026-09-28", p50: null, p90: null, n: 0 },
  ],
  groups: [
    {
      teamId: 1,
      accountId: null,
      label: "Alpha",
      workingDays: shownDistribution([2, 3, 2, 1, 1], 0, 2, { p50: 3.2, p90: 7, p95: 8, mean: 3.9 }),
      elapsedDays: shownDistribution([1, 3, 3, 1, 1], 0, 3, { p50: 5, p90: 10, p95: 12, mean: 5.8 }),
      excluded: { population: 10, neverStarted: 1 },
    },
    {
      teamId: 2,
      accountId: null,
      label: "Beta",
      workingDays: shownDistribution([1, 3, 2, 1, 0], 0, 2, { p50: 3.8, p90: 6.5, p95: 7, mean: 4 }),
      elapsedDays: shownDistribution([1, 2, 3, 1, 0], 0, 3, { p50: 5.5, p90: 9, p95: 10, mean: 5.6 }),
      excluded: { population: 7, neverStarted: 0 },
    },
    {
      teamId: null,
      accountId: null,
      label: null,
      workingDays: hiddenDistribution(2),
      elapsedDays: hiddenDistribution(2),
      excluded: { population: 3, neverStarted: 1 },
    },
  ],
};

/** Every period below the minimum sample: a trend with no points at all. */
export const CYCLE_TIME_ALL_HIDDEN: CycleTimeReport = {
  ...CYCLE_TIME,
  trend: CYCLE_TIME.trend.map((b) => ({ ...b, p50: null, p90: null })),
};

/** The month variant: same tasks, coarser buckets (n's still sum to the measured tasks). */
export const CYCLE_TIME_MONTHS: CycleTimeReport = {
  ...CYCLE_TIME,
  trend: [
    { bucketStart: "2026-08-01", p50: 4, p90: 8, n: 11 },
    { bucketStart: "2026-09-01", p50: 3, p90: 7, n: 7 },
  ],
};

export const CYCLE_TIME_EMPTY: CycleTimeReport = {
  meta: META,
  workingDays: hiddenDistribution(0),
  elapsedDays: hiddenDistribution(0),
  excluded: { population: 0, neverStarted: 0 },
  trend: [{ bucketStart: "2026-09-07", p50: null, p90: null, n: 0 }],
  groups: [],
};

export const REPORTED_TIME: ReportedTimeRatioReport = {
  meta: META,
  ratio: shownDistribution([6, 4, 2, 1, 1], 0, 0.5, { p50: 0.6, p90: 1.8, p95: 2.2, mean: 0.9 }),
  excluded: { population: 20, noWorklogs: 3, neverStarted: 2, zeroCycle: 1 },
  flowEfficiency: shownDistribution([4, 6, 4, 2, 1], 0, 0.2, { p50: 0.4, p90: 0.8, p95: 0.9, mean: 0.45 }),
  flowEfficiencyExcluded: { population: 20, neverStarted: 2, zeroCycle: 1 },
  groups: [
    {
      teamId: 1,
      accountId: null,
      label: "Alpha",
      ratio: shownDistribution([3, 3, 2, 1, 1], 0, 0.5, { p50: 0.7, p90: 1.9, p95: 2.2, mean: 1 }),
      excluded: { population: 12, noWorklogs: 0, neverStarted: 1, zeroCycle: 1 },
      flowEfficiency: shownDistribution([2, 4, 2, 1, 1], 0, 0.2, { p50: 0.4, p90: 0.8, p95: 0.9, mean: 0.45 }),
      flowEfficiencyExcluded: { population: 12, neverStarted: 1, zeroCycle: 1 },
    },
    {
      teamId: 2,
      accountId: null,
      label: "Beta",
      ratio: hiddenDistribution(4),
      excluded: { population: 8, noWorklogs: 3, neverStarted: 1, zeroCycle: 0 },
      flowEfficiency: shownDistribution([2, 2, 2, 1, 0], 0, 0.2, { p50: 0.35, p90: 0.7, p95: 0.8, mean: 0.4 }),
      flowEfficiencyExcluded: { population: 8, neverStarted: 1, zeroCycle: 0 },
    },
  ],
};

export const REPORTED_TIME_HIDDEN: ReportedTimeRatioReport = {
  meta: META,
  ratio: hiddenDistribution(3),
  excluded: { population: 9, noWorklogs: 4, neverStarted: 1, zeroCycle: 1 },
  flowEfficiency: hiddenDistribution(4),
  flowEfficiencyExcluded: { population: 9, neverStarted: 1, zeroCycle: 4 },
  groups: [],
};

export const REPORTED_TIME_EMPTY: ReportedTimeRatioReport = {
  meta: META,
  ratio: hiddenDistribution(0),
  excluded: { population: 0, noWorklogs: 0, neverStarted: 0, zeroCycle: 0 },
  flowEfficiency: hiddenDistribution(0),
  flowEfficiencyExcluded: { population: 0, neverStarted: 0, zeroCycle: 0 },
  groups: [],
};

// ---- WIP (report 9) and the estimated backlog (reports 10, 13) ------------------------------

const NOT_DERIVED = "Not derived yet: no connection in scope has a successful DERIVE run.";

const STAGE_KEYS = ["NOT_STARTED", "IN_PROGRESS", "DONE", "UNMAPPED"].map((key) => ({ key, label: key }));

/** Fri–Tue: the weekend days are not working days; every point carries every key, zero-filled. */
export const WIP_STAGE: WipReport = {
  meta: META,
  by: "STAGE",
  itemKind: "TASK",
  keys: STAGE_KEYS,
  series: [
    { day: "2026-09-25", isWorkingDay: true, counts: { NOT_STARTED: 40, IN_PROGRESS: 6, DONE: 100, UNMAPPED: 1 } },
    { day: "2026-09-26", isWorkingDay: false, counts: { NOT_STARTED: 40, IN_PROGRESS: 6, DONE: 100, UNMAPPED: 1 } },
    { day: "2026-09-27", isWorkingDay: false, counts: { NOT_STARTED: 40, IN_PROGRESS: 6, DONE: 100, UNMAPPED: 1 } },
    { day: "2026-09-28", isWorkingDay: true, counts: { NOT_STARTED: 39, IN_PROGRESS: 8, DONE: 101, UNMAPPED: 1 } },
    { day: "2026-09-29", isWorkingDay: true, counts: { NOT_STARTED: 38, IN_PROGRESS: 9, DONE: 102, UNMAPPED: 1 } },
  ],
  note: null,
};

export const WIP_STATUS: WipReport = {
  meta: META,
  by: "STATUS",
  itemKind: "TASK",
  keys: [
    { key: "10001", label: "To Do" },
    { key: "10002", label: "In Progress" },
    { key: "10003", label: "Done" },
  ],
  series: [
    { day: "2026-09-28", isWorkingDay: true, counts: { "10001": 39, "10002": 8, "10003": 101 } },
    { day: "2026-09-29", isWorkingDay: true, counts: { "10001": 38, "10002": 9, "10003": 102 } },
  ],
  note: null,
};

export const WIP_COLUMN: WipReport = {
  meta: { ...META, level: "TEAM" },
  by: "COLUMN",
  itemKind: "TASK",
  keys: [
    { key: "Backlog", label: "Backlog" },
    { key: "Doing", label: "Doing" },
    { key: "(no column)", label: "(no column)" },
  ],
  series: [{ day: "2026-09-29", isWorkingDay: true, counts: { Backlog: 12, Doing: 4, "(no column)": 2 } }],
  note: null,
};

export const WIP_EPICS: WipReport = { ...WIP_STAGE, itemKind: "EPIC" };
export const WIP_BOTH: WipReport = { ...WIP_STAGE, itemKind: "BOTH" };

/** No connection has derived: an empty series and the server's explanation. */
export const WIP_NOT_DERIVED: WipReport = {
  meta: { ...META, derivedAt: null },
  by: "STAGE",
  itemKind: "TASK",
  keys: STAGE_KEYS,
  series: [],
  note: NOT_DERIVED,
};

const BACKLOG_TREND = [
  { day: "2026-09-27", items: 10, md: 21 },
  { day: "2026-09-28", items: 11, md: 23.5 },
  { day: "2026-09-29", items: 12, md: 25 },
];

/** 25 MD over a mean of 10 MD per sprint (three of three closed sprints) = 2.5 sprints ahead. */
export const BACKLOG: BacklogReport = {
  meta: META,
  current: { asOfDay: "2026-09-29", items: 12, md: 25, meanDeliveredMd: 10, windowSprints: 3, sprintsUsed: 3, backlogInSprints: 2.5 },
  trend: BACKLOG_TREND,
  note: null,
};

/** TEAM level, one closed sprint of the three-sprint window: 25 ÷ 8 = 3.125 sprints. */
export const BACKLOG_PARTIAL_WINDOW: BacklogReport = {
  meta: { ...META, level: "TEAM" },
  current: { asOfDay: "2026-09-29", items: 12, md: 25, meanDeliveredMd: 8, windowSprints: 3, sprintsUsed: 1, backlogInSprints: 3.125 },
  trend: BACKLOG_TREND,
  note: null,
};

/** No closed sprint (or a scope with no velocity of its own): no mean, so no figure in sprints. */
export const BACKLOG_NO_VELOCITY: BacklogReport = {
  meta: { ...META, level: "TEAM" },
  current: { asOfDay: "2026-09-29", items: 12, md: 25, meanDeliveredMd: null, windowSprints: 3, sprintsUsed: 0, backlogInSprints: null },
  trend: BACKLOG_TREND,
  note: null,
};

/** A mean of exactly 0: the mean is there, the ratio is not. */
export const BACKLOG_ZERO_VELOCITY: BacklogReport = {
  meta: { ...META, level: "TEAM" },
  current: { asOfDay: "2026-09-29", items: 12, md: 25, meanDeliveredMd: 0, windowSprints: 3, sprintsUsed: 3, backlogInSprints: null },
  trend: BACKLOG_TREND,
  note: null,
};

export const BACKLOG_NOT_DERIVED: BacklogReport = {
  meta: { ...META, derivedAt: null },
  current: { asOfDay: null, items: 0, md: 0, meanDeliveredMd: null, windowSprints: 3, sprintsUsed: 0, backlogInSprints: null },
  trend: [],
  note: NOT_DERIVED,
};

// ---- Aging WIP (report 11) and blocked time (report 12) -------------------------------------
// Aging: items oldest first, every band consistent with the thresholds of the item's OWN kind
// (`band` null ⇔ that kind's thresholds are hidden). Blocked time obeys the partitions the estimation
// fixtures do: shareOfCycle.n + neverStarted + zeroCycle == population, groups sum to the whole.

const DEFAULT_PERCENTILES = [
  { percentile: 50, workingDays: 8 },
  { percentile: 85, workingDays: 15 },
  { percentile: 95, workingDays: 25 },
];
const HIDDEN_PERCENTILES = [50, 85, 95].map((percentile) => ({ percentile, workingDays: null }));

/** Tasks: window of 40, thresholds shown. Epics: 2 finished epics, hidden — so the epic carries no band. */
export const AGING_UNIT: AgingWipReport = {
  meta: META,
  thresholds: { n: 40, hidden: false, percentiles: DEFAULT_PERCENTILES },
  epicThresholds: { n: 2, hidden: true, percentiles: HIDDEN_PERCENTILES },
  items: [
    { issueKey: "FLO-E1", summary: "Reporting epic", itemKind: "EPIC", teamId: 1, startedAt: Date.UTC(2026, 6, 20), ageWorkingDays: 40, blocked: false, band: null },
    { issueKey: "FLO-1", summary: "Stuck on review", itemKind: "TASK", teamId: 1, assigneeAccountId: "acc-ann", assignee: "Ann Author", startedAt: Date.UTC(2026, 7, 10), ageWorkingDays: 30, blocked: true, band: "P95" },
    { issueKey: "FLO-2", summary: "Slow but moving", itemKind: "TASK", teamId: 2, assigneeAccountId: "acc-cy", assignee: "Cy Coder", startedAt: Date.UTC(2026, 8, 7), ageWorkingDays: 15.5, blocked: false, band: "P85" },
    { issueKey: "FLO-3", summary: null, itemKind: "TASK", teamId: 2, assigneeAccountId: "acc-cy", assignee: "Cy Coder", startedAt: Date.UTC(2026, 8, 15), ageWorkingDays: 9, blocked: false, band: "P50" },
    { issueKey: "FLO-4", summary: "Fresh", itemKind: "TASK", teamId: null, assigneeAccountId: null, assignee: null, startedAt: Date.UTC(2026, 8, 22), ageWorkingDays: 6, blocked: false, band: "WITHIN" },
  ],
  itemsTruncated: false,
};

export const AGING_TEAM: AgingWipReport = { ...AGING_UNIT, meta: { ...META, level: "TEAM" } };

/** A window below the minimum sample: no threshold value, no band anywhere. */
export const AGING_HIDDEN: AgingWipReport = {
  meta: META,
  thresholds: { n: 3, hidden: true, percentiles: HIDDEN_PERCENTILES },
  epicThresholds: { n: 0, hidden: true, percentiles: HIDDEN_PERCENTILES },
  items: AGING_UNIT.items.filter((item) => item.itemKind === "TASK").map((item) => ({ ...item, band: null })),
  itemsTruncated: false,
};

export const AGING_TRUNCATED: AgingWipReport = { ...AGING_UNIT, itemsTruncated: true };

export const AGING_EMPTY: AgingWipReport = { ...AGING_UNIT, items: [] };

export const BLOCKED_TIME: BlockedTimeReport = {
  meta: META,
  itemKind: "TASK",
  blockedWorkingDays: shownDistribution([12, 4, 2, 1, 1], 0, 1, { p50: 0.5, p90: 2.5, p95: 3.5, mean: 0.9 }),
  shareOfCycle: shownDistribution([9, 4, 2, 1, 1], 0, 0.2, { p50: 0.05, p90: 0.5, p95: 0.7, mean: 0.15 }),
  blockedItems: 9,
  excluded: { population: 20, neverStarted: 2, zeroCycle: 1 },
  topItems: [
    { issueKey: "FLO-10", summary: "Waiting on vendor", itemKind: "TASK", teamId: 1, doneAt: Date.UTC(2026, 8, 20), blockedWorkingDays: 6.5, cycleWorkingDays: 10, share: 0.65 },
    { issueKey: "FLO-11", summary: null, itemKind: "TASK", teamId: null, doneAt: Date.UTC(2026, 8, 12), blockedWorkingDays: 4, cycleWorkingDays: 0, share: null },
  ],
  groups: [
    {
      teamId: 1,
      accountId: null,
      label: "Alpha",
      blockedWorkingDays: shownDistribution([7, 3, 1, 1, 0], 0, 1, { p50: 0.4, p90: 2, p95: 3, mean: 0.7 }),
      shareOfCycle: shownDistribution([6, 2, 1, 1, 0], 0, 0.2, { p50: 0.04, p90: 0.4, p95: 0.6, mean: 0.12 }),
      blockedItems: 5,
      excluded: { population: 12, neverStarted: 1, zeroCycle: 1 },
    },
    {
      teamId: 2,
      accountId: null,
      label: "Beta",
      blockedWorkingDays: shownDistribution([3, 1, 1, 0, 0], 0, 1, { p50: 0.6, p90: 2, p95: 2.5, mean: 0.8 }),
      shareOfCycle: hiddenDistribution(4),
      blockedItems: 2,
      excluded: { population: 5, neverStarted: 1, zeroCycle: 0 },
    },
    {
      teamId: null,
      accountId: null,
      label: null,
      blockedWorkingDays: hiddenDistribution(3),
      shareOfCycle: hiddenDistribution(3),
      blockedItems: 2,
      excluded: { population: 3, neverStarted: 0, zeroCycle: 0 },
    },
  ],
};

/** TEAM level, epics included: the groups (per assignee) cover the tasks only. */
export const BLOCKED_TEAM_BOTH: BlockedTimeReport = {
  ...BLOCKED_TIME,
  meta: { ...META, level: "TEAM" },
  itemKind: "BOTH",
  topItems: [
    ...BLOCKED_TIME.topItems,
    { issueKey: "FLO-E2", summary: "Platform epic", itemKind: "EPIC", teamId: 1, doneAt: Date.UTC(2026, 8, 2), blockedWorkingDays: 3, cycleWorkingDays: 30, share: 0.1 },
  ],
  groups: BLOCKED_TIME.groups.slice(0, 2).map((group, i) => ({
    ...group,
    teamId: null,
    accountId: i === 0 ? "acc-ann" : "acc-bob",
    label: i === 0 ? "Ann Author" : "Bob Builder",
  })),
};

/** Nothing was ever blocked: every item is a real zero, the top list is empty. */
export const BLOCKED_NONE: BlockedTimeReport = {
  ...BLOCKED_TIME,
  blockedWorkingDays: shownDistribution([20, 0, 0, 0, 0], 0, 1, { p50: 0, p90: 0, p95: 0, mean: 0 }),
  blockedItems: 0,
  topItems: [],
  groups: [],
};

export const BLOCKED_EMPTY: BlockedTimeReport = {
  meta: META,
  itemKind: "TASK",
  blockedWorkingDays: hiddenDistribution(0),
  shareOfCycle: hiddenDistribution(0),
  blockedItems: 0,
  excluded: { population: 0, neverStarted: 0, zeroCycle: 0 },
  topItems: [],
  groups: [],
};

// ---- Epic progress / EVM (report 15) -------------------------------------------------------
// Consistent by construction: sv = ev − pv, cv = ev − ac, spi = ev ÷ pv, cpi = ev ÷ ac (null when the
// denominator is 0); a series ends at the as-of figures; the UNIT domain rows add up to the headline.

const EVM_POINTS = [
  { date: "2026-09-27", pv: 8, ev: 4, ac: 6 },
  { date: "2026-09-28", pv: 10, ev: 6, ac: 8 },
  { date: "2026-09-29", pv: 12, ev: 9, ac: 10 },
];
const EVM_AS_OF = { day: "2026-09-29", pv: 12, ev: 9, ac: 10, sv: -3, spi: 0.75, cv: -1, cpi: 0.9 };

/** The whole unit: the domains (epic basis), then the teams (sprint basis — one soft-deleted, one UNASSIGNED). */
export const EPIC_PROGRESS_UNIT: EpicProgressReport = {
  meta: { ...META, domainView: "EPIC" },
  level: "UNIT",
  scope: null,
  series: [],
  asOf: EVM_AS_OF,
  epic: null,
  foreignWorkShare: null,
  rows: [
    { kind: "DOMAIN", id: null, key: "FLO", name: "Flow", pv: 12, ev: 9, ac: 10, sv: -3, spi: 0.75, cv: -1, cpi: 0.9 },
    { kind: "TEAM", id: 1, key: null, name: "Alpha", active: true, pv: 20, ev: 15, ac: 18, sv: -5, spi: 0.75, cv: -3, cpi: 15 / 18 },
    { kind: "TEAM", id: 4, key: null, name: "Old team", active: false, pv: 5, ev: 5, ac: 4, sv: 0, spi: 1, cv: 1, cpi: 1.25 },
    { kind: "TEAM", id: 0, key: null, name: "Unassigned", active: true, pv: 0, ev: 0, ac: 3, sv: 0, spi: null, cv: -3, cpi: 0 },
  ],
  note: null,
};

export const EPIC_PROGRESS_DOMAIN: EpicProgressReport = {
  meta: { ...META, domainView: "EPIC" },
  level: "DOMAIN",
  scope: { kind: "DOMAIN", id: null, key: "FLO", name: "Flow" },
  series: EVM_POINTS.map((point) => ({ ...point, pvOriginal: null })),
  asOf: EVM_AS_OF,
  epic: null,
  foreignWorkShare: null,
  rows: [
    { kind: "EPIC", id: null, key: "FLO-33", name: "Reporting epic", pv: 8, ev: 6, ac: 7, sv: -2, spi: 0.75, cv: -1, cpi: 6 / 7 },
    { kind: "EPIC", id: null, key: "FLO-40", name: "FLO-40", pv: 4, ev: 3, ac: 3, sv: -1, spi: 0.75, cv: 0, cpi: 1 },
  ],
  note: null,
};

/** An epic re-planned once: the original plan is drawn, both drift flags are set, the budget comes from its tasks. */
export const EPIC_PROGRESS_EPIC: EpicProgressReport = {
  meta: { ...META, domainView: "EPIC" },
  level: "EPIC",
  scope: { kind: "EPIC", id: null, key: "FLO-33", name: "Reporting epic" },
  series: EVM_POINTS.map((point, index) => ({ ...point, pvOriginal: [10, 12, 14][index] })),
  asOf: EVM_AS_OF,
  epic: {
    budgetMd: 20,
    budgetSource: "CHILDREN",
    startAt: Date.UTC(2026, 8, 1),
    dueAt: Date.UTC(2026, 9, 31),
    inPvHorizon: true,
    hasPvCurve: true,
    baselines: [
      { effectiveFrom: 1_780_000_000_000 - 86_400_000 * 30, supersededAt: 1_780_000_000_000 - 86_400_000 * 10, startAt: Date.UTC(2026, 8, 1), dueAt: Date.UTC(2026, 9, 15), budgetMd: 16 },
      { effectiveFrom: 1_780_000_000_000 - 86_400_000 * 10, supersededAt: null, startAt: Date.UTC(2026, 8, 1), dueAt: Date.UTC(2026, 9, 31), budgetMd: 20 },
    ],
    drift: { dates: true, budget: true },
  },
  foreignWorkShare: null,
  rows: [],
  note: null,
};

/** An epic with no plan at all: PV 0 (no SPI), no original curve, no drift. */
export const EPIC_PROGRESS_NO_PLAN: EpicProgressReport = {
  ...EPIC_PROGRESS_EPIC,
  series: EVM_POINTS.map((point) => ({ ...point, pv: 0, pvOriginal: null })),
  asOf: { day: "2026-09-29", pv: 0, ev: 9, ac: 10, sv: 9, spi: null, cv: -1, cpi: 0.9 },
  epic: {
    budgetMd: null,
    budgetSource: null,
    startAt: null,
    dueAt: null,
    inPvHorizon: false,
    hasPvCurve: false,
    baselines: [],
    drift: { dates: false, budget: false },
  },
};

/** In the horizon, but the window is a weekend: no working day, so no curve. */
export const EPIC_PROGRESS_NO_CURVE: EpicProgressReport = {
  ...EPIC_PROGRESS_EPIC,
  series: EVM_POINTS.map((point) => ({ ...point, pv: 0, pvOriginal: null })),
  epic: { ...EPIC_PROGRESS_EPIC.epic!, hasPvCurve: false, baselines: [], drift: { dates: false, budget: false } },
};

/** A team with nothing logged: CPI and the foreign-work share are both missing. */
export const EPIC_PROGRESS_TEAM: EpicProgressReport = {
  meta: { ...META, level: "TEAM", domainView: "EPIC" },
  level: "TEAM",
  scope: { kind: "TEAM", id: 1, key: null, name: "Alpha" },
  series: EVM_POINTS.map((point) => ({ ...point, pvOriginal: null })),
  asOf: EVM_AS_OF,
  epic: null,
  foreignWorkShare: 0.125,
  rows: [],
  note: null,
};

export const EPIC_PROGRESS_TEAM_NO_COST: EpicProgressReport = {
  ...EPIC_PROGRESS_TEAM,
  asOf: { day: "2026-09-29", pv: 12, ev: 9, ac: 0, sv: -3, spi: 0.75, cv: 9, cpi: null },
  foreignWorkShare: null,
};

export const EPIC_PROGRESS_NOT_DERIVED: EpicProgressReport = {
  meta: { ...META, domainView: "EPIC", derivedAt: null },
  level: "UNIT",
  scope: null,
  series: [],
  asOf: { day: null, pv: 0, ev: 0, ac: 0, sv: 0, spi: null, cv: 0, cpi: null },
  epic: null,
  foreignWorkShare: null,
  rows: [],
  note: NOT_DERIVED,
};
