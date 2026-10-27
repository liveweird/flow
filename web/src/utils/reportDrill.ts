import type { ReactNode } from "react";
import type { TFunction } from "i18next";
import { HOME_DRILL_VIEWS, type HomeDrillView } from "../api/home";
import type { ReportDrillItem } from "../api/reportDrill";
import type { ColumnDef } from "../components/ColumnTable";
import { formatDate } from "./formatDate";
import type { CostBreakdownBy } from "./costBreakdown";
import { isValidIsoDate } from "./isoDate";
import { formatDays, formatMd, formatPercent, formatRatio, formatSignedMd, formatSignedPercent } from "./reportFormat";

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
 * What a drill is about: one throughput period bucket (`bucket`, by its first day), one sprint's scope bucket (`sprint`), the ACTUAL COST of a
 * throughput period bucket or sprint (`ac-bucket` / `ac-sprint`: the tasks the time was logged on, not the delivered ones), or one
 * bar of a distribution report's histogram (`hist`: `view` names the distribution — the server's own `view` value — and
 * `bucketIndex` the bar, 0-based, the open tail last), or one slice of the Costs donuts (`cost`: the breakdown `by` and the
 * slice's `keys`, several for the folded "Other"), or one sprint's actual-cost bar of the Costs report (`cost-sprint`: the whole bar, or one
 * `epicSegment` of it like a by-epic sprint bar). A sprint is named by its team AND its id: several connections to one Jira
 * site carry the same sprint id under different teams, and a unit-wide chart (Home) draws them all, so the id alone would list every
 * team's items. `segmentAccountId` narrows a stacked bar to one person's share of it. A sprint bar of a by-epic chart (Commitments', Throughput's)
 * carries `epicSegment` — an epic's id, `NO_EPIC` or `OTHER_EPICS` — and, for `OTHER_EPICS`, the chart's own `topEpicIds` (the epics that are NOT the folded rest).
 */
export type DrillTarget =
  | { kind: "bucket"; bucketStart: string; segmentAccountId?: string }
  | { kind: "ac-bucket"; bucketStart: string; segmentAccountId?: string }
  | { kind: "ac-sprint"; teamId: number; sprintId: number; segmentAccountId?: string }
  | { kind: "sprint"; teamId: number; sprintId: number; bucket: SprintBucket; segmentAccountId?: string; epicSegment?: string; topEpicIds?: number[] }
  | { kind: "hist"; view: string; bucketIndex: number; segmentAccountId?: string }
  | { kind: "cost"; by: CostBreakdownBy; keys: string[]; segmentAccountId?: string }
  | { kind: "cost-sprint"; teamId: number; connectionId: number; sprintId: number; epicSegment?: string; topEpicIds?: number[]; segmentAccountId?: string }
  | { kind: "cell"; view: string; segmentAccountId?: string }
  | { kind: "mix"; by: CompareMixBy; key: string; segmentAccountId?: string }
  | { kind: "home"; view: HomeDrillView; teamId: number; segmentAccountId?: string }
  | { kind: "week"; teamId: number; bucketStart: string; segmentAccountId?: string }
  // Person trend's point: one row (its key) at one window (its last day). Never narrowed to a person: the page's filter names the one person.
  | { kind: "point"; row: string; end: string; segmentAccountId?: string };

/** The slices Compare people's work mix groups by — the very filter keys a drill narrows with. */
export type CompareMixBy = "activityType" | "workCategory" | "domain";
const COMPARE_MIX_BYS: readonly CompareMixBy[] = ["activityType", "workCategory", "domain"];

/** The Home overview's targets: one team's figure (`home`) and one team's delivery week (`week`). Neither is ever narrowed to a person. */
export type HomeDrillTarget = Extract<DrillTarget, { kind: "home" }>;
export type WeekDrillTarget = Extract<DrillTarget, { kind: "week" }>;

/** The histogram target by name, for the code that handles only that shape. */
export type HistDrillTarget = Extract<DrillTarget, { kind: "hist" }>;

/** The actual-cost targets of Throughput's two charts, by name, for the code that handles only those shapes. */
export type ActualCostBucketTarget = Extract<DrillTarget, { kind: "ac-bucket" }>;
export type ActualCostSprintTarget = Extract<DrillTarget, { kind: "ac-sprint" }>;

/** The cost-slice target by name, for the code that handles only that shape. */
export type CostDrillTarget = Extract<DrillTarget, { kind: "cost" }>;

/** The Costs sprint targets (a sprint's whole actual-cost bar, or one by-epic segment of it) by name. */
export type CostSprintDrillTarget = Extract<DrillTarget, { kind: "cost-sprint" }>;

/** The targets of Compare people's table (a row's cell, a work-mix key): always one person's, so the segment is part of their identity. */
export type CellDrillTarget = Extract<DrillTarget, { kind: "cell" }>;
export type MixDrillTarget = Extract<DrillTarget, { kind: "mix" }>;

/** The most keys one cost drill carries (the server's bound): "Other" lists every folded key. */
const COST_MAX_KEYS = 100;

/**
 * The unit of a drill list's figure column (each report's own per-item value): MD delivered, a sprint bucket's estimate in MD,
 * the man-days logged in a cost slice, or a distribution's own measure (working days, elapsed days, a ratio, a share, an accuracy ratio, blocked days, a blocked share, a signed estimate change, the times a task was reopened).
 */
export type DrillUnit =
  | "md"
  | "loggedMd"
  | "estimateMd"
  | "wd"
  | "days"
  | "ratio"
  | "share"
  | "accuracy"
  | "blockedWd"
  | "blockedShare"
  | "change"
  | "changes"
  | "heldDays"
  | "reopens"
  | "ageWd"
  | "actualMd"
  | "gapMd";

/**
 * Which instant the date column shows (`anchorAt`): when the item was done, when its sprint was completed, when it was last logged on,
 * when it started or a worklog was logged (Compare people's lists), or none at all (`none`: the list has no date column).
 */
export type DrillAnchor = "done" | "completed" | "logged" | "started" | "worklog" | "none";

const SEGMENT_SEPARATOR = ";s=";

/** A by-epic sprint bar's tail, `:e=<key>` (`:e=OTHER_EPICS,<id>,<id>…` carries the chart's top epics); nothing for a bar of the whole sprint. */
function epicSpelling(target: { epicSegment?: string; topEpicIds?: number[] }): string {
  if (target.epicSegment === undefined) return "";
  return `:e=${[target.epicSegment, ...(target.topEpicIds ?? [])].join(",")}`;
}

const EPIC_SPELLING = /^e=(NO_EPIC|[1-9]\d{0,15}|OTHER_EPICS(?:,[1-9]\d{0,15}){1,6})$/;

/** The epic part of a sprint target (`e=…`) read back: nothing, a segment, or `null` for anything this module did not write. */
function parseEpicSpelling(raw: string | undefined): { epicSegment?: string; topEpicIds?: number[] } | null {
  if (raw === undefined) return {};
  const match = EPIC_SPELLING.exec(raw);
  if (match === null) return null;
  const [epicSegment, ...tops] = match[1].split(",");
  return tops.length === 0 ? { epicSegment } : { epicSegment, topEpicIds: tops.map(Number) };
}

function identityOf(target: DrillTarget): string {
  if (target.kind === "bucket") return `bucket:${target.bucketStart}`;
  if (target.kind === "ac-bucket") return `ac-bucket:${target.bucketStart}`;
  if (target.kind === "ac-sprint") return `ac-sprint:${target.teamId}:${target.sprintId}`;
  if (target.kind === "sprint") return `sprint:${target.teamId}:${target.sprintId}:${target.bucket}${epicSpelling(target)}`;
  // Keys are free text (an activity type may hold a colon or a comma): each is percent-encoded, so ":" and "," stay separators.
  if (target.kind === "cost") return `cost:${target.by}:${target.keys.map(encodeURIComponent).join(",")}`;
  if (target.kind === "cost-sprint") return `cost-sprint:${target.teamId}:${target.connectionId}:${target.sprintId}${epicSpelling(target)}`;
  if (target.kind === "cell") return `cell:${target.view}`;
  if (target.kind === "mix") return `mix:${target.by}:${encodeURIComponent(target.key)}`;
  if (target.kind === "home") return `home:${target.view}:${target.teamId}`;
  if (target.kind === "week") return `week:${target.teamId}:${target.bucketStart}`;
  if (target.kind === "point") return `point:${target.row}:${target.end}`;
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

/**
 * A `cost-sprint:<teamId>:<connectionId>:<sprintId>[:e=<segment>…]` target: a team, the connection the bar was drawn from (a sprint id can exist
 * on two connections), a sprint and, for a by-epic segment, its spelling. Never one person's.
 */
function parseCostSprint(
  parts: [team: string, connection: string | undefined, sprint: string | undefined, epic: string | undefined],
  segmentAccountId: string | undefined,
): DrillTarget | null {
  const [team, connection, sprint, epic] = parts;
  const teamId = parseId(team);
  const connectionId = connection === undefined ? null : parseId(connection);
  const sprintId = sprint === undefined ? null : parseId(sprint);
  const epicParts = parseEpicSpelling(epic);
  return teamId !== null && connectionId !== null && sprintId !== null && epicParts !== null && segmentAccountId === undefined
    ? { kind: "cost-sprint", teamId, connectionId, sprintId, ...epicParts }
    : null;
}

/** A `home:<VIEW>:<teamId>` target: a view the server answers and a real team id. Home's lists are never a person's, so a segment spoils it. */
function parseHome(view: string, team: string | undefined, extra: string | undefined, segmentAccountId: string | undefined): DrillTarget | null {
  const known = HOME_DRILL_VIEWS.find((candidate) => candidate === view);
  const teamId = team === undefined ? null : parseId(team);
  return known !== undefined && teamId !== null && extra === undefined && segmentAccountId === undefined ? { kind: "home", view: known, teamId } : null;
}

/** A `week:<teamId>:<monday>` target: a team and the first day of one delivery week. */
function parseWeek(team: string, bucketStart: string | undefined, extra: string | undefined, segmentAccountId: string | undefined): DrillTarget | null {
  const teamId = parseId(team);
  const valid = bucketStart !== undefined && isValidIsoDate(bucketStart);
  return teamId !== null && valid && extra === undefined && segmentAccountId === undefined ? { kind: "week", teamId, bucketStart } : null;
}

/** A `mix:<by>:<key>` target: a known slice, one non-empty well-encoded key, and the person. */
function parseMix(by: string, key: string | undefined, extra: string | undefined, segmentAccountId: string | undefined): DrillTarget | null {
  const known = COMPARE_MIX_BYS.find((candidate) => candidate === by);
  if (known === undefined || key === undefined || extra !== undefined || segmentAccountId === undefined) return null;
  try {
    const decoded = decodeURIComponent(key);
    return decoded === "" ? null : { kind: "mix", by: known, key: decoded, segmentAccountId };
  } catch {
    return null; // a malformed percent escape
  }
}

const ROW_KEY_PATTERN = /^[a-z][A-Za-z0-9]{0,63}$/;

/** A `point:<rowKey>:<windowEnd>` target (Person trend): a row key as the server spells it and a real day. Never a person's segment: the filter names the person. */
function parsePoint(row: string, end: string | undefined, extra: string | undefined, segmentAccountId: string | undefined): DrillTarget | null {
  const valid = ROW_KEY_PATTERN.test(row) && end !== undefined && isValidIsoDate(end);
  return valid && extra === undefined && segmentAccountId === undefined ? { kind: "point", row, end } : null;
}

/** An `ac-bucket:<monday>` or `ac-sprint:<teamId>:<sprintId>` target (Throughput's actual cost): a real day, or a team and a sprint id, and nothing after. */
function parseActualCost(
  kind: "ac-bucket" | "ac-sprint",
  first: string,
  second: string | undefined,
  third: string | undefined,
  segment: { segmentAccountId?: string },
): DrillTarget | null {
  if (kind === "ac-bucket") return second === undefined && isValidIsoDate(first) ? { kind, bucketStart: first, ...segment } : null;
  const teamId = parseId(first);
  const sprintId = second === undefined ? null : parseId(second);
  return teamId !== null && sprintId !== null && third === undefined ? { kind, teamId, sprintId, ...segment } : null;
}

/** The target a `drill` param spells, or `null` for anything this module did not write (forgiving, like the report filter parser). */
export function parseDrill(raw: string | null): DrillTarget | null {
  if (!raw) return null;
  const at = raw.indexOf(SEGMENT_SEPARATOR);
  const identity = at < 0 ? raw : raw.slice(0, at);
  const segmentAccountId = at < 0 ? undefined : raw.slice(at + SEGMENT_SEPARATOR.length);
  if (segmentAccountId === "") return null;
  const [kind, first, second, third, fourth, ...rest] = identity.split(":");
  // Only a sprint bar has a fourth part (its by-epic segment, or a Costs bar's); `sprint` and `cost-sprint` read it themselves.
  if (rest.length > 0 || !first || (fourth !== undefined && kind !== "sprint" && kind !== "cost-sprint")) return null;
  const segment = segmentAccountId === undefined ? {} : { segmentAccountId };
  if (kind === "bucket") return second === undefined && isValidIsoDate(first) ? { kind, bucketStart: first, ...segment } : null;
  if (kind === "ac-bucket" || kind === "ac-sprint") return parseActualCost(kind, first, second, third, segment);
  if (kind === "sprint") {
    const teamId = parseId(first);
    const sprintId = second === undefined ? null : parseId(second);
    const bucket = SPRINT_BUCKETS.find((candidate) => candidate === third);
    const epic = parseEpicSpelling(fourth);
    return teamId !== null && sprintId !== null && bucket !== undefined && epic !== null ? { kind, teamId, sprintId, bucket, ...epic, ...segment } : null;
  }
  if (kind === "cost") return third === undefined ? parseCost(first, second) : null;
  if (kind === "cost-sprint") return parseCostSprint([first, second, third, fourth], segmentAccountId);
  // Compare people's targets are one person's by definition (the server's `segmentAccountId` is required there): no person, no target.
  if (kind === "cell") {
    return second === undefined && VIEW_PATTERN.test(first) && segmentAccountId !== undefined ? { kind, view: first, segmentAccountId } : null;
  }
  if (kind === "mix") return parseMix(first, second, third, segmentAccountId);
  if (kind === "home") return parseHome(first, second, third, segmentAccountId);
  if (kind === "week") return parseWeek(first, second, third, segmentAccountId);
  if (kind === "point") return parsePoint(first, second, third, segmentAccountId);
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
  changes: formatMd,
  heldDays: formatMd,
  reopens: formatMd,
  ageWd: formatDays,
  actualMd: formatMd,
  gapMd: formatSignedMd,
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
    return { key: "assignee", header: t("reports.drill.columns.authors"), render: (item) => item.assignee ?? t("reports.costs.noAuthor") };
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
  /** How the key cell renders (the drawer passes the Jira link); plain text by default. */
  renderIssueKey: (item: ReportDrillItem) => ReactNode = (item) => item.issueKey,
): ColumnDef<ReportDrillItem>[] {
  const format = FIGURE_FORMATTERS[unit];
  return [
    { key: "issueKey", header: t("reports.drill.columns.issueKey"), render: renderIssueKey },
    { key: "summary", header: t("reports.drill.columns.summary"), render: (item) => item.summary ?? "—" },
    ...(showKind ? [kindColumn(t)] : []),
    peopleColumn(t, unit),
    ...(anchor === "none"
      ? []
      : [{ key: "anchor", header: t(`reports.drill.columns.${anchor}`), render: (item: ReportDrillItem) => formatDate(item.anchorAt, "—", timeZone) }]),
    {
      key: "figure",
      header: t(`reports.drill.columns.figure.${unit}`),
      align: "right",
      render: (item) => (item.figure === null ? "—" : format(item.figure)),
    },
  ];
}
