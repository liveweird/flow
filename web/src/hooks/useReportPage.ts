import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { getReportFilters } from "../api/reports";
import { COMPARE_PEOPLE_COUNT, domainStandsForTeam, reportQuery, type ReportControls, type ReportFilterState } from "../utils/reportFilter";
import { useReportFilter } from "./useReportFilter";

/**
 * The load order every report page shares: `["reports","filters"]` once (staleTime 60 s), then the
 * page's own query keyed `["reports", <report>, <serialized filter>]` — enabled only once the
 * filters loaded (the remembered team is part of the key, so it must be known first) and kept
 * over the previous filter's data (`keepPreviousData`) while a change refetches. A `teamRequired` report (`ReportControls.teamRequired`) is also
 * held back while the filter has no team (the unit has none), so it never asks for the whole unit — unless `teamOrDomain` and the filter names a domain;
 * a `peopleOnly` report (Compare people) while it has not exactly two people. `storageKey`
 * (`reports.<report>`) is the page's view key for the filter bar's remembered open/closed state and for the filter the screen was last left on (`useReportFilter`).
 */
export function useReportPage<T>(
  report: string,
  fetchReport: (filter: ReportFilterState) => Promise<T>,
  /** Makes an unanswerable filter answerable (see `useReportFilter`); a module-level function. */
  normalize?: (filter: ReportFilterState) => ReportFilterState,
  /** The page's `ReportControls` flags that change how the filter is read (a module-level constant). */
  controls: Pick<ReportControls, "teamRequired" | "teamOrDomain" | "epicScope" | "multiMember" | "peopleOnly"> = {},
) {
  const filtersQuery = useQuery({ queryKey: ["reports", "filters"], queryFn: getReportFilters, staleTime: 60_000 });
  const storageKey = `reports.${report}`;
  const { filter, setFilter } = useReportFilter(filtersQuery.data, storageKey, normalize, controls);
  const query = useQuery({
    queryKey: ["reports", report, reportQuery(filter)],
    queryFn: () => fetchReport(filter),
    enabled:
      filtersQuery.isSuccess &&
      (!controls.teamRequired || filter.teamId !== undefined || domainStandsForTeam(controls, filter)) &&
      // Compare people is answerable only with both people picked (the server answers one, or three, with a 400).
      (!controls.peopleOnly || filter.accountIds?.length === COMPARE_PEOPLE_COUNT),
    placeholderData: keepPreviousData,
  });
  return {
    filtersQuery,
    filters: filtersQuery.data,
    filter,
    /** `reportQuery(filter)` once the reference data is in, else null: `useReportDrill`'s key, so a team given to a teamless link is not a user change. */
    filterKey: filtersQuery.data === undefined ? null : reportQuery(filter),
    setFilter,
    query,
    storageKey,
  };
}
