// Shared report test fixtures: the reference data every report's filter bar reads, and two
// velocity responses (UNIT and TEAM level). Typed against the generated schema so a spec drift
// breaks the fixtures, not just the page.
import type {
  AdjustmentFigures,
  Distribution,
  EpicAccuracyRow,
  EpicEstimationAccuracyReport,
  EstimateAdjustmentsReport,
  ReportFilters,
  SprintConsistencyReport,
  TaskEstimationAccuracyReport,
  ThroughputReport,
  VelocityReport,
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
