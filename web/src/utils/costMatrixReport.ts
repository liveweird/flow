import type { TFunction } from "i18next";
import type { CostMatrixBucket, CostMatrixReport, ReportFilters, ReportMeta } from "../api/reports";
import { applyReportFilter, dropReportSpecific, parseReportFilter, type ReportFilterState } from "./reportFilter";
import { costMatrixPath, reportSpecificKeep } from "./reportLinks";
import { bucketLabel } from "./throughputReport";

/** True when the sprint split can be answered: sprints are per team, so the filter needs a real one (`teamId` >= 1, never the unassigned `0`). */
export function costSprintSplitAvailable(filter: ReportFilterState): boolean {
  return filter.teamId !== undefined && filter.teamId >= 1;
}

/**
 * Makes a filter the cost matrix can answer, and says what its page can show. The report reads the
 * domain view (default EPIC), a domain, an activity type, a work category, a connection and the time split, and the page
 * has a control for each, while `bucket`, `by`, `itemKind` and `epicId` are other reports' own params. Every one
 * the page cannot show or clear is dropped off the request AND the URL — and so is a `split=SPRINT` no team backs
 * (the server answers it `400`; the page reads it as Total).
 */
export function normalizeCostMatrixFilter(filter: ReportFilterState): ReportFilterState {
  const next = dropReportSpecific(filter, reportSpecificKeep(costMatrixPath));
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
