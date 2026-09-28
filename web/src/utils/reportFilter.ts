import { isoDateToEpochMillis, epochMillisToIsoDate, isValidIsoDate } from "./isoDate";

/**
 * The reports filter, (de)serialized to the URL search params — the URL is the source of truth
 * (deep-linkable, the `RawIssueInspector` precedent). The parser is deliberately forgiving: an
 * invalid or conflicting param is DROPPED (never sent, so the server's own `400` matrix is not
 * something a hand-edited link can trip over); the server stays the authority on ranges.
 * Mirrors `reports/ReportFilter.kt`'s vocabulary.
 */

export type DomainView = "TASK" | "EPIC";
export type Breakdown = "NONE" | "DOMAIN" | "ACTIVITY_TYPE" | "WORK_CATEGORY";

const DOMAIN_VIEWS: readonly DomainView[] = ["TASK", "EPIC"];
export const BREAKDOWNS: readonly Breakdown[] = ["NONE", "DOMAIN", "ACTIVITY_TYPE", "WORK_CATEGORY"];

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
  activityType?: string;
  workCategory?: string;
  breakdown?: Breakdown;
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
  "activityType",
  "workCategory",
  "breakdown",
  "connectionId",
] as const;

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
  const activityType = parseText(params.get("activityType"));
  if (activityType !== undefined) filter.activityType = activityType;
  const workCategory = parseText(params.get("workCategory"));
  if (workCategory !== undefined) filter.workCategory = workCategory;
  const breakdown = parseEnum(params.get("breakdown"), BREAKDOWNS);
  if (breakdown !== undefined) filter.breakdown = breakdown;
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
