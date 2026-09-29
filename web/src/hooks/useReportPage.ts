import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { getReportFilters } from "../api/reports";
import { reportQuery, type ReportFilterState } from "../utils/reportFilter";
import { useReportFilter } from "./useReportFilter";

/**
 * The load order every report page shares: `["reports","filters"]` once (staleTime 60 s), then the
 * page's own query keyed `["reports", <report>, <serialized filter>]` — enabled only once the
 * filters loaded (the remembered team is part of the key, so it must be known first) and kept
 * over the previous filter's data (`keepPreviousData`) while a change refetches.
 */
export function useReportPage<T>(report: string, fetchReport: (filter: ReportFilterState) => Promise<T>) {
  const filtersQuery = useQuery({ queryKey: ["reports", "filters"], queryFn: getReportFilters, staleTime: 60_000 });
  const { filter, setFilter } = useReportFilter(filtersQuery.data);
  const query = useQuery({
    queryKey: ["reports", report, reportQuery(filter)],
    queryFn: () => fetchReport(filter),
    enabled: filtersQuery.isSuccess,
    placeholderData: keepPreviousData,
  });
  return { filtersQuery, filters: filtersQuery.data, filter, setFilter, query };
}
