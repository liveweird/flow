// Shared report test fixtures: the reference data every report's filter bar reads, and two
// velocity responses (UNIT and TEAM level). Typed against the generated schema so a spec drift
// breaks the fixtures, not just the page.
import type { ReportFilters, SprintConsistencyReport, ThroughputReport, VelocityReport } from "../api/reports";

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
