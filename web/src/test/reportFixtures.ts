// Shared report test fixtures: the reference data every report's filter bar reads, and two
// velocity responses (UNIT and TEAM level). Typed against the generated schema so a spec drift
// breaks the fixtures, not just the page.
import type { ReportFilters, VelocityReport } from "../api/reports";

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
