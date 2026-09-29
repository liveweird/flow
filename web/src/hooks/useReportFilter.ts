import { useEffect, useMemo } from "react";
import { useLocation, useSearchParams } from "react-router-dom";
import type { ReportFilters } from "../api/reports";
import {
  applyReportFilter,
  hasReportFilterParams,
  parseReportFilter,
  reportQuery,
  type ReportFilterState,
} from "../utils/reportFilter";
import { useStoredState } from "./useStoredState";

const identity = (filter: ReportFilterState) => filter;

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
 * `clearRememberedTeam` is for a link that must land on the WHOLE UNIT: a bare URL would otherwise
 * re-apply the remembered team, so such a link clears the memory as it is followed.
 */
export function useReportFilter(
  filters: ReportFilters | undefined,
  normalize: (filter: ReportFilterState) => ReportFilterState = identity,
): {
  filter: ReportFilterState;
  setFilter: (next: ReportFilterState) => void;
  clearRememberedTeam: () => void;
} {
  const [params, setParams] = useSearchParams();
  const { state } = useLocation();
  const [rememberedTeam, setRememberedTeam] = useStoredState<number | null>("reports.teamId", null, isRememberedTeam);
  const parsed = useMemo(() => parseReportFilter(params), [params]);

  const teamToApply =
    !hasReportFilterParams(params) &&
    rememberedTeam !== null &&
    (filters?.teams.some((team) => team.id === rememberedTeam) ?? false)
      ? rememberedTeam
      : undefined;
  const requested = useMemo(
    () => (teamToApply === undefined ? parsed : { ...parsed, teamId: teamToApply }),
    [parsed, teamToApply],
  );
  const filter = useMemo(() => normalize(requested), [normalize, requested]);
  const needsRewrite = reportQuery(filter) !== reportQuery(requested);

  useEffect(() => {
    if (needsRewrite) {
      setParams((prev) => applyReportFilter(prev, normalize(parseReportFilter(prev))), { replace: true, state });
    }
  }, [needsRewrite, normalize, setParams, state]);

  useEffect(() => {
    if (teamToApply !== undefined) {
      setParams((prev) => applyReportFilter(prev, { ...parseReportFilter(prev), teamId: teamToApply }), { replace: true, state });
    }
  }, [teamToApply, setParams, state]);

  const setFilter = (next: ReportFilterState) => {
    if (next.teamId !== filter.teamId) setRememberedTeam(next.teamId !== undefined && next.teamId > 0 ? next.teamId : null);
    setParams((prev) => applyReportFilter(prev, next), { state });
  };

  const clearRememberedTeam = () => setRememberedTeam(null);

  return { filter, setFilter, clearRememberedTeam };
}
