import type { UseQueryResult } from "@tanstack/react-query";
import type { ReportFilters } from "../api/reports";
import LoadingBlock from "./LoadingBlock";
import ErrorAlert from "./ErrorAlert";

/** The reference-data query's own load/failure state, shown above a report until it resolves. */
export default function ReportFiltersStatus({ query }: { query: UseQueryResult<ReportFilters> }) {
  if (query.isError) {
    return <ErrorAlert error={query.error} />;
  }
  return query.isPending ? <LoadingBlock /> : null;
}
