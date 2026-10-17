import type { TFunction } from "i18next";
import type { ReportDrillItem } from "../api/reportDrill";
import type { ColumnDef } from "../components/ColumnTable";
import { formatDate } from "./formatDate";
import type { CostBreakdownBy } from "./costBreakdown";
import { isValidIsoDate } from "./isoDate";
import { formatDays, formatMd, formatPercent, formatRatio, formatSignedPercent } from "./reportFormat";

/**
 * The drill's whole state is ONE URL param: which bar of the chart the open drawer lists. It is page-local like throughput's
 * `view` (no part of the report filter, never sent with a report request, never in a query key) and `reportHref` drops it on a
 * tab hop, because a bar means nothing on another report.
 */
export const DRILL_PARAM = "drill";

/** The seven sprint-scope buckets (`Report 6`): the vocabulary of every sprint bar's drill, spelled as the API's `bucket` enum. */
const SPRINT_BUCKETS = ["COMMITTED", "ADDED", "REMOVED", "FINAL", "DELIVERED", "CARRIED_OVER", "DROPPED"] as const;
export type SprintBucket = (typeof SPRINT_BUCKETS)[number];

/**
 * What a drill is about: one throughput period bucket (`bucket`, by its first day), one sprint's scope bucket (`sprint`), or one
 * bar of a distribution report's histogram (`hist`: `view` names the distribution — the server's own `view` value — and
 * `bucketIndex` the bar, 0-based, the open tail last), or one slice of the cost matrix's donuts (`cost`: the breakdown `by` and the
 * slice's `keys`, several for the folded "Other"). A sprint is named by its team AND its id: several connections to one Jira
 * site carry the same sprint id under different teams, and a unit-wide chart (Home) draws them all, so the id alone would list every
 * team's items. `segmentAccountId` narrows a stacked bar to one person's share of it.
 */
export type DrillTarget =
  | { kind: "bucket"; bucketStart: string; segmentAccountId?: string }
  | { kind: "sprint"; teamId: number; sprintId: number; bucket: SprintBucket; segmentAccountId?: string }
  | { kind: "hist"; view: string; bucketIndex: number; segmentAccountId?: string }
  | { kind: "cost"; by: CostBreakdownBy; keys: string[]; segmentAccountId?: string };

/** The histogram target by name, for the code that handles only that shape. */
export type HistDrillTarget = Extract<DrillTarget, { kind: "hist" }>;

/** The cost-slice target by name, for the code that handles only that shape. */
export type CostDrillTarget = Extract<DrillTarget, { kind: "cost" }>;

/** The most keys one cost drill carries (the server's bound): "Other" lists every folded key. */
const COST_MAX_KEYS = 100;

/**
 * The unit of a drill list's figure column (each report's own per-item value): MD delivered, a sprint bucket's estimate in MD,
 * the man-days logged in a cost slice, or a distribution's own measure (working days, elapsed days, a ratio, a share, an accuracy ratio, blocked days, a blocked share, a signed estimate change).
 */
export type DrillUnit = "md" | "loggedMd" | "estimateMd" | "wd" | "days" | "ratio" | "share" | "accuracy" | "blockedWd" | "blockedShare" | "change";

/** Which instant the date column shows (`anchorAt`): when the item was done, when its sprint was completed, or when it was last logged on. */
export type DrillAnchor = "done" | "completed" | "logged";

const SEGMENT_SEPARATOR = ";s=";

function identityOf(target: DrillTarget): string {
  if (target.kind === "bucket") return `bucket:${target.bucketStart}`;
  if (target.kind === "sprint") return `sprint:${target.teamId}:${target.sprintId}:${target.bucket}`;
  // Keys are free text (an activity type may hold a colon or a comma): each is percent-encoded, so ":" and "," stay separators.
  if (target.kind === "cost") return `cost:${target.by}:${target.keys.map(encodeURIComponent).join(",")}`;
  return `hist:${target.view}:${target.bucketIndex}`;
}

/**
 * The URL spelling of a target: `bucket:2026-03-02`, `sprint:<teamId>:<sprintId>:COMMITTED` or `hist:WORKING_DAYS:3`, plus `;s=<accountId>`
 * for one person's segment. An account id is the REST of the string after the separator (Jira ids carry colons), so it never needs
 * escaping beyond the URL's own.
 */
export function serializeDrill(target: DrillTarget): string {
  const identity = identityOf(target);
  return target.segmentAccountId ? `${identity}${SEGMENT_SEPARATOR}${target.segmentAccountId}` : identity;
}

/** A team or sprint id: a positive whole number of at most 16 digits. */
function parseId(raw: string): number | null {
  return /^[1-9]\d{0,15}$/.test(raw) ? Number(raw) : null;
}

const VIEW_PATTERN = /^[A-Z][A-Z_]*$/;
const INDEX_PATTERN = /^\d{1,6}$/;

const COST_BYS: readonly CostBreakdownBy[] = ["ACTIVITY_TYPE", "WORK_CATEGORY"];

