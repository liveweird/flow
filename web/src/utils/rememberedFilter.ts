import type { ReportFilters } from "../api/reports";
import { pageParamsOf, reportSpecificKeep } from "./reportLinks";
import {
  DATE_PRESETS,
  activePeriodChoice,
  dropReportSpecific,
  parseReportFilter,
  presetRange,
  serializeReportFilter,
  withPeriod,
  type DatePreset,
  type ReportFilterParseOptions,
  type ReportFilterState,
} from "./reportFilter";

/**
 * The pure half of "remember every filter a user sets, per screen" (`hooks/useReportFilter.ts` is the hook half, `readRememberedQuery`/`writeRememberedQuery` in `hooks/useStoredState.ts` the storage).
 * What is remembered is the screen's URL filter as that page writes it — a canonical query string of the managed filter params
 * plus the page-local view switches (`view`, `kind`) — never a second schema, so a restore is just "parse it like a link".
 * A period chosen through a relative preset ("Last 30 days", "This month", …) is remembered SYMBOLICALLY: the remembered query carries a
 * reserved `preset=<id>` key beside the dates it was written as, which never reaches the URL or the server — a restore re-resolves it
 * against today (`presetRange`), so a restore days later is still "Last 30 days", not a stale "Custom" range. A custom range stays absolute.
 * `drill` (an open bar) and `epicId` (an epic progress scope reached by drilling) are no filters and are never kept.
 */

/** The reserved key of the remembered query that names the relative preset the period was chosen through. */
const PRESET_KEY = "preset";

/** The relative preset the filter's explicit dates correspond to as of `today`, if any (the default period has no dates and is nothing to remember). */
export function presetOf(filter: ReportFilterState, today: string): DatePreset | undefined {
  if (filter.from === undefined && filter.to === undefined) return undefined;
  const choice = activePeriodChoice(filter, today);
  return DATE_PRESETS.find((preset) => preset === choice);
}

/**
 * The query string to remember for a report page: its settled filter, minus what the page has no control for, plus its page-local switches read from `params`,
 * plus the relative `preset` the period was chosen through (`presetOf`; none when it is a custom range).
 */
export function rememberedQuery(filter: ReportFilterState, params: URLSearchParams, path: string, preset?: DatePreset): string {
  const own = dropReportSpecific(filter, reportSpecificKeep(path));
  delete own.epicId;
  const query = serializeReportFilter(own);
  if (preset !== undefined && own.from !== undefined) query.set(PRESET_KEY, preset);
  for (const { key, value } of pageParamsOf(path)) {
    if (params.get(key) === value) query.set(key, value);
  }
  return query.toString();
}

/** What a remembered query gives back: the filter (as a link would parse), and the page-local params it carried. */
export interface RememberedFilter {
  filter: ReportFilterState;
  page: ReadonlyArray<readonly [string, string]>;
}

/**
 * Parses a remembered query like a link would be, keeping only what the page at `path` reads and its one valid value per page-local switch.
 * With `today` (the configured zone's date) a recorded relative preset is re-resolved to today's range; without it the dates stay as written.
 */
export function readRememberedFilter(stored: string, path: string, options: ReportFilterParseOptions, today?: string): RememberedFilter {
  const params = new URLSearchParams(stored);
  let filter = dropReportSpecific(parseReportFilter(params, options), reportSpecificKeep(path));
  delete filter.epicId;
  const preset = DATE_PRESETS.find((candidate) => candidate === params.get(PRESET_KEY));
  if (preset !== undefined && today !== undefined && filter.from !== undefined) filter = withPeriod(filter, presetRange(preset, today));
  const page = pageParamsOf(path).flatMap(({ key, value }) => (params.get(key) === value ? [[key, value] as const] : []));
  return { filter, page };
}

/**
 * The filter with every reference the current data no longer knows dropped — a deleted team (and its people), a person who left the
 * team, a sprint the team no longer lists, an unknown domain, activity type, work category or connection. The unassigned bucket
 * (`teamId=0`) is a real value without a roster. A dropped period falls back to the report's default.
 */
export function dropStaleReferences(filter: ReportFilterState, reference: ReportFilters, teamless = false): ReportFilterState {
  const next = { ...filter };
  // Compare people's people are read across every team: they are Jira accounts, not a team's roster, so there is no roster to check them against.
  if (teamless) {
    delete next.teamId;
    delete next.accountId;
    return dropStaleSlices(next, reference);
  }
  const team = filter.teamId === undefined ? undefined : reference.teams.find((candidate) => candidate.id === filter.teamId);
  if (filter.teamId !== undefined && filter.teamId !== 0 && team === undefined) delete next.teamId;
  const members = new Set(team?.members.map((member) => member.accountId));
  if (next.teamId === undefined || (next.accountId !== undefined && !members.has(next.accountId))) delete next.accountId;
  if (next.teamId === undefined) delete next.accountIds;
  else if (next.accountIds !== undefined) {
    const kept = next.accountIds.filter((id) => members.has(id));
    if (kept.length === 0) delete next.accountIds;
    else next.accountIds = kept;
  }
  if (next.sprintId !== undefined) {
    const sprintId = next.sprintId;
    // A sprint remembered beside a team is that team's: it goes with a team the data no longer has.
    const pool = filter.teamId === undefined ? reference.teams : team === undefined ? [] : [team];
    if (!pool.some((candidate) => candidate.sprints.some((sprint) => sprint.sprintId === sprintId))) delete next.sprintId;
  }
  return dropStaleSlices(next, reference);
}

/** The filter without the domain, activity type, work category or connection the reference data no longer lists. */
function dropStaleSlices(next: ReportFilterState, reference: ReportFilters): ReportFilterState {
  if (next.domain !== undefined && !reference.domains.some((domain) => domain.domainKey === next.domain)) delete next.domain;
  if (next.activityType !== undefined && !reference.activityTypes.includes(next.activityType)) delete next.activityType;
  if (next.workCategory !== undefined && !reference.workCategories.includes(next.workCategory)) delete next.workCategory;
  if (next.connectionId !== undefined && !reference.connections.some((connection) => connection.id === next.connectionId)) {
    delete next.connectionId;
  }
  return next;
}
