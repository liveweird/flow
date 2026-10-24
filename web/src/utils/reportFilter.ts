import { parseBoundedInteger, MAX_TEXT, validRange } from "./filterPrimitives";
import { addDays, epochMillisToIsoDate, isValidIsoDate } from "./isoDate";

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

/** What the epic progress report measures over (A37): the period alone or from the beginning of time; the server defaults per level. */
export type EpicBasis = "PERIOD" | "CUMULATIVE";
export const EPIC_BASES: readonly EpicBasis[] = ["PERIOD", "CUMULATIVE"];

/** What the WIP report keys its counts by (report 9); the server defaults to STAGE. */
export type WipBy = "STATUS" | "STAGE" | "COLUMN";
export const WIP_BYS: readonly WipBy[] = ["STAGE", "STATUS", "COLUMN"];

/**
 * Which items a report counts (WIP, report 9, and blocked time, report 12); the server defaults to TASK. A parsed URL may name any
 * of the three; what a page OFFERS is its `ReportControls.itemKind` list.
 */
export type ReportItemKind = "TASK" | "EPIC" | "BOTH";
const ITEM_KINDS: readonly ReportItemKind[] = ["TASK", "EPIC", "BOTH"];
/** WIP counts tasks or epics, never the two summed (an epic and its tasks in one band count the same work twice): `BOTH` is a 400 there. */
export const WIP_ITEM_KINDS: readonly ReportItemKind[] = ["TASK", "EPIC"];
/** Blocked time (report 12) also reads the two kinds together. */
export const BLOCKED_ITEM_KINDS: readonly ReportItemKind[] = ITEM_KINDS;

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
  /**
   * Several members at once (USER level, and only on the reports that set `ReportControls.multiMember`): REPEATED `accountId` URL keys, distinct,
   * at most `MAX_ACCOUNTS`, in the order picked. Only a multi-member page parses them (`parseReportFilter`'s `multiAccount`);
   * everywhere else a repeated `accountId` reads as its first value, as ever, and this stays unset.
   */
  accountIds?: string[];
  domainView?: DomainView;
  domain?: string;
  /** An epic's issue key (`FLO-33`) — the epic progress report's EPIC scope; no other report has it. */
  epicId?: string;
  activityType?: string;
  workCategory?: string;
  /** Time resolution of a bucketed series (throughput); the server defaults to WEEK. */
  bucket?: Bucket;
  /** What the epic progress report measures over; absent = the server's per-level default (PERIOD, but CUMULATIVE for one epic). */
  basis?: EpicBasis;
  /** What the WIP report keys its counts by. */
  by?: WipBy;
  /** Which items the WIP and blocked-time reports count. */
  itemKind?: ReportItemKind;
  connectionId?: number;
  /**
   * The share (integer 0..25) of the largest values the distribution reports fold into their histogram's final open
   * bucket. Only the DEFAULT_TAIL_PERCENT's absence is canonical: 5 is never stored, serialized or sent (the server's default).
   */
  tailPercent?: number;
  /**
   * The estimated backlog's own staleness window in days (integer 0..3650): only items touched within it are counted. `0` = no limit
   * (sent as is); absent = the page default, `DEFAULT_BACKLOG_STALENESS_DAYS`, applied when the request is made and never stored.
   * The page keeps it on one of `BACKLOG_STALENESS_STEPS` (`snapBacklogStaleness`).
   */
  touchedWithinDays?: number;
}

/** The server's default `tailPercent`: left out of the URL, the request and the query key. */
export const DEFAULT_TAIL_PERCENT = 5;
/** The largest `tailPercent` the server accepts. */
const MAX_TAIL_PERCENT = 25;
/** What the long-tail control offers (the default included); a hand-edited URL may carry any other integer in range. */
export const TAIL_PERCENT_CHOICES: readonly number[] = [0, 1, 2, 5, 10, 15, 20, 25];

/** The staleness windows (days) of the estimated backlog's slider, shortest first; the slider's last stop is "no limit" (`BACKLOG_STALENESS_OFF`). */
export const BACKLOG_STALENESS_STEPS = [7, 14, 30, 60, 90, 180, 365] as const;
/** The window the estimated backlog page asks for when the URL names none: sent explicitly, never in the URL. */
export const DEFAULT_BACKLOG_STALENESS_DAYS = 90;
/** `touchedWithinDays=0`: no staleness filter. */
export const BACKLOG_STALENESS_OFF = 0;
/** The largest `touchedWithinDays` the server accepts. */
const MAX_BACKLOG_STALENESS_DAYS = 3650;

