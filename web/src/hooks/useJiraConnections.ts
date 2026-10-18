import { useContext, useMemo, useSyncExternalStore } from "react";
import { QueryClientContext } from "@tanstack/react-query";
import type { ReportFilters } from "../api/reports";

/** The report filters' query key — the ONE cache entry every report page (and the raw-issue inspector) loads. */
const REPORT_FILTERS_KEY = ["reports", "filters"] as const;

const NO_SUBSCRIPTION = () => () => {};

/**
 * The connections (id, name, Jira `siteUrl`) of the report filters ALREADY in the query cache, `undefined` until they are there.
 * A passive read: it never fetches (the page that shows keys loads the filters itself — `useReportPage`, the Deep dive page, the
 * raw-issue inspector), so a key link works in a bare component test and costs no request of its own; it re-renders when the
 * cache entry arrives or changes.
 */
export function useJiraConnections(): ReportFilters["connections"] | undefined {
  const client = useContext(QueryClientContext);
  const subscribe = useMemo(
    () => (client === undefined ? NO_SUBSCRIPTION : (notify: () => void) => client.getQueryCache().subscribe(notify)),
    [client],
  );
  return useSyncExternalStore(subscribe, () => client?.getQueryData<ReportFilters>(REPORT_FILTERS_KEY)?.connections);
}
