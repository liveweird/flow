// The Costs sprint widgets' fixtures (`sprints[]`, `outsideSprintsMd`): Alpha's two closed sprints, oldest first as the server sends them.
// Alpha 1 has a calendar capacity and two segments; Alpha 2 has NO capacity and all four segments. The segments add up to each sprint's AC
// exactly. The special segments carry a deliberately odd `label` — the server's is an English fallback, the page must localise by `key`.
import type { ReportDrillItems } from "../api/reportDrill";
import type { CostsReport, CostsSprint } from "../api/reports";
import { COSTS_TEAM, histogramItems } from "./reportFixtures";

type Segment = CostsSprint["byEpic"][number];

const segment = (key: string, epicId: number | null, epicKey: string | null, label: string, acMd: number): Segment => ({ key, epicId, epicKey, label, acMd });
const checkout = (acMd: number) => segment("101", 101, "FLO-10", "FLO-10 Checkout", acMd);
const search = (acMd: number) => segment("102", 102, "FLO-11", "FLO-11 Search", acMd);
const other = (acMd: number) => segment("OTHER_EPICS", null, null, "fallback: other epics", acMd);
const none = (acMd: number) => segment("NO_EPIC", null, null, "fallback: no epic", acMd);

/** The two top epics' ids, in segment order: what an "Other epics" drill must send. */
export const COSTS_TOP_EPIC_IDS = [101, 102];

/** Alpha 1: AC 10 MD against a calendar capacity of 12. */
const ALPHA_1: CostsSprint = {
  sprintId: 11,
  name: "Alpha 1",
  teamId: 1,
  connectionId: 1,
  startAt: 1_000,
  completedAt: 2_000,
  acMd: 10,
  capacityMd: 12,
  capacitySource: "CALENDAR",
  byEpic: [checkout(6), none(4)],
};

/** Alpha 2: AC 13.5 MD and no capacity. */
const ALPHA_2: CostsSprint = {
  sprintId: 12,
  name: "Alpha 2",
  teamId: 1,
  connectionId: 1,
  startAt: 3_000,
  completedAt: 4_000,
  acMd: 13.5,
  capacityMd: null,
  capacitySource: null,
  byEpic: [checkout(7), search(3), other(2.5), none(1)],
};

/** TEAM level with both sprints and 2.5 MD logged in the period outside them. */
export const COSTS_WITH_SPRINTS: CostsReport = {
  ...COSTS_TEAM,
  sprints: [ALPHA_1, ALPHA_2],
  outsideSprintsMd: 2.5,
  categoryTargets: null,
  categoryShareTrend: null,
};

/** One author picked (USER level): the AC is theirs and the team's capacity is withheld. */
export const COSTS_WITH_SPRINTS_USER: CostsReport = {
  ...COSTS_WITH_SPRINTS,
  meta: { ...COSTS_TEAM.meta, level: "USER" },
  sprints: [ALPHA_1, ALPHA_2].map((sprint) => ({ ...sprint, capacityMd: null, capacitySource: null })),
};

/** A team that completed no sprint in the period: the sprint list is empty and every logged man-day is outside. */
export const COSTS_NO_SPRINTS: CostsReport = { ...COSTS_TEAM, sprints: [], outsideSprintsMd: COSTS_TEAM.totalMd };

/** The unit level (the API only; the page never asks for it): no sprint and no outside figure. */
export const COSTS_UNIT: CostsReport = { ...COSTS_TEAM, meta: { ...COSTS_TEAM.meta, level: "UNIT" }, sprints: [], outsideSprintsMd: null };

/** The worklogs behind a sprint bar: [sumMd] is the drill's recomputed bar. */
export function costsSprintItems(sumMd: number): ReportDrillItems {
  return {
    ...histogramItems(2, 0, null, 0),
    bar: { count: 2, sumMd, from: null, to: null },
    items: [
      { issueKey: "FLO-1", summary: "Build it", itemKind: "TASK", accountId: null, assignee: "Ann Author, Bob", teamId: null, anchorAt: Date.UTC(2026, 2, 2, 10), figure: sumMd - 0.5 },
      { issueKey: "FLO-E1", summary: "The epic", itemKind: "EPIC", accountId: "acc-bob", assignee: "Bob", teamId: null, anchorAt: Date.UTC(2026, 1, 20, 10), figure: 0.5 },
    ],
  };
}
