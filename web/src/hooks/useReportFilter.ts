import { useEffect, useMemo } from "react";
import { useLocation, useSearchParams } from "react-router-dom";
import type { ReportFilters } from "../api/reports";
import {
  applyReportFilter,
  hasReportFilterParams,
  parseReportFilter,
  reportQuery,
  requiredTeamId,
  type ReportControls,
  type ReportFilterState,
} from "../utils/reportFilter";
import { useStoredState } from "./useStoredState";

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

const isRememberedTeam = (v: unknown): v is number | null => v === null || (typeof v === "number" && v > 0);

/**
 * The report filter as URL state: the search params are the source of truth (deep-linkable), this
 * hook parses them and hands back a setter that writes them. The one thing it adds is the
 * REMEMBERED team (`useStoredState`, so it survives reports and reloads): a report opened
 * with NO filter params at all (a bare nav click) starts on the last team the user picked,
 * provided the reference data still lists it — and the URL is rewritten (replace) to say so, so
 * a link copied from the address bar means what the page shows. Any link that carries filter
 * params is taken as written: a copied unit-level link with a period stays unit-level. Only a
 * change made through the Team control touches the memory (picking a team stores it, clearing
 * the team clears it); no other filter change does.
 *
 * `normalize` (a module-level function, so its identity is stable) makes a filter the report can
 * answer out of one it cannot — a pasted link's team AND domain, a column keying without a team.
 * The normalised filter is what the page reads AND what the URL is rewritten to (replace), so a
 * dropped param cannot come back on its own when another control changes later.
 *
 * Every write keeps the current `location.state` (a drill link's hand-off, e.g. the domain an epic
 * was reached through), so a period change or a normalising rewrite does not silently lose it.
 * A `teamRequired` report (commitments, throughput, cycle time, reported time, adjustments) is never read for the whole unit: a URL that names no team — a bare link, or one with
 * only a period — is given one (`requiredTeamId`: the team listing a linked sprint, else the remembered team, else the first
 * by name) and the URL is rewritten (replace) like the remembered-team case; so is a `teamId` the loaded team list does
 * not know (a deleted team, `0`), whose members are dropped with it. With no team at all the filter stays teamless
 * and the page says so. `multiMember` makes the URL's repeated `accountId` read as `accountIds`.
 *
 * `clearRememberedTeam` is for a link that must land on the WHOLE UNIT: a bare URL would otherwise
 * re-apply the remembered team, so such a link clears the memory as it is followed.
 */
export function useReportFilter(
  filters: ReportFilters | undefined,
  normalize: (filter: ReportFilterState) => ReportFilterState = identity,
  controls: Pick<ReportControls, "teamRequired" | "multiMember"> = {},
): {
  filter: ReportFilterState;
  setFilter: (next: ReportFilterState) => void;
  clearRememberedTeam: () => void;
} {
  const [params, setParams] = useSearchParams();
  const { state } = useLocation();
  const [rememberedTeam, setRememberedTeam] = useStoredState<number | null>("reports.teamId", null, isRememberedTeam);
  const parseOptions = useMemo(() => ({ multiAccount: controls.multiMember === true }), [controls.multiMember]);
  const parsed = useMemo(() => parseReportFilter(params, parseOptions), [params, parseOptions]);

  const rememberedListed =
    rememberedTeam !== null && (filters?.teams.some((team) => team.id === rememberedTeam) ?? false) ? rememberedTeam : undefined;
  const requiredTeam =
    controls.teamRequired && filters ? requiredTeamId(parsed, filters.teams, rememberedListed) : undefined;
  const teamToApply = (hasReportFilterParams(params) ? undefined : rememberedListed) ?? requiredTeam;
  const requested = useMemo(
    () => (teamToApply === undefined ? parsed : onTeam(parsed, teamToApply)),
    [parsed, teamToApply],
  );
  const filter = useMemo(() => normalize(requested), [normalize, requested]);
  const needsRewrite = reportQuery(filter) !== reportQuery(requested);

  useEffect(() => {
    if (needsRewrite) {
      setParams((prev) => applyReportFilter(prev, normalize(parseReportFilter(prev, parseOptions))), { replace: true, state });
    }
  }, [needsRewrite, normalize, parseOptions, setParams, state]);

  useEffect(() => {
    if (teamToApply !== undefined) {
      setParams((prev) => applyReportFilter(prev, onTeam(parseReportFilter(prev, parseOptions), teamToApply)), { replace: true, state });
    }
  }, [teamToApply, parseOptions, setParams, state]);

  const setFilter = (next: ReportFilterState) => {
    if (next.teamId !== filter.teamId) setRememberedTeam(next.teamId !== undefined && next.teamId > 0 ? next.teamId : null);
    setParams((prev) => applyReportFilter(prev, next), { state });
  };

  const clearRememberedTeam = () => setRememberedTeam(null);

  return { filter, setFilter, clearRememberedTeam };
}
