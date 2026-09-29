import type { EpicProgressPoint } from "../api/reports";
import { applyReportFilter, parseReportFilter, type ReportFilterState } from "./reportFilter";
import { formatIndex } from "./reportFormat";

/** The scope an epic progress link selects: at most ONE of these is ever set. */
export interface EpicScope {
  epicId?: string;
  domain?: string;
  teamId?: number;
}

/**
 * Makes a filter the epic progress report can answer out of one it cannot. The report is always
 * the EPIC view (`domainView=TASK` is a `400`), has no user level (`accountId` is a `400`) and no
 * per-day activity type or work category (both `400`); the ignored `breakdown`/`bucket`/`by`/
 * `itemKind` are dropped too, since the page has no control for them. And the scope is ONE of
 * epic, domain or team: a pasted link with several keeps the most specific (epic, then domain,
 * then team) — the URL is rewritten to it, so a dropped scope cannot come back on its own.
 */
export function normalizeEpicProgressFilter(filter: ReportFilterState): ReportFilterState {
  const next = { ...filter };
  for (const key of ["domainView", "accountId", "activityType", "workCategory", "breakdown", "bucket", "by", "itemKind"] as const) {
    delete next[key];
  }
  if (next.epicId !== undefined) {
    delete next.domain;
    delete next.teamId;
  } else if (next.domain !== undefined) {
    delete next.teamId;
  }
  return next;
}

/**
 * A change made through the filter bar, made to honour "the last scope touched wins": picking a
 * team or a domain replaces the epic (which the bar cannot show or clear) and the other scope.
 * The bar's own `domainExcludesTeam` already swaps team and domain; the epic is the one it cannot.
 */
export function applyBarChange(previous: ReportFilterState, next: ReportFilterState): ReportFilterState {
  const result = { ...next };
  if (next.teamId !== undefined && next.teamId !== previous.teamId) {
    delete result.epicId;
    delete result.domain;
  } else if (next.domain !== undefined && next.domain !== previous.domain) {
    delete result.epicId;
    delete result.teamId;
  }
  return result;
}

/**
 * The query string of the same report re-scoped: the period and the other shared params travel, the scope is
 * replaced. `dropSprint` removes a one-sprint period (a sprint belongs to one team, so it must not follow a
 * link to a team that does not list it — the filter bar's `changeTeam` rule).
 */
export function scopedSearch(params: URLSearchParams, scope: EpicScope, options: { dropSprint?: boolean } = {}): string {
  const next = { ...parseReportFilter(params), ...scope };
  if (options.dropSprint) delete next.sprintId;
  if (scope.epicId === undefined) delete next.epicId;
  if (scope.domain === undefined) delete next.domain;
  if (scope.teamId === undefined) delete next.teamId;
  return applyReportFilter(params, normalizeEpicProgressFilter(next)).toString();
}

/** One chart/table row of the cumulative curves; `pvOriginal` is null where the epic has no original curve. */
export interface EvmRow {
  date: string;
  pv: number;
  ev: number;
  ac: number;
  pvOriginal: number | null;
}

export function evmRows(series: readonly EpicProgressPoint[]): EvmRow[] {
  return series.map((point) => ({
    date: point.date,
    pv: point.pv,
    ev: point.ev,
    ac: point.ac,
    pvOriginal: point.pvOriginal ?? null,
  }));
}

/** True when any point carries the original-plan curve (EPIC level, and only where that baseline has one). */
export function hasOriginalPlan(rows: readonly EvmRow[]): boolean {
  return rows.some((row) => row.pvOriginal !== null);
}

/** Where an index stands against 1, read at the two decimals it is printed with — "1.00" is on target. */
export type IndexVerdict = "above" | "below" | "on";

export function indexVerdict(value: number): IndexVerdict {
  const shown = Number(formatIndex(value));
  if (shown === 1) return "on";
  return shown > 1 ? "above" : "below";
}
