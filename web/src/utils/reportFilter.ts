import { isoDateToEpochMillis, epochMillisToIsoDate, isValidIsoDate } from "./isoDate";

/**
 * The reports filter, (de)serialized to the URL search params — the URL is the source of truth
 * (deep-linkable, the `RawIssueInspector` precedent). The parser is deliberately forgiving: an
 * invalid or conflicting param is DROPPED (never sent, so the server's own `400` matrix is not
 * something a hand-edited link can trip over); the server stays the authority on ranges.
 * Mirrors `reports/ReportFilter.kt`'s vocabulary.
 */

export type DomainView = "TASK" | "EPIC";

export type Bucket = "WEEK" | "MONTH";
export const BUCKETS: readonly Bucket[] = ["WEEK", "MONTH"];

/** What the WIP report keys its counts by (report 9); the server defaults to STAGE. */
export type WipBy = "STATUS" | "STAGE" | "COLUMN";
export const WIP_BYS: readonly WipBy[] = ["STAGE", "STATUS", "COLUMN"];

/** Which items the WIP report counts (report 9); the server defaults to TASK. */
export type WipItemKind = "TASK" | "EPIC" | "BOTH";
export const WIP_ITEM_KINDS: readonly WipItemKind[] = ["TASK", "EPIC", "BOTH"];

const DOMAIN_VIEWS: readonly DomainView[] = ["TASK", "EPIC"];

export interface ReportFilterState {
  /** Period — exactly one of `sprintId`, `lastSprints`, `from`/`to` (precedence in that order). */
  from?: string;
  to?: string;
  lastSprints?: number;
  sprintId?: number;
  /** Org drill: `teamId` alone = TEAM level; with `accountId` = USER level. `0` is UNASSIGNED. */
  teamId?: number;
  accountId?: string;
  domainView?: DomainView;
  domain?: string;
  /** An epic's issue key (`FLO-33`) — the epic progress report's EPIC scope; no other report has it. */
  epicId?: string;
  activityType?: string;
  workCategory?: string;
  /** Time resolution of a bucketed series (throughput); the server defaults to WEEK. */
  bucket?: Bucket;
  /** What the WIP report keys its counts by. */
  by?: WipBy;
  /** Which items the WIP report counts. */
  itemKind?: WipItemKind;
  connectionId?: number;
}

/** Every param this module owns — anything else in the query string is left alone. */
const MANAGED_KEYS = [
  "from",
  "to",
  "lastSprints",
  "sprintId",
  "teamId",
  "accountId",
  "domainView",
  "domain",
  "epicId",
  "activityType",
  "workCategory",
  "bucket",
  "by",
  "itemKind",
  "connectionId",
] as const;

/** The managed params only some reports have a control for; the rest (period, team, member, connection) are shared. */
export const REPORT_SPECIFIC_KEYS = [
  "domainView",
  "domain",
  "epicId",
  "activityType",
  "workCategory",
  "bucket",
  "by",
  "itemKind",
] as const;
export type ReportSpecificKey = (typeof REPORT_SPECIFIC_KEYS)[number];

/**
 * A copy of the filter without every report-specific param the page has no control for — everything
 * in `REPORT_SPECIFIC_KEYS` except `keep`. The shared params (period, team, member, connection) always stay.
 */
export function dropReportSpecific(filter: ReportFilterState, keep: ReadonlySet<ReportSpecificKey>): ReportFilterState {
  const next = { ...filter };
  for (const key of REPORT_SPECIFIC_KEYS) {
    if (!keep.has(key)) delete next[key];
  }
  return next;
}

/** True when the query string carries ANY param this module owns (valid or not). */
export function hasReportFilterParams(params: URLSearchParams): boolean {
  return MANAGED_KEYS.some((key) => params.has(key));
}

const MAX_SPAN_DAYS = 1100;
const MAX_LAST_SPRINTS = 52;
const MAX_TEXT = 200;
const DAY_MS = 86_400_000;

function parseInteger(value: string | null, min: number, max = Number.MAX_SAFE_INTEGER): number | undefined {
  if (value == null || !/^\d{1,15}$/.test(value)) return undefined;
  const n = Number(value);
  return n >= min && n <= max ? n : undefined;
}

function parseText(value: string | null): string | undefined {
  return value != null && value.trim() !== "" && value.length <= MAX_TEXT ? value : undefined;
}

function parseDate(value: string | null): string | undefined {
  return value != null && isValidIsoDate(value) ? value : undefined;
}

function parseEnum<T extends string>(value: string | null, allowed: readonly T[]): T | undefined {
  return allowed.includes(value as T) ? (value as T) : undefined;
}

