import type { TFunction } from "i18next";
import type { ReportFilters, CommitmentsSprint } from "../api/reports";
import type { ColumnDef } from "../components/ColumnTable";
import type { SprintBucket } from "./reportDrill";
import { formatMd } from "./reportFormat";
import { labelledSprints } from "./reportSprints";

/** The four figures every commitments row (a sprint, a group, a person's share of a sprint) carries. */
export interface CommitmentsFigures {
  initialMd: number;
  initialItems: number;
  finalMd: number;
  finalItems: number;
}

/** The initial/final MD and item columns every commitments table shares; `pick` finds the figures in a row. */
export function commitmentsFigureColumns<T>(t: TFunction, pick: (row: T) => CommitmentsFigures): ColumnDef<T>[] {
  return [
    { key: "initialMd", header: t("reports.commitments.column.initialMd"), render: (row) => formatMd(pick(row).initialMd) },
    { key: "initialItems", header: t("reports.commitments.column.initialItems"), render: (row) => pick(row).initialItems },
    { key: "finalMd", header: t("reports.commitments.column.finalMd"), render: (row) => formatMd(pick(row).finalMd) },
    { key: "finalItems", header: t("reports.commitments.column.finalItems"), render: (row) => pick(row).finalItems },
  ];
}

/**
 * The bucket a figure column of {@link commitmentsFigureColumns} stands behind (Initial = COMMITTED, Final = FINAL) and the item
 * count of that figure, which is what a drill of it lists; any other key has no bucket.
 */
export function commitmentsFigureDrill<T>(
  key: string,
  pick: (row: T) => CommitmentsFigures,
): { bucket: SprintBucket; items: (row: T) => number } | undefined {
  switch (key) {
    case "initialMd":
    case "initialItems":
      return { bucket: "COMMITTED", items: (row) => pick(row).initialItems };
    case "finalMd":
    case "finalItems":
      return { bucket: "FINAL", items: (row) => pick(row).finalItems };
    default:
      return undefined;
  }
}

/** Team mode: one row per sprint — the committed and final bars and the capacity marker. */
export interface CommitmentsChartRow {
  /** The row's identity in the drill: `<teamId>:<sprintId>`. */
  id: string;
  /** The x-axis category: the sprint's name, prefixed with its team when several teams share the chart. */
  label: string;
  initialMd: number;
  finalMd: number;
  initialItems: number;
  finalItems: number;
  /** The sprint's capacity in MD; `null` = none, and the line has no marker there. */
  capacityMd: number | null;
  /** Where the capacity came from — the tooltip says a default one is members × working days. */
  capacitySource: CommitmentsSprint["capacitySource"];
}

/** The bar-chart rows: one per sprint, in completion order. */
export function commitmentsChartRows(sprints: readonly CommitmentsSprint[], filters: ReportFilters): CommitmentsChartRow[] {
  return labelledSprints(sprints, filters).map(({ sprint, label }) => ({
    id: `${sprint.teamId}:${sprint.sprintId}`,
    label,
    initialMd: sprint.initialMd,
    finalMd: sprint.finalMd,
    initialItems: sprint.initialItems,
    finalItems: sprint.finalItems,
    capacityMd: sprint.capacityMd,
    capacitySource: sprint.capacitySource,
  }));
}

/** True when some sprint has a capacity — otherwise the capacity series has nothing to draw and stays out of the chart. */
export function hasCapacity(rows: readonly CommitmentsChartRow[]): boolean {
  return rows.some((row) => row.capacityMd !== null);
}

/** Initial committed MD over the capacity, as a fraction; `null` when there is no capacity (or it is 0). */
export function loadFraction(initialMd: number, capacityMd: number | null): number | null {
  return capacityMd === null || capacityMd === 0 ? null : initialMd / capacityMd;
}

/**
 * One picked person of the user-mode chart; `committedKey`/`finalKey` are the data keys of their two segments. `position`
 * (1-based, bottom of the stack first) is the person's number: with more than seven people the categorical palette repeats, so
 * the number — `personLabel` — is what tells the segments apart in the legend, the tooltip and the tables.
 */
export interface CommitmentsChartUser {
  accountId: string;
  label: string;
  position: number;
  committedKey: string;
  finalKey: string;
}

/** A person as the chart, its legend, its tooltip and the tables beside it name them: "2. Bob". */
export function personLabel(position: number, label: string): string {
  return `${position}. ${label}`;
}

/** The people of a set of sprints in stack order: the union of every sprint's `byUser`, first seen first. */
function chartUsers(sprints: readonly CommitmentsSprint[]): CommitmentsChartUser[] {
  const users: CommitmentsChartUser[] = [];
  for (const sprint of sprints) {
    for (const share of sprint.byUser) {
      if (users.some((user) => user.accountId === share.accountId)) continue;
      const index = users.length;
      users.push({ accountId: share.accountId, label: share.label, position: index + 1, committedKey: `c${index}`, finalKey: `f${index}` });
    }
  }
  return users;
}

/** Each person's stack position by account id — the number the chart and every table beside it show. */
export function userPositions(sprints: readonly CommitmentsSprint[], filters: ReportFilters): ReadonlyMap<string, number> {
  const ordered = labelledSprints(sprints, filters).map(({ sprint }) => sprint);
  return new Map(chartUsers(ordered).map((user) => [user.accountId, user.position]));
}

/** User mode: a sprint's row holds each person's committed and final MD under their keys (account ids are not safe data keys). */
export type CommitmentsUserRow = { id: string; label: string } & Record<string, number | string>;

/**
 * The stacked-column rows of user mode: the people are the union of every sprint's `byUser` in first-seen (request)
 * order, a person a sprint does not list counts 0 there. Two stacks per sprint — committed and final — each cut into
 * one segment per person.
 */
export function commitmentsUserChart(
  sprints: readonly CommitmentsSprint[],
  filters: ReportFilters,
): { users: CommitmentsChartUser[]; rows: CommitmentsUserRow[] } {
  const labelled = labelledSprints(sprints, filters);
  const users = chartUsers(labelled.map(({ sprint }) => sprint));
  const rows = labelled.map(({ sprint, label }) => {
    const row: CommitmentsUserRow = { id: `${sprint.teamId}:${sprint.sprintId}`, label };
    for (const user of users) {
      const share = sprint.byUser.find((candidate) => candidate.accountId === user.accountId);
      row[user.committedKey] = share?.initialMd ?? 0;
      row[user.finalKey] = share?.finalMd ?? 0;
    }
    return row;
  });
  return { users, rows };
}

/** A flat row of the per-person table beside the user-mode chart: one person's share of one sprint. */
export interface CommitmentsShareRow {
  key: string;
  sprint: CommitmentsSprint;
  /** The person's stack position in the chart (`CommitmentsChartUser.position`). */
  position: number;
  share: CommitmentsSprint["byUser"][number];
}

/** The per-person-per-sprint table rows, sprints in completion order, people numbered as in the chart. */
export function commitmentsShareRows(sprints: readonly CommitmentsSprint[], filters: ReportFilters): CommitmentsShareRow[] {
  const positions = userPositions(sprints, filters);
  return labelledSprints(sprints, filters).flatMap(({ sprint }) =>
    sprint.byUser.map((share) => ({
      key: `${sprint.teamId}:${sprint.sprintId}:${share.accountId}`,
      sprint,
      position: positions.get(share.accountId) ?? 0,
      share,
    })),
  );
}