/** The slider step nearest to `days` (a tie goes to the larger); `0` (no limit) stays `0`. */
export function snapToStalenessStep(days: number): number {
  if (days === BACKLOG_STALENESS_OFF) return BACKLOG_STALENESS_OFF;
  return BACKLOG_STALENESS_STEPS.reduce((best, step) => {
    const gap = Math.abs(step - days);
    const bestGap = Math.abs(best - days);
    return gap < bestGap || (gap === bestGap && step > best) ? step : best;
  });
}

/**
 * The backlog filter with a hand-edited `touchedWithinDays` made one of the slider's stops: snapped to the nearest step, and the
 * default (90) dropped so it stays absent — the URL is rewritten to say so (a `normalize` step for `useReportPage`).
 */
export function snapBacklogStaleness(filter: ReportFilterState): ReportFilterState {
  const days = filter.touchedWithinDays;
  if (days === undefined) return filter;
  const snapped = snapToStalenessStep(days);
  if (snapped === days && snapped !== DEFAULT_BACKLOG_STALENESS_DAYS) return filter;
  const next = { ...filter };
  if (snapped === DEFAULT_BACKLOG_STALENESS_DAYS) delete next.touchedWithinDays;
  else next.touchedWithinDays = snapped;
  return next;
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
  "basis",
  "by",
  "itemKind",
  "connectionId",
  "tailPercent",
  "touchedWithinDays",
] as const;