/** A `from`/`to` pair the server would accept: ordered, and no wider than its span cap. */
function validRange(from: string | undefined, to: string | undefined): boolean {
  if (from !== undefined && to !== undefined) {
    const span = (isoDateToEpochMillis(to) - isoDateToEpochMillis(from)) / DAY_MS;
    if (span < 0 || span + 1 > MAX_SPAN_DAYS) return false;
  }
  return true;
}

export function parseReportFilter(params: URLSearchParams): ReportFilterState {
  const filter: ReportFilterState = {};

  const sprintId = parseInteger(params.get("sprintId"), 1);
  const lastSprints = parseInteger(params.get("lastSprints"), 1, MAX_LAST_SPRINTS);
  if (sprintId !== undefined) {
    filter.sprintId = sprintId;
  } else if (lastSprints !== undefined) {
    filter.lastSprints = lastSprints;
  } else {
    const from = parseDate(params.get("from"));
    const to = parseDate(params.get("to"));
    if (validRange(from, to)) {
      if (from !== undefined) filter.from = from;
      if (to !== undefined) filter.to = to;
    }
  }

  const teamId = parseInteger(params.get("teamId"), 0);
  if (teamId !== undefined) {
    filter.teamId = teamId;
    // A user-level read always needs the team whose roster it is read in.
    const accountId = parseText(params.get("accountId"));
    if (accountId !== undefined) filter.accountId = accountId;
  }

  const domainView = parseEnum(params.get("domainView"), DOMAIN_VIEWS);
  if (domainView !== undefined) filter.domainView = domainView;
  const domain = parseText(params.get("domain"));
  if (domain !== undefined) filter.domain = domain;
  const epicId = parseText(params.get("epicId"));
  if (epicId !== undefined) filter.epicId = epicId;
  const activityType = parseText(params.get("activityType"));
  if (activityType !== undefined) filter.activityType = activityType;
  const workCategory = parseText(params.get("workCategory"));
  if (workCategory !== undefined) filter.workCategory = workCategory;
  const bucket = parseEnum(params.get("bucket"), BUCKETS);
  if (bucket !== undefined) filter.bucket = bucket;
  const by = parseEnum(params.get("by"), WIP_BYS);
  if (by !== undefined) filter.by = by;
  const itemKind = parseEnum(params.get("itemKind"), WIP_ITEM_KINDS);
  if (itemKind !== undefined) filter.itemKind = itemKind;
  const connectionId = parseInteger(params.get("connectionId"), 1);
  if (connectionId !== undefined) filter.connectionId = connectionId;

  return filter;
}

/** Writes the filter into `base` (a copy): managed keys are replaced, unrelated ones kept. */
export function applyReportFilter(base: URLSearchParams, filter: ReportFilterState): URLSearchParams {
  const next = new URLSearchParams(base);
  for (const key of MANAGED_KEYS) next.delete(key);
  for (const key of MANAGED_KEYS) {
    const value = filter[key];
    if (value !== undefined && value !== "") next.set(key, String(value));
  }
  return next;
}

/** The canonical (fixed key order) serialization — the page query key and the request query. */
export function serializeReportFilter(filter: ReportFilterState): URLSearchParams {
  return applyReportFilter(new URLSearchParams(), filter);
}

export function reportQuery(filter: ReportFilterState): string {
  return serializeReportFilter(filter).toString();
}

/**
 * Which optional controls a report offers — the bar shows a control ONLY where the report uses
 * it (velocity ignores domain/activity/category, so it passes none). `domainView` is
 * the report's own default view (D3), its presence turns the "delivered in / earned in" toggle on.
 */
export interface ReportControls {
  domainView?: DomainView;
  domain?: boolean;
  activityType?: boolean;
  workCategory?: boolean;
  /** The week/month resolution of a bucketed series (throughput). */
  bucket?: boolean;
  /** What the WIP report keys its counts by (stage, status, board column). */
  wipBy?: boolean;
  /** Which items the WIP report counts (tasks, epics, both). */
  itemKind?: boolean;
  /**
   * The report's aggregate has no team × domain split (the server answers `400` for both together),
   * so the last of the two controls touched wins: picking a team clears the domain and vice versa.
   */
  domainExcludesTeam?: boolean;
  /** The report is "as of now" and ignores the period (aging WIP): no period control at all. */
  noPeriod?: boolean;
  /** The report has no user level (epic progress answers `400` to `accountId`): no member control. */
  noMember?: boolean;
  /** Narrow to one Jira connection (data quality); shown only when there is more than one to choose from. */
  connection?: boolean;
}

