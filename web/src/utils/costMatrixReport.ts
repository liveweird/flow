import type { CostMatrixReport, ReportFilters, ReportMeta } from "../api/reports";
import { applyReportFilter, parseReportFilter, type ReportFilterState } from "./reportFilter";

/**
 * Makes a filter the cost matrix can answer, and says what its page can show. The report reads the
 * domain view (default EPIC), a domain, an activity type, a work category and a connection, and the page
 * has a control for each; it also accepts `breakdown` (and changes nothing), while `bucket`, `by`,
 * `itemKind` and `epicId` are other reports' own params. Every one the page cannot show or clear is
 * dropped off the request AND the URL.
 */
export function normalizeCostMatrixFilter(filter: ReportFilterState): ReportFilterState {
  const next = { ...filter };
  for (const key of ["breakdown", "bucket", "by", "itemKind", "epicId"] as const) {
    delete next[key];
  }
  return next;
}

/** True for a `lastSprints`/`sprintId` period: the server resolves sprints and sends no `from`/`to`. */
export function isSprintRelative(meta: ReportMeta): boolean {
  return meta.from === null;
}

/** The largest single cell of the matrix — what the heat scale is measured against (0 when there is none). */
export function maxCellMd(report: CostMatrixReport): number {
  let max = 0;
  for (const row of report.rows) {
    for (const cell of row.cells) max = Math.max(max, cell.md);
  }
  return max;
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