/** The managed params only some reports have a control for; the rest (period, team, member, connection) are shared. */
export const REPORT_SPECIFIC_KEYS = [
  "domainView",
  "domain",
  "epicId",
  "activityType",
  "workCategory",
  "bucket",
  "basis",
  "by",
  "itemKind",
  "tailPercent",
  "touchedWithinDays",
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

const MAX_LAST_SPRINTS = 52;
/** How many members a multi-member report takes (the server's cap on repeated `accountId`). */
export const MAX_ACCOUNTS = 8;

/** The distinct, non-blank values of a repeated param, in order, at most `MAX_ACCOUNTS` (extras dropped, never sent). */
function distinctAccounts(values: readonly string[]): string[] {
  const picked: string[] = [];
  for (const value of values) {
    const text = parseText(value);
    if (text !== undefined && !picked.includes(text)) picked.push(text);
  }
  return picked.slice(0, MAX_ACCOUNTS);
}

/**
 * What a page opts into when reading the URL — only the `MULTI_ACCOUNT_PATHS` pages (`reportLinks.ts`) read several members, and only
 * Compare people (`teamless`, `ReportControls.peopleOnly`) reads them WITHOUT a team: its people are read across every team.
 */
export interface ReportFilterParseOptions {
  multiAccount?: boolean;
  teamless?: boolean;
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

export function parseReportFilter(params: URLSearchParams, options: ReportFilterParseOptions = {}): ReportFilterState {
  const filter: ReportFilterState = {};

  const sprintId = parseBoundedInteger(params.get("sprintId"), 1);
  const lastSprints = parseBoundedInteger(params.get("lastSprints"), 1, MAX_LAST_SPRINTS);
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

  const teamId = parseBoundedInteger(params.get("teamId"), 0);
  if (teamId !== undefined) filter.teamId = teamId;
  // A user-level read needs the team whose roster it is read in — except Compare people's `teamless` read of two people across every team.
  if (teamId !== undefined || options.teamless) {
    if (options.multiAccount || options.teamless) {
      const accountIds = distinctAccounts(params.getAll("accountId"));
      if (accountIds.length > 0) filter.accountIds = accountIds;
    } else {
      const accountId = parseText(params.get("accountId"));
      if (accountId !== undefined) filter.accountId = accountId;
    }
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
  const basis = parseEnum(params.get("basis"), EPIC_BASES);
  if (basis !== undefined) filter.basis = basis;
  const by = parseEnum(params.get("by"), WIP_BYS);
  if (by !== undefined) filter.by = by;
  const itemKind = parseEnum(params.get("itemKind"), ITEM_KINDS);
  if (itemKind !== undefined) filter.itemKind = itemKind;
  const connectionId = parseBoundedInteger(params.get("connectionId"), 1);
  if (connectionId !== undefined) filter.connectionId = connectionId;
  const tailPercent = parseBoundedInteger(params.get("tailPercent"), 0, MAX_TAIL_PERCENT);
  if (tailPercent !== undefined && tailPercent !== DEFAULT_TAIL_PERCENT) filter.tailPercent = tailPercent;
  const touchedWithinDays = parseBoundedInteger(params.get("touchedWithinDays"), 0, MAX_BACKLOG_STALENESS_DAYS);
  if (touchedWithinDays !== undefined) filter.touchedWithinDays = touchedWithinDays;

  return filter;
}

/** Writes the filter into `base` (a copy): managed keys are replaced, unrelated ones kept. */
export function applyReportFilter(base: URLSearchParams, filter: ReportFilterState): URLSearchParams {
  const next = new URLSearchParams(base);
  for (const key of MANAGED_KEYS) next.delete(key);
  for (const key of MANAGED_KEYS) {
    const value = filter[key];
    if (value !== undefined && value !== "") next.set(key, String(value));
    // The members of a multi-member page ride right after the single `accountId` slot: repeated keys, distinct, capped.
    if (key === "accountId") for (const id of distinctAccounts(filter.accountIds ?? [])) next.append(key, id);
  }
  return next;
}

const sameValue = (a: unknown, b: unknown): boolean =>
  Array.isArray(a) && Array.isArray(b) ? a.length === b.length && a.every((item, i) => item === b[i]) : a === b;

/**
 * `current` with only what a control changed applied: every key whose value in `next` differs from `base` (the filter the control was
 * rendered from) is set on a copy of `current`, or deleted when `next` lacks it; keys the control did not touch keep `current`'s value.
 * A write from a control of an older render (its `base` is stale) therefore cannot undo what a newer change put in `current`. Coupled
 * keys move together because each is in the diff (a period pick sets `from`/`to` and drops `lastSprints`/`sprintId`).
 * Limits: a coupled key the stale control did not diff stays (a team switch from a base without a member keeps a member picked since;
 * a report's `normalize`/`dropStaleReferences` cleans it where it uses them), an array (`accountIds`) is replaced whole, and a reset to `{}`
 * from a stale base keeps the keys set since that render.
 */
export function rebaseReportFilter(base: ReportFilterState, next: ReportFilterState, current: ReportFilterState): ReportFilterState {
  const merged: Record<string, unknown> = { ...current };
  const keys = new Set([...Object.keys(base), ...Object.keys(next)]) as Set<keyof ReportFilterState>;
  for (const key of keys) {
    if (sameValue(base[key], next[key])) continue;
    if (next[key] === undefined) delete merged[key];
    else merged[key] = next[key];
  }
  return merged as ReportFilterState;
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
 * it (commitments ignores domain/activity/category, so it passes none). `domainView` is
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
  /** The item kinds the report offers in its Count control (WIP: tasks and epics; blocked time: and both); absent = no control. */
  itemKind?: readonly ReportItemKind[];
  /**
   * The report's aggregate has no team × domain split (the server answers `400` for both together),
   * so the last of the two controls touched wins: picking a team clears the domain and vice versa.
   */
  domainExcludesTeam?: boolean;
  /** The report is "as of now" and ignores the period (aging WIP): no period control at all. */
  noPeriod?: boolean;
  /** The report is read for the whole team, never one member (epic progress answers `400` to `accountId`; sprint consistency): no member control. */
  noMember?: boolean;
  /** Narrow to one Jira connection (data quality); shown only when there is more than one to choose from. */
  connection?: boolean;
  /**
   * The report cannot answer for the whole unit (commitments' capacity and per-user split are team figures, and so are the per-person splits of the `multiMember` reports): the team
   * control is not clearable and offers no "All teams", and a URL without a team is normalized to one
   * (`requiredTeamId`, applied by `useReportFilter`).
   */
  teamRequired?: boolean;
  /**
   * With `teamRequired` + `domainExcludesTeam` (WIP): the report needs exactly ONE of a specific team or a specific domain, never neither
   * (the daily aggregate has no whole-unit view here and no team × domain split). A picked domain stands in for the team — the team
   * control then shows no team and is still not clearable — and clearing the domain falls back to the default team (`requiredTeamId`).
   */
  teamOrDomain?: boolean;
  /**
   * With `teamOrDomain` (epic progress): a single epic in the URL (`epicId`, reached from the report's own drills) is a third scope that stands in for the team, so
   * the single-epic view is answerable without one; the epic is the more specific scope and the page's normaliser drops a team or domain beside it.
   */
  epicScope?: boolean;
  /** The member control picks up to `MAX_ACCOUNTS` people (`ReportFilterState.accountIds`) instead of one (the reports in `MULTI_ACCOUNT_PATHS`, `reportLinks.ts`). */
  multiMember?: boolean;
  /**
   * Compare people: two people read across every team over a date range. The bar has no team, member or sprint-period control (the people
   * are picked beside it, `ComparePeoplePickers`), the repeated `accountId` is read WITHOUT a team (`parseReportFilter`'s `teamless`),
   * and the page is requested only with exactly `COMPARE_PEOPLE_COUNT` of them.
   */
  peopleOnly?: boolean;
}

/** How many people Compare people reads: exactly two, side A then side B. */
export const COMPARE_PEOPLE_COUNT = 2;

/** True while a `teamOrDomain` report is read for a domain alone (or, with `epicScope`, for one epic): the (required) team is legitimately absent. */
export function domainStandsForTeam(controls: Pick<ReportControls, "teamOrDomain" | "epicScope">, filter: ReportFilterState): boolean {
  if (controls.teamOrDomain !== true || filter.teamId !== undefined) return false;
  return filter.domain !== undefined || (controls.epicScope === true && filter.epicId !== undefined);
}

/**
 * The team a `teamRequired` report starts on when the URL names none: the team that lists the linked sprint, else the
 * remembered team (when the reference data still lists it), else the first team by name. `undefined` when the
 * filter already has a team the reference data lists, or there is no team at all. A `teamId` the list does not know (a
 * deleted team, or `0` = unassigned, which a team-only report cannot read) counts as ABSENT. `teams` must be the loaded list:
 * called before it loads, every team would look unknown.
 */
export function requiredTeamId(
  filter: ReportFilterState,
  teams: ReadonlyArray<{ id: number; name: string; sprints: ReadonlyArray<{ sprintId: number }> }>,
  remembered: number | undefined,
): number | undefined {
  if (filter.teamId !== undefined && teams.some((team) => team.id === filter.teamId)) return undefined;
  const listingSprint =
    filter.sprintId === undefined ? undefined : teams.find((team) => team.sprints.some((s) => s.sprintId === filter.sprintId));
  if (listingSprint) return listingSprint.id;
  if (remembered !== undefined && teams.some((team) => team.id === remembered)) return remembered;
  return [...teams].sort((a, b) => a.name.localeCompare(b.name) || a.id - b.id)[0]?.id;
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
 * The filter of a `noMember` report that is ALWAYS read for every member of the team: no `accountId` (one) nor `accountIds` (several),
 * so a pasted link's member is dropped and the URL rewritten to say so (a `normalize` for `useReportPage`).
 */
export function dropMember(filter: ReportFilterState): ReportFilterState {
  if (filter.accountId === undefined && filter.accountIds === undefined) return filter;
  const next = { ...filter };
  delete next.accountId;
  delete next.accountIds;
  return next;
}

/**
 * WIP's whole filter, made consistent: the team wins over a domain, an `itemKind=BOTH` (no longer a WIP kind, the server answers `400`)
 * falls back to the default, TASK, and a board-column keying the
 * filter cannot honour is dropped (so it falls back to the default, STAGE, and — being gone from the
 * URL too — cannot come back by itself when a team is picked later). (Picked people need no rule here: the parser
 * only reads `accountId`s under a `teamId`, so a domain read never carries one.)
 */
export function normalizeWipFilter(filter: ReportFilterState): ReportFilterState {
  const next = dropDomainWithTeam(filter);
  const dropKind = next.itemKind !== undefined && !WIP_ITEM_KINDS.includes(next.itemKind);
  const dropBy = next.by === "COLUMN" && !wipColumnAvailable(next);
  if (!dropKind && !dropBy) return next;
  const trimmed = { ...next };
  if (dropKind) delete trimmed.itemKind;
  if (dropBy) delete trimmed.by;
  return trimmed;
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

/** A preset's inclusive `[from, to]` as of `today` (UTC calendar, like every date here). */
export function presetRange(preset: DatePreset, today: string): { from: string; to: string } {
  const [year, month] = today.split("-").map(Number);
  const first = (y: number, m: number) => epochMillisToIsoDate(Date.UTC(y, m - 1, 1));
  switch (preset) {
    case "last30":
      return { from: addDays(today, -29), to: today };
    case "last90":
      return { from: addDays(today, -89), to: today };
    case "last365":
      return { from: addDays(today, -364), to: today };
    case "thisMonth":
      return { from: first(year, month), to: today };
    case "lastMonth":
      return { from: first(year, month - 1), to: addDays(first(year, month), -1) };
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
