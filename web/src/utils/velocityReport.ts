import type { TFunction } from "i18next";
import type { ReportFilters, VelocitySprint } from "../api/reports";
import type { ColumnDef } from "../components/ColumnTable";
import { formatMd } from "./reportFormat";
import { labelledSprints } from "./reportSprints";

/** The four figures every velocity row (a sprint, a group, a person's share of a sprint) carries. */
export interface VelocityFigures {
  initialMd: number;
  initialItems: number;
  finalMd: number;
  finalItems: number;
}

/** The initial/final MD and item columns every velocity table shares; `pick` finds the figures in a row. */
export function velocityFigureColumns<T>(t: TFunction, pick: (row: T) => VelocityFigures): ColumnDef<T>[] {
  return [
    { key: "initialMd", header: t("reports.velocity.column.initialMd"), render: (row) => formatMd(pick(row).initialMd) },
    { key: "initialItems", header: t("reports.velocity.column.initialItems"), render: (row) => pick(row).initialItems },
    { key: "finalMd", header: t("reports.velocity.column.finalMd"), render: (row) => formatMd(pick(row).finalMd) },
    { key: "finalItems", header: t("reports.velocity.column.finalItems"), render: (row) => pick(row).finalItems },
  ];
}

/** Team mode: one row per sprint — the committed and final bars and the capacity marker. */
export interface VelocityChartRow {
  /** The x-axis category: the sprint's name, prefixed with its team when several teams share the chart. */
  label: string;
  initialMd: number;
  finalMd: number;
  initialItems: number;
  finalItems: number;
  /** The sprint's capacity in MD; `null` = none, and the line has no marker there. */
  capacityMd: number | null;
  /** Where the capacity came from — the tooltip says a default one is members × working days. */
  capacitySource: VelocitySprint["capacitySource"];
}

/** The bar-chart rows: one per sprint, in completion order. */
export function velocityChartRows(sprints: readonly VelocitySprint[], filters: ReportFilters): VelocityChartRow[] {
  return labelledSprints(sprints, filters).map(({ sprint, label }) => ({
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
export function hasCapacity(rows: readonly VelocityChartRow[]): boolean {
  return rows.some((row) => row.capacityMd !== null);
}

/** Initial committed MD over the capacity, as a fraction; `null` when there is no capacity (or it is 0). */
export function loadFraction(initialMd: number, capacityMd: number | null): number | null {
  return capacityMd === null || capacityMd === 0 ? null : initialMd / capacityMd;
}

/** One picked person of the user-mode chart; `committedKey`/`finalKey` are the data keys of their two segments. */
export interface VelocityChartUser {
  accountId: string;
  label: string;
  committedKey: string;
  finalKey: string;
}

/** User mode: a sprint's row holds each person's committed and final MD under their keys (account ids are not safe data keys). */
export type VelocityUserRow = { label: string } & Record<string, number | string>;

/**
 * The stacked-column rows of user mode: the people are the union of every sprint's `byUser` in first-seen (request)
 * order, a person a sprint does not list counts 0 there. Two stacks per sprint — committed and final — each cut into
 * one segment per person.
 */
export function velocityUserChart(
  sprints: readonly VelocitySprint[],
  filters: ReportFilters,
): { users: VelocityChartUser[]; rows: VelocityUserRow[] } {
  const labelled = labelledSprints(sprints, filters);
  const users: VelocityChartUser[] = [];
  for (const { sprint } of labelled) {
    for (const share of sprint.byUser) {
      if (users.some((user) => user.accountId === share.accountId)) continue;
      const index = users.length;
      users.push({ accountId: share.accountId, label: share.label, committedKey: `c${index}`, finalKey: `f${index}` });
    }
  }
  const rows = labelled.map(({ sprint, label }) => {
    const row: VelocityUserRow = { label };
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
export interface VelocityShareRow {
  key: string;
  sprint: VelocitySprint;
  share: VelocitySprint["byUser"][number];
}

/** The per-person-per-sprint table rows, sprints in completion order, people in request order. */
export function velocityShareRows(sprints: readonly VelocitySprint[], filters: ReportFilters): VelocityShareRow[] {
  return labelledSprints(sprints, filters).flatMap(({ sprint }) =>
    sprint.byUser.map((share) => ({ key: `${sprint.teamId}:${sprint.sprintId}:${share.accountId}`, sprint, share })),
  );
}
