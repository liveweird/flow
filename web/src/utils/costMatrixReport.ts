import type { TFunction } from "i18next";
import type { CostEpicColumn, CostMatrixBucket, CostMatrixReport, ReportFilters, ReportMeta } from "../api/reports";
import type { CostBreakdownBy } from "./costBreakdown";
import type { DrillTarget, EpicDrillTarget } from "./reportDrill";
import { applyReportFilter, dropReportSpecific, parseReportFilter, type ReportFilterState } from "./reportFilter";
import { costMatrixPath, reportSpecificKeep } from "./reportLinks";
import { bucketLabel } from "./throughputReport";

/** True when the sprint split can be answered: sprints are per team, so the filter needs a real one (`teamId` >= 1, never the unassigned `0`). */
export function costSprintSplitAvailable(filter: ReportFilterState): boolean {
  return filter.teamId !== undefined && filter.teamId >= 1;
}

/**
 * Makes a filter the cost matrix can answer, and says what its page can show. The report reads the
 * domain view (default EPIC), a domain, an activity type, a work category and the time split, and the page
 * has a control for each, while `bucket`, `by`, `itemKind`, `epicId` and `connectionId` have none here. Every one
 * the page cannot show or clear is dropped off the request AND the URL — and so is a `split=SPRINT` no team backs
 * (the server answers it `400`; the page reads it as Total).
 */
export function normalizeCostMatrixFilter(filter: ReportFilterState): ReportFilterState {
  const next = dropReportSpecific(filter, reportSpecificKeep(costMatrixPath));
  delete next.connectionId; // an ingestion concept with no control here (the API still accepts it): a stale link's value is dropped
  if (next.split === "SPRINT" && !costSprintSplitAvailable(next)) delete next.split;
  return next;
}

/** True for a `lastSprints`/`sprintId` period: the server resolves sprints and sends no `from`/`to`. */
export function isSprintRelative(meta: ReportMeta): boolean {
  return meta.from === null;
}

/**
 * The largest single cell the matrix SHOWS — what the heat scale is measured against (0 when there is none). Unsplit,
 * those are the period cells; split, the period's domain cells are not repeated and the buckets' cells are the ones shown.
 */
export function maxCellMd(report: CostMatrixReport): number {
  let max = 0;
  for (const row of report.rows) {
    const shown = report.split === undefined ? row.cells : (row.byBucket ?? []).flatMap((bucket) => bucket.cells);
    for (const cell of shown) max = Math.max(max, cell.md);
  }
  return max;
}

/** A split group's heading: a week by its Monday, a sprint by its name, the time outside every sprint by its own label. */
export function bucketHeading(bucket: CostMatrixBucket, kind: "WEEK" | "SPRINT", t: TFunction): string {
  if (kind === "WEEK") return bucketLabel(bucket.key, "WEEK");
  return bucket.label ?? t("reports.costMatrix.outsideSprints");
}

/**
 * The query string of the same report drilled into one author team (`teamId`) or one author of it
 * (`teamId` + `accountId`): the period and the other shared params travel along. A one-sprint period
 * survives a drill only into a team that lists that sprint (the filter bar's `changeTeam` rule), else it
 * falls back to the default period.
 */
export function drillSearch(params: URLSearchParams, filters: ReportFilters, target: { teamId: number; accountId?: string }): string {
  const current = parseReportFilter(params);
  const next: ReportFilterState = { ...current, teamId: target.teamId, accountId: target.accountId };
  const listed = filters.teams.find((team) => team.id === target.teamId)?.sprints.some((sprint) => sprint.sprintId === current.sprintId);
  if (current.sprintId !== undefined && !listed) delete next.sprintId;
  return applyReportFilter(params, normalizeCostMatrixFilter(next)).toString();
}

/** The focus-return anchors of the cost matrix's drill cards: a drill opened by a click on a donut slice returns focus to its card. */
const BREAKDOWN_ANCHORS: Record<CostBreakdownBy, string> = { ACTIVITY_TYPE: "cost-activity-type", WORK_CATEGORY: "cost-work-category" };
export const EPIC_ANCHOR = "cost-epics";

/** The breakdown card's anchor. */
export const breakdownAnchor = (by: CostBreakdownBy): string => BREAKDOWN_ANCHORS[by];

/** The card the page's ONE drill returns focus to: the target's own (a donut's), else the epic table's. */
export const costDrillAnchor = (target: DrillTarget): string => (target.kind === "cost" ? BREAKDOWN_ANCHORS[target.by] : EPIC_ANCHOR);

/** The identity of a matrix row, or of an epic-table column standing for one: the author team and author, the soft-delete mark when known. */
interface RowIdentity {
  teamId: number | null;
  accountId: string | null;
  active?: boolean | null;
}

/**
 * Where a matrix row (or a column of the same identity) can be drilled into: an author team at UNIT level (`{ teamId }`), an author at
 * TEAM level (`{ teamId, accountId }`), else `null` — the no-team and no-author rows, a soft-deleted team (its own drill is a `400`),
 * the unassigned team under a sprint-relative period (a team-less drill resolves no sprint and answers empty), and USER level.
 */
export function rowDrillTarget(row: RowIdentity, level: ReportMeta["level"], sprintRelative: boolean): { teamId: number; accountId?: string } | null {
  if (row.teamId === null) return null;
  if (row.teamId === 0 && sprintRelative) return null;
  if (level === "UNIT") return row.active === false ? null : { teamId: row.teamId };
  if (level === "TEAM" && row.accountId !== null) return { teamId: row.teamId, accountId: row.accountId };
  return null;
}

/**
 * The drill target of an epic-table figure in one author column: the epic `key` narrowed to the column — its author team (UNIT) or author
 * (TEAM) — or `null` where the matrix would not open that author either (`rowDrillTarget`'s rules). A UNIT cell under a sprint-relative period
 * is not drillable at all: the team rows read the union of every team's sprints, a team drill only its own, so the list would not add up to the cell.
 */
export function epicCellTarget(report: Pick<CostMatrixReport, "meta">, column: CostEpicColumn, keys: string[]): EpicDrillTarget | null {
  const { level } = report.meta;
  const sprintRelative = isSprintRelative(report.meta);
  if (level === "UNIT" && sprintRelative) return null;
  // `active` is the column's own soft-delete mark (UNIT only, like the matrix row's): a deleted author team is not drillable.
  const target = rowDrillTarget({ teamId: column.teamId, accountId: column.accountId, active: column.active }, level, sprintRelative);
  if (target === null) return null;
  return target.accountId === undefined ? { kind: "epic", keys, teamId: target.teamId } : { kind: "epic", keys, teamId: target.teamId, segmentAccountId: target.accountId };
}