/** A `cost:<by>:<key>,<key>…` target: a known breakdown and one to [COST_MAX_KEYS] non-empty, well-encoded keys (else `null`). */
function parseCost(by: string, joined: string | undefined): DrillTarget | null {
  const known = COST_BYS.find((candidate) => candidate === by);
  if (known === undefined || joined === undefined) return null;
  try {
    const keys = joined.split(",").map((key) => decodeURIComponent(key));
    return keys.length <= COST_MAX_KEYS && keys.every((key) => key !== "") ? { kind: "cost", by: known, keys } : null;
  } catch {
    return null; // a malformed percent escape
  }
}

/** The target a `drill` param spells, or `null` for anything this module did not write (forgiving, like the report filter parser). */
export function parseDrill(raw: string | null): DrillTarget | null {
  if (!raw) return null;
  const at = raw.indexOf(SEGMENT_SEPARATOR);
  const identity = at < 0 ? raw : raw.slice(0, at);
  const segmentAccountId = at < 0 ? undefined : raw.slice(at + SEGMENT_SEPARATOR.length);
  if (segmentAccountId === "") return null;
  const [kind, first, second, third, ...rest] = identity.split(":");
  if (rest.length > 0 || !first) return null;
  const segment = segmentAccountId === undefined ? {} : { segmentAccountId };
  if (kind === "bucket") return second === undefined && isValidIsoDate(first) ? { kind, bucketStart: first, ...segment } : null;
  if (kind === "sprint") {
    const teamId = parseId(first);
    const sprintId = second === undefined ? null : parseId(second);
    const bucket = SPRINT_BUCKETS.find((candidate) => candidate === third);
    return teamId !== null && sprintId !== null && bucket !== undefined ? { kind, teamId, sprintId, bucket, ...segment } : null;
  }
  if (kind === "cost") return third === undefined ? parseCost(first, second) : null;
  if (kind === "hist") {
    return second !== undefined && third === undefined && VIEW_PATTERN.test(first) && INDEX_PATTERN.test(second)
      ? { kind, view: first, bucketIndex: Number(second), ...segment }
      : null;
  }
  return null;
}

/** `target` with its segment set to `accountId` (or removed). */
export function withSegment(target: DrillTarget, accountId: string | undefined): DrillTarget {
  const next = { ...target };
  delete next.segmentAccountId;
  return accountId === undefined ? next : { ...next, segmentAccountId: accountId };
}

const FIGURE_FORMATTERS: Record<DrillUnit, (value: number) => string> = {
  md: formatMd,
  loggedMd: formatMd,
  estimateMd: formatMd,
  wd: formatDays,
  days: formatDays,
  ratio: formatRatio,
  share: formatPercent,
  accuracy: formatRatio,
  blockedWd: formatDays,
  blockedShare: formatPercent,
  change: formatSignedPercent,
};

/** The column that says whether a row is a task or an epic (only a list that mixes the two needs it; a worklog list never does). */
function kindColumn(t: TFunction): ColumnDef<ReportDrillItem> {
  return {
    key: "kind",
    header: t("reports.drill.columns.kind"),
    render: (item) => t(item.itemKind === "EPIC" ? "reports.kind.EPIC" : "reports.kind.TASK"),
  };
}

/**
 * The people column. A delivery list names the item's assignee (an epic has none: a dash, not "Unassigned"); the cost list
 * (`loggedMd`) names the AUTHORS of the worklogs in the slice — the server joins several — or "No known author".
 */
function peopleColumn(t: TFunction, unit: DrillUnit): ColumnDef<ReportDrillItem> {
  if (unit === "loggedMd") {
    return { key: "assignee", header: t("reports.drill.columns.authors"), render: (item) => item.assignee ?? t("reports.costMatrix.noAuthor") };
  }
  return {
    key: "assignee",
    header: t("reports.drill.columns.assignee"),
    render: (item) => item.assignee ?? (item.itemKind === "EPIC" ? "—" : t("reports.groups.unassigned")),
  };
}

/**
 * The drawer's columns: issue key, summary, (the kind, only when [showKind] — a list mixing tasks and epics), assignee, the anchor
 * date (in the configured zone) and the bar's own figure (a dash = unestimated). An epic has no assignee (A19), so it shows a dash
 * rather than "Unassigned".
 */
export function drillItemColumns(
  t: TFunction,
  unit: DrillUnit,
  anchor: DrillAnchor,
  timeZone: string,
  showKind = false,
): ColumnDef<ReportDrillItem>[] {
  const format = FIGURE_FORMATTERS[unit];
  return [
    { key: "issueKey", header: t("reports.drill.columns.issueKey"), render: (item) => item.issueKey },
    { key: "summary", header: t("reports.drill.columns.summary"), render: (item) => item.summary ?? "—" },
    ...(showKind ? [kindColumn(t)] : []),
    peopleColumn(t, unit),
    { key: "anchor", header: t(`reports.drill.columns.${anchor}`), render: (item) => formatDate(item.anchorAt, "—", timeZone) },
    {
      key: "figure",
      header: t(`reports.drill.columns.figure.${unit}`),
      align: "right",
      render: (item) => (item.figure === null ? "—" : format(item.figure)),
    },
  ];
}
