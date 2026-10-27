import { useCallback, useEffect, useLayoutEffect, useMemo, useRef } from "react";
import { useLocation, useNavigationType } from "react-router-dom";
import type { ReportFilters } from "../api/reports";
import {
  applyReportFilter,
  domainStandsForTeam,
  hasReportFilterParams,
  parseReportFilter,
  rebaseReportFilter,
  reportQuery,
  requiredTeamId,
  presetRange,
  withPeriod,
  type ReportControls,
  type ReportFilterState,
} from "../utils/reportFilter";
import { todayIsoDate } from "../utils/isoDate";
import {
  dropStaleReferences,
  presetOf,
  readRememberedFilter,
  rememberedQuery,
} from "../utils/rememberedFilter";
import { useLiveSearchParams } from "./useLiveSearchParams";
import { readRememberedQuery, useStoredState, writeRememberedQuery } from "./useStoredState";

const identity = (filter: ReportFilterState) => filter;

/** The filter on `teamId`; a member of the team it replaces is dropped (the roster is another team's). */
function onTeam(filter: ReportFilterState, teamId: number): ReportFilterState {
  const next = { ...filter, teamId };
  if (filter.teamId !== undefined && filter.teamId !== teamId) {
    delete next.accountId;
    delete next.accountIds;
  }
  return next;
}

/**
 * Remembers one page-local switch (`useThroughputView`'s `view`, `useAgingKind`'s `kind`) as the user left it — `value` null is its default,
 * the absent param. Only that param is written, over whatever the screen already remembers: the filter in the URL may have come from a link,
 * and a link is never stored.
 */
export function rememberPageParam(storageKey: string, key: string, value: string | null) {
  const memory = new URLSearchParams(readRememberedQuery(storageKey) ?? "");
  if (value === null) memory.delete(key);
  else memory.set(key, value);
  writeRememberedQuery(storageKey, memory.toString());
}

const isRememberedTeam = (v: unknown): v is number | null => v === null || (typeof v === "number" && v > 0);

/**
 * The report filter as URL state: the search params are the source of truth (deep-linkable), this
 * hook parses them and hands back a setter that writes them. What it adds is MEMORY, in two layers:
 *
 * - The filter the user last set on THIS screen (`storageKey` = `reports.<report>`, stored under `<storageKey>.filter` as the
 *   URL query that page writes). A report opened with NO filter params at all (a bare nav click) restores it: every value is
 *   checked against the reference data (`dropStaleReferences` — a deleted team, a person who left, a vanished sprint or domain is
 *   dropped), run through the page's own `normalize` and `requiredTeamId`, and the URL is rewritten (replace) to say so, so a
 *   link copied from the address bar means what the page shows. A link that carries any filter param is taken as written (deep
 *   links win; a copied unit-level link stays unit-level) and is NOT stored: only the user's own change is — a `setFilter`
 *   call, a page-local switch (`useThroughputView`, `useAgingKind`) or an in-page link that narrows the report (a pushed
 *   location on this page). Back/forward, a normalising replace and a restore never write.
 * - The REMEMBERED team (`useStoredState`, `reports.teamId`, shared by every report, and the only memory that existed before the
 *   per-screen one): a bare report with no remembered filter of its own starts on the last team the user picked, provided the
 *   reference data still lists it. Only a change made through the Team control touches it (picking a team stores it, clearing the
 *   team clears it).
 *
 * `normalize` (a module-level function, so its identity is stable) makes a filter the report can
 * answer out of one it cannot — a pasted link's team AND domain, a column keying without a team.
 * The normalised filter is what the page reads AND what the URL is rewritten to (replace), so a
 * dropped param cannot come back on its own when another control changes later.
 *
 * Every write keeps the current `location.state` (a drill link's hand-off, e.g. the domain an epic
 * was reached through), so a period change or a normalising rewrite does not silently lose it.
 * A `teamRequired` report (`ReportControls.teamRequired`) is never read for the whole unit: a URL that names no team — a bare link, or one with
 * only a period — is given one (`requiredTeamId`: the team listing a linked sprint, else the remembered team, else the first
 * by name) and the URL is rewritten (replace) like the restore case; so is a `teamId` the loaded team list does
 * not know (a deleted team, `0`), whose members are dropped with it. With no team at all the filter stays teamless
 * and the page says so. With `teamOrDomain` a URL naming a domain and no team is left alone (the domain stands in for the team, and
 * the remembered team survives it). `multiMember` makes the URL's repeated `accountId` read as `accountIds`; `peopleOnly` (Compare people, Person trend) reads
 * them with no team at all, and remembers them like any other part of the filter (they are Jira accounts, so a restore keeps them as written).
 * With `epicScope` too (epic progress) a URL naming a single epic stands in for the team the same way.
 * A report with a `defaultPeriod` (Person trend: a year) is given that period when the URL names none, and the URL is rewritten (replace) to say so.
 */
