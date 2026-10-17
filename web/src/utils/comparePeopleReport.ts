import type { TFunction } from "i18next";
import type { CompareCell, CompareRow, ReportFilters } from "../api/reports";
import type { CompareMixBy, DrillAnchor, DrillUnit } from "./reportDrill";
import { formatDays, formatMd, formatPercent, formatRatio, formatSignedPercent, type ValueFormat } from "./reportFormat";
import { COMPARE_PEOPLE_COUNT, dropReportSpecific, type ReportControls, type ReportFilterState } from "./reportFilter";
import { comparePeoplePath, reportSpecificKeep } from "./reportLinks";

/**
 * Compare people (report 18): the bar offers the date period and the slices both people are read through — no team, member or sprint
 * period (the two people are picked beside the bar, `ComparePeoplePickers`). A task is a person's by its assignee at done, so the
 * domain view starts on TASK, like the other fact reports.
 */
export const COMPARE_CONTROLS: ReportControls = { peopleOnly: true, domainView: "TASK", domain: true, activityType: true, workCategory: true };

/**
 * Compare people's whole filter, made answerable: no team, single `accountId` or sprint-relative period (it reads two people across every
 * team over a date range), none of another report's keys, and only the first two of any more people — the URL is rewritten to say so (a
 * `normalize` step for `useReportPage`, a module-level function so its identity is stable).
 */
export function normalizeComparePeopleFilter(filter: ReportFilterState): ReportFilterState {
  const next = dropReportSpecific(filter, reportSpecificKeep(comparePeoplePath));
  delete next.teamId;
  delete next.accountId;
  delete next.lastSprints;
  delete next.sprintId;
  if (next.accountIds !== undefined) next.accountIds = next.accountIds.slice(0, COMPARE_PEOPLE_COUNT);
  return next;
}

type CompareUnit = CompareRow["unit"];
type CompareView = NonNullable<CompareRow["drill"]>;

const UNIT_FORMATS: Record<CompareUnit, ValueFormat> = {
  MD: formatMd,
  MD_PER_MD: formatRatio,
  ITEMS: formatMd,
  ITEMS_PER_DAY: formatRatio,
  ITEMS_PER_MD: formatRatio,
  DAYS: formatDays,
  RATIO: formatRatio,
  SHARE: formatPercent,
};

/** How a row's figure is written: its unit's formatter, except the median change of an estimate, a signed fraction (`+25%`). */
function rowFormat(row: CompareRow): ValueFormat {
  return row.key === "adjustmentMedianChange" ? formatSignedPercent : UNIT_FORMATS[row.unit];
}

/** The figures read as fractions (a share, an estimate's change): their gap is a number of percentage POINTS, not a percentage. */
function isFractional(row: CompareRow): boolean {
  return row.unit === "SHARE" || row.key === "adjustmentMedianChange";
}

const MISSING = "—";

/** A cell's figure for the table: a dash when it is withheld (under the minimum sample) or has no denominator. */
export function cellText(row: CompareRow, cell: CompareCell): string {
  return cell.value === null ? MISSING : rowFormat(row)(cell.value);
}

/** A signed gap with a true minus; a gap that prints as zero carries no sign. */
function signed(value: number, text: string): string {
  return parseFloat(text) === 0 ? text : `${value > 0 ? "+" : "−"}${text}`;
}

/** The gap in percentage points: `0.05` → `5`. */
function points(gap: number): string {
  return String(Math.round(Math.abs(gap) * 1000) / 10);
}

/** B minus A as the table writes it (`+1.4`, `−3`, `+5 pp`), or a dash when either side has no figure. */
export function differenceText(row: CompareRow, t: TFunction): string {
  const gap = row.difference;
  if (gap === undefined) return MISSING;
  if (isFractional(row)) return t("reports.comparePeople.points", { value: signed(gap, points(gap)) });
  return signed(gap, rowFormat(row)(Math.abs(gap)));
}

/** "1.4 days", "3 MD", "5 pp": how far apart the two sides are, in the row's unit (the cue's accessible name says it in words). */
function marginText(row: CompareRow, margin: number, t: TFunction): string {
  if (isFractional(row)) return t("reports.comparePeople.margin.SHARE", { value: points(margin) });
  const value = rowFormat(row)(margin);
  // Days and items (also per day / per available MD) inflect with the number they are told: the printed value, not the raw figure,
  // decides one or many; the other units have a single form and ignore the count.
  return t(`reports.comparePeople.margin.${row.unit}`, { count: Number(value), value });
}

/**
 * The accessible name of the cue on a row that has a better side: "A better by 1.4 days", or, for a row with a target, "A closer to
 * 1.0 by 0.12". `undefined` when the row names no side (a tie, a neutral row, a side under the minimum sample).
 */