/** The filter with ONE key set — or dropped for `null`/`undefined` (a cleared control). */
export function withFilterKey<K extends keyof ReportFilterState>(
  filter: ReportFilterState,
  key: K,
  value: ReportFilterState[K] | null,
): ReportFilterState {
  const next = { ...filter };
  if (value === null || value === undefined) delete next[key];
  else next[key] = value;
  return next;
}

/**
 * True when the WIP report can be keyed by board column: one team's board — never the whole unit,
 * the UNASSIGNED bucket (`teamId=0`) or a domain slice (the server answers `400` for each).
 */
export function wipColumnAvailable(filter: ReportFilterState): boolean {
  return filter.teamId !== undefined && filter.teamId > 0 && filter.domain === undefined;
}

/**
 * The snapshot reports (WIP, backlog) read a daily aggregate with no team × domain split, so the
 * server answers `400` for both together. A pasted link carrying both is read with the team
 * winning: the domain is dropped.
 */
export function dropDomainWithTeam(filter: ReportFilterState): ReportFilterState {
  if (filter.teamId === undefined || filter.domain === undefined) return filter;
  const next = { ...filter };
  delete next.domain;
  return next;
}

/**
 * WIP's whole filter, made consistent: the team wins over a domain, and a board-column keying the
 * filter cannot honour is dropped (so it falls back to the default, STAGE, and — being gone from the
 * URL too — cannot come back by itself when a team is picked later).
 */
export function normalizeWipFilter(filter: ReportFilterState): ReportFilterState {
  const next = dropDomainWithTeam(filter);
  if (next.by !== "COLUMN" || wipColumnAvailable(next)) return next;
  const withoutBy = { ...next };
  delete withoutBy.by;
  return withoutBy;
}

/** The drill level a filter selects — the same rule as the server's. */
export function filterLevel(filter: ReportFilterState): "UNIT" | "TEAM" | "USER" {
  if (filter.teamId === undefined) return "UNIT";
  return filter.accountId === undefined ? "TEAM" : "USER";
}

// ---- the period control -------------------------------------------------------------------

export type DatePreset = "last30" | "last90" | "last365" | "thisMonth" | "lastMonth" | "thisQuarter";
export const DATE_PRESETS: readonly DatePreset[] = [
  "last30",
  "last90",
  "last365",
  "thisMonth",
  "lastMonth",
  "thisQuarter",
];
export const LAST_SPRINT_COUNTS: readonly number[] = [1, 3, 6, 12];

/** What the period `Select` shows: a preset, a custom range, N sprints, or one sprint. */
export type PeriodChoice = DatePreset | "custom" | "sprint" | `lastSprints:${number}`;

function shiftDays(iso: string, days: number): string {
  return epochMillisToIsoDate(isoDateToEpochMillis(iso) + days * DAY_MS);
}

/** A preset's inclusive `[from, to]` as of `today` (UTC calendar, like every date here). */
export function presetRange(preset: DatePreset, today: string): { from: string; to: string } {
  const [year, month] = today.split("-").map(Number);
  const first = (y: number, m: number) => epochMillisToIsoDate(Date.UTC(y, m - 1, 1));
  switch (preset) {
    case "last30":
      return { from: shiftDays(today, -29), to: today };
    case "last90":
      return { from: shiftDays(today, -89), to: today };
    case "last365":
      return { from: shiftDays(today, -364), to: today };
    case "thisMonth":
      return { from: first(year, month), to: today };
    case "lastMonth":
      return { from: first(year, month - 1), to: shiftDays(first(year, month), -1) };
    case "thisQuarter":
      return { from: first(year, Math.floor((month - 1) / 3) * 3 + 1), to: today };
  }
}

/** The choice a filter currently corresponds to. No period params = the server's 90-day default. */
export function activePeriodChoice(filter: ReportFilterState, today: string): PeriodChoice {
  if (filter.sprintId !== undefined) return "sprint";
  if (filter.lastSprints !== undefined) return `lastSprints:${filter.lastSprints}`;
  if (filter.from === undefined && filter.to === undefined) return "last90";
  const to = filter.to ?? today;
  const match = DATE_PRESETS.find((preset) => {
    const range = presetRange(preset, today);
    return range.from === filter.from && range.to === to;
  });
  return match ?? "custom";
}

/** The filter with only its period replaced (other controls untouched). */
export function withPeriod(
  filter: ReportFilterState,
  period: Pick<ReportFilterState, "from" | "to" | "lastSprints" | "sprintId">,
): ReportFilterState {
  const next = { ...filter, ...period };
  if (period.from === undefined) delete next.from;
  if (period.to === undefined) delete next.to;
  if (period.lastSprints === undefined) delete next.lastSprints;
  if (period.sprintId === undefined) delete next.sprintId;
  return next;
}