export function useReportFilter(
  filters: ReportFilters | undefined,
  storageKey: string,
  normalize: (filter: ReportFilterState) => ReportFilterState = identity,
  controls: Pick<ReportControls, "teamRequired" | "teamOrDomain" | "epicScope" | "multiMember" | "peopleOnly" | "defaultPeriod"> = {},
): {
  filter: ReportFilterState;
  setFilter: (next: ReportFilterState) => void;
} {
  const [params, setParams, liveUrl] = useLiveSearchParams();
  const location = useLocation();
  const { pathname } = location;
  const navigationType = useNavigationType();
  const [rememberedTeam, setRememberedTeam] = useStoredState<number | null>("reports.teamId", null, isRememberedTeam);
  const teamless = controls.peopleOnly === true;
  const parseOptions = useMemo(() => ({ multiAccount: controls.multiMember === true || teamless, teamless }), [controls.multiMember, teamless]);
  const parsed = useMemo(() => parseReportFilter(params, parseOptions), [params, parseOptions]);

  // A bare visit (no filter param at all) restores the screen's own memory, validated against the reference data once it is loaded.
  const bare = !hasReportFilterParams(params);
  const stored = readRememberedQuery(storageKey);
  const seed = useMemo(() => {
    if (!bare || stored === null || filters === undefined) return undefined;
    const remembered = readRememberedFilter(stored, pathname, parseOptions, todayIsoDate(filters.timeZone));
    return { filter: dropStaleReferences(remembered.filter, filters, teamless), page: remembered.page };
  }, [bare, stored, filters, pathname, parseOptions, teamless]);
  const base = seed?.filter ?? parsed;

  const rememberedListed =
    rememberedTeam !== null && (filters?.teams.some((team) => team.id === rememberedTeam) ?? false) ? rememberedTeam : undefined;
  const requiredTeam =
    controls.teamRequired && filters && !domainStandsForTeam(controls, base)
      ? requiredTeamId(base, filters.teams, rememberedListed)
      : undefined;
  // The shared team stands in for a screen that remembers no filter of its own: no memory at all, or one holding only a page-local switch.
  const rememberedNoFilter = stored === null || (stored !== "" && seed !== undefined && Object.keys(seed.filter).length === 0);
  // A people-only report (Compare people) has no team: neither the shared remembered team nor a required one is applied to it.
  const teamToApply = teamless ? undefined : (bare && rememberedNoFilter ? rememberedListed : undefined) ?? requiredTeam;
  const requested = useMemo(() => (teamToApply === undefined ? base : onTeam(base, teamToApply)), [base, teamToApply]);
  const { defaultPeriod } = controls;
  const timeZone = filters?.timeZone;
  const filter = useMemo(() => {
    const normalized = normalize(requested);
    // A report whose own period is not the server's 90 days (Person trend: a year) states it in the URL once the zone is known, so a link means what the page shows.
    const periodless = normalized.from === undefined && normalized.to === undefined && normalized.lastSprints === undefined && normalized.sprintId === undefined;
    return defaultPeriod !== undefined && timeZone !== undefined && periodless ? withPeriod(normalized, presetRange(defaultPeriod, todayIsoDate(timeZone))) : normalized;
  }, [normalize, requested, defaultPeriod, timeZone]);

  // What the page shows differs from what the URL says (a restore, a required team, a normalised link): say so, by replace.
  const seedPage = useMemo(() => (seed === undefined ? [] : seed.page.filter(([key]) => !params.has(key))), [seed, params]);
  const needsRewrite = reportQuery(filter) !== reportQuery(parsed) || seedPage.length > 0;
  useEffect(() => {
    if (!needsRewrite) return;
    setParams(
      (prev) => {
        const next = applyReportFilter(prev, filter);
        for (const [key, value] of seedPage) next.set(key, value);
        return next;
      },
      { replace: true, state: liveUrl.state() },
    );
  }, [needsRewrite, filter, seedPage, setParams, liveUrl]);

  // An in-page link that narrows the report (a row name, a breadcrumb) is a user change too: a PUSH onto this very page that
  // carries filter params. Arriving here (a tab hop, Home, a pasted link) is not, and neither are Back/forward and replaces.
  /** The memory for a settled filter: its query plus the relative preset the period was chosen through, as of today in the configured zone. */
  const rememberedFor = useCallback(
    (settled: ReportFilterState, from: URLSearchParams) =>
      rememberedQuery(settled, from, pathname, filters === undefined ? undefined : presetOf(settled, todayIsoDate(filters.timeZone))),
    [filters, pathname],
  );
  // Only a push that CHANGED the settled filter is a narrowing: opening a drill (`drill=…`) or flipping a page-local switch pushes onto the
  // same page with the same filter, and must not store the filter a link carried.
  const filterKey = reportQuery(filter);
  const mounted = useRef({ key: location.key, pathname, filterKey });
  useEffect(() => {
    if (mounted.current.key === location.key) return;
    mounted.current.key = location.key;
    const changed = filterKey !== mounted.current.filterKey;
    if (changed && navigationType === "PUSH" && pathname === mounted.current.pathname && hasReportFilterParams(params)) {
      writeRememberedQuery(storageKey, rememberedFor(filter, params));
    }
  }, [location.key, navigationType, pathname, params, filter, filterKey, storageKey, rememberedFor]);
  useEffect(() => {
    mounted.current.filterKey = filterKey;
  }, [filterKey]);

  // The latest filter: the rendered one whenever it changes, else what the last write set (see `useLiveSearchParams` for the URL half).
  const liveFilter = useRef(filter);
  useLayoutEffect(() => {
    liveFilter.current = filter;
  }, [filter]);
  /**
   * `next` was built from the `filter` of the render that handed the control down (the page passes both together). Only the keys it changed
   * from that filter are applied, over the latest filter and the latest URL: a control opened before another change landed (a period
   * picked half a second earlier) cannot overwrite it with the filter it was rendered with. The write also becomes the latest filter at
   * once, so a second write before the navigation commits builds on the first.
   */
  const setFilter = (next: ReportFilterState) => {
    const current = liveFilter.current;
    const merged = rebaseReportFilter(filter, next, current);
    liveFilter.current = normalize(merged);
    // A domain standing in for the team (`teamOrDomain`) does not wipe the memory: clearing the domain returns to that team.
    const teamTouched = merged.teamId !== current.teamId && !domainStandsForTeam(controls, merged);
    if (teamTouched) setRememberedTeam(merged.teamId !== undefined && merged.teamId > 0 ? merged.teamId : null);
    // Written before the navigation, so the bare URL a cleared filter can leave never meets the previous memory.
    writeRememberedQuery(storageKey, rememberedFor(liveFilter.current, liveUrl.params()));
    setParams((prev) => applyReportFilter(prev, merged), { state: liveUrl.state() });
  };

  return { filter, setFilter };
}