export function betterLabel(row: CompareRow, t: TFunction): string | undefined {
  if (row.better === undefined || row.margin === undefined) return undefined;
  const margin = marginText(row, row.margin, t);
  return row.direction === "TARGET"
    ? t("reports.comparePeople.closerTo", { side: row.better, target: (row.target ?? 1).toFixed(1), margin })
    : t("reports.comparePeople.betterBy", { side: row.better, margin });
}

const UNCATEGORIZED = "UNCATEGORIZED";

/**
 * A work-mix key as the tables and the drawer title print it: the activity type or category as written, the reserved `UNCATEGORIZED`
 * as the bars' "(uncategorized)", a domain by its name (its key when the reference data no longer lists it), and a task without a value
 * as "(no …)" — the one key the mix has that cannot be sliced to.
 */
export function mixKeyLabel(by: CompareMixBy, key: string | null, t: TFunction, domains: ReportFilters["domains"]): string {
  if (key === null) return t(`reports.comparePeople.mix.none.${by}`);
  if (by === "workCategory" && key === UNCATEGORIZED) return t("reports.filters.uncategorized");
  if (by === "domain") return domains.find((domain) => domain.domainKey === key)?.domainName ?? key;
  return key;
}

/** The views whose figure counts the NUMERATOR of a share (or the held days of an average): a zero figure has nothing to list. */
const NUMERATOR_VIEWS: ReadonlySet<CompareView> = new Set(["ADJUSTED", "BLOCKED", "FOREIGN", "LATE", "NO_ESTIMATE", "WIP_TASK_DAYS"]);

/**
 * The item count the table stands behind for a view — what the drawer's own count is compared with — or `undefined` where the cell's
 * `n` is not a count of the listed items (a share counts its numerator, the coverage its gap, the average WIP its working days).
 */
// Rows sharing a view (the cycle p50/p90, the two WIP-at-end rows, the three DELIVERED rows) carry the same sample, so the FIRST row in the server's order stands for the view.
export function cellCountOf(report: { rows: readonly CompareRow[] }, view: string, accountIndex: number): number | undefined {
  if (NUMERATOR_VIEWS.has(view as CompareView) || view === "NO_WORKLOGS") return undefined;
  const row = report.rows.find((candidate) => candidate.drill === view);
  return row === undefined ? undefined : (accountIndex === 0 ? row.a : row.b).n;
}

/**
 * How many items a cell's figure stands for — the number that decides whether it is a button (`DrillFigure`: 0 = plain text). A cell with no
 * view (available man-days), no figure, or one withheld under the minimum sample has nothing to open; a share counts its numerator (a
 * zero share lists nothing, a coverage of 100% has no gap) and every other figure its sample `n`.
 */
// Depends on the server's row order only through `row.drill`: each row names its own view, so no ordering assumption is made here.
export function drillCount(row: CompareRow, cell: CompareCell): number {
  if (row.drill === undefined || cell.hidden || cell.value === null) return 0;
  if (row.drill === "NO_WORKLOGS") return cell.value < 1 ? cell.n : 0;
  if (NUMERATOR_VIEWS.has(row.drill)) return cell.value > 0 ? cell.n : 0;
  return cell.n;
}

/** How each view's list reads: the unit of its figure column and the instant of its date column (`anchorAt`). */
const VIEW_COLUMNS: Readonly<Record<CompareView, { unit: DrillUnit; anchor: DrillAnchor }>> = {
  DELIVERED: { unit: "md", anchor: "done" },
  CYCLE_WORKING_DAYS: { unit: "wd", anchor: "done" },
  CYCLE_ELAPSED_DAYS: { unit: "days", anchor: "done" },
  REPORTED_TIME_RATIO: { unit: "ratio", anchor: "done" },
  FLOW_EFFICIENCY: { unit: "share", anchor: "done" },
  ACCURACY_AT_START: { unit: "accuracy", anchor: "done" },
  ACCURACY_AT_DONE: { unit: "accuracy", anchor: "done" },
  ADJUSTED: { unit: "changes", anchor: "started" },
  ADJUSTMENT_CHANGE: { unit: "change", anchor: "done" },
  WIP_TASK_DAYS: { unit: "heldDays", anchor: "none" },
  WIP_AT_END: { unit: "ageWd", anchor: "started" },
  BLOCKED: { unit: "blockedWd", anchor: "done" },
  LOGGED: { unit: "loggedMd", anchor: "worklog" },
  FOREIGN: { unit: "loggedMd", anchor: "worklog" },
  LATE: { unit: "loggedMd", anchor: "worklog" },
  NO_WORKLOGS: { unit: "md", anchor: "done" },
  NO_ESTIMATE: { unit: "md", anchor: "done" },
};

/** The view's column pair; an unknown view (a newer server) reads as delivered tasks. */
export function viewColumns(view: string): { unit: DrillUnit; anchor: DrillAnchor } {
  return VIEW_COLUMNS[view as CompareView] ?? VIEW_COLUMNS.DELIVERED;
}
