// The by-epic sprint charts' fixtures (Commitments' `sprints[].byEpic`, Throughput's `bySprint[].byEpic`): Alpha's two closed sprints
// split by two top epics, the folded "Other epics" and "No epic", the segments adding up to each row's figures exactly. The two special
// segments carry a deliberately odd `label` — the server's is an English fallback, the page must localise them by `key`.
import type { ReportDrillItems } from "../api/reportDrill";
import type { CommitmentsReport, ThroughputReport } from "../api/reports";
import { COMMITMENTS_TEAM, COMMITMENTS_USERS, SPRINT_ITEMS, THROUGHPUT_TEAM, THROUGHPUT_USERS } from "./reportFixtures";

type CommitmentsEpic = CommitmentsReport["sprints"][number]["byEpic"][number];
type ThroughputEpic = ThroughputReport["bySprint"][number]["byEpic"][number];

const CHECKOUT = { key: "101", epicId: 101, epicKey: "FLO-10", label: "FLO-10 Checkout" };
const SEARCH = { key: "102", epicId: 102, epicKey: "FLO-11", label: "FLO-11 Search" };
const OTHER = { key: "OTHER_EPICS", epicId: null, epicKey: null, label: "fallback: other epics" };
const NONE = { key: "NO_EPIC", epicId: null, epicKey: null, label: "fallback: no epic" };

/** The two top epics' ids, in segment order: what an "Other epics" drill must send. */
export const TOP_EPIC_IDS = [101, 102];

const initialFinal = (identity: Omit<CommitmentsEpic, "initialMd" | "initialItems" | "finalMd" | "finalItems">, figures: [number, number, number, number]): CommitmentsEpic => ({
  ...identity,
  initialMd: figures[0],
  initialItems: figures[1],
  finalMd: figures[2],
  finalItems: figures[3],
});

/** Alpha 2 (initial 20 MD / 8, final 24.5 / 9) in four segments; Alpha 1 (15 / 6, 16 / 6) has no second epic and no "Other epics". */
const COMMITMENTS_BY_EPIC: Record<number, CommitmentsEpic[]> = {
  12: [
    initialFinal(CHECKOUT, [8, 3, 10, 3]),
    initialFinal(SEARCH, [6, 2, 7.5, 3]),
    initialFinal(OTHER, [4, 2, 4, 2]),
    initialFinal(NONE, [2, 1, 3, 1]),
  ],
  11: [initialFinal(CHECKOUT, [9, 3, 10, 3]), initialFinal(NONE, [6, 3, 6, 3])],
};

/** TEAM level: Alpha's two sprints, each split by epic. */
export const COMMITMENTS_EPICS: CommitmentsReport = {
  ...COMMITMENTS_TEAM,
  sprints: COMMITMENTS_TEAM.sprints.map((sprint) => ({ ...sprint, byEpic: COMMITMENTS_BY_EPIC[sprint.sprintId] ?? [] })),
};

/** USER level (two people picked): the same split, now the picked people's summed figures. */
export const COMMITMENTS_EPICS_USERS: CommitmentsReport = {
  ...COMMITMENTS_USERS,
  sprints: COMMITMENTS_USERS.sprints.map((sprint) => ({ ...sprint, byEpic: COMMITMENTS_BY_EPIC[sprint.sprintId] ?? [] })),
};

const delivered = (identity: Omit<ThroughputEpic, "deliveredMd" | "deliveredItems">, figures: [number, number]): ThroughputEpic => ({
  ...identity,
  deliveredMd: figures[0],
  deliveredItems: figures[1],
});

/** Alpha 2 delivered 18 MD / 7 items, Alpha 1 10 / 4. */
const THROUGHPUT_BY_EPIC: Record<number, ThroughputEpic[]> = {
  12: [delivered(CHECKOUT, [6, 2]), delivered(SEARCH, [5, 2]), delivered(OTHER, [4, 2]), delivered(NONE, [3, 1])],
  11: [delivered(CHECKOUT, [7, 3]), delivered(NONE, [3, 1])],
};

/** TEAM level: Alpha's two sprints, each split by epic; the period view (`byBucket`) is not split. */
export const THROUGHPUT_EPICS: ThroughputReport = {
  ...THROUGHPUT_TEAM,
  bySprint: THROUGHPUT_TEAM.bySprint.map((sprint) => ({ ...sprint, byEpic: THROUGHPUT_BY_EPIC[sprint.sprintId] ?? [] })),
};

/** USER level: the same split over the picked people. */
export const THROUGHPUT_EPICS_USERS: ThroughputReport = {
  ...THROUGHPUT_USERS,
  bySprint: THROUGHPUT_USERS.bySprint.map((sprint) => ({ ...sprint, byEpic: sprint.sprintId === 12 ? THROUGHPUT_BY_EPIC[12] : [] })),
};

/** The items behind one by-epic bar: [count] tasks worth [sumMd] MD together, as the sprint drills serve them. */
export function epicSegmentItems(count: number, sumMd: number): ReportDrillItems {
  return {
    ...SPRINT_ITEMS,
    bar: { count, sumMd, from: null, to: null },
    items: SPRINT_ITEMS.items.slice(0, count),
    total: count,
  };
}
