import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { getReportFilters } from "../api/reports";
import { reportQuery, type ReportControls, type ReportFilterState } from "../utils/reportFilter";
import { useReportFilter } from "./useReportFilter";

/**
 * The load order every report page shares: `["reports","filters"]` once (staleTime 60 s), then the
 * page's own query keyed `["reports", <report>, <serialized filter>]` — enabled only once the
 * filters loaded (the remembered team is part of the key, so it must be known first) and kept
 * over the previous filter's data (`keepPreviousData`) while a change refetches. A `teamRequired` report (commitments, throughput) is also
 * held back while the filter has no team (the unit has none), so it never asks for the whole unit. `storageKey`
 * (`reports.<report>`) is the page's view key for the filter bar's remembered open/closed state.
 */
export function useReportPage<T>(
  report: string,
  fetchReport: (filter: ReportFilterState) => Promise<T>,
  /** Makes an unanswerable filter answerable (see `useReportFilter`); a module-level function. */
  normalize?: (filter: ReportFilterState) => ReportFilterState,
  /** The page's `ReportControls` flags that change how the filter is read (a module-level constant). */
  controls: Pick<ReportControls, "teamRequired" | "multiMember"> = {},
) {
  const filtersQuery = useQuery({ queryKey: ["reports", "filters"], queryFn: getReportFilters, staleTime: 60_000 });
  const { filter, setFilter, clearRememberedTeam } = useReportFilter(filtersQuery.data, normalize, controls);
  const query = useQuery({
    queryKey: ["reports", report, reportQuery(filter)],
    queryFn: () => fetchReport(filter),
    enabled: filtersQuery.isSuccess && (!controls.teamRequired || filter.teamId !== undefined),
    placeholderData: keepPreviousData,
  });
  return {
    filtersQuery,
    filters: filtersQuery.data,
    filter,
    setFilter,
    clearRememberedTeam,
    query,
    storageKey: `reports.${report}`,
  };
}
