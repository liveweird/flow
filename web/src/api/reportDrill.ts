// The drill-to-items API: one wrapper per `/api/v1/reports/<report>/items` path (any-authenticated, read-only, like every
// report). The query string is the PARENT report's filter serialization plus the bar's identity; the response is the same
// report-shaped `{ meta, bar, items, total, truncated }` for every report.

import { jsonRequest } from "./http";
import type { paths } from "./schema";
import type { DrillTarget } from "../utils/reportDrill";
import { reportQuery, type ReportFilterState } from "../utils/reportFilter";

export type ReportDrillItems = paths["/api/v1/reports/throughput/items"]["get"]["responses"]["200"]["content"]["application/json"];
export type ReportDrillItem = ReportDrillItems["items"][number];

/** The tasks behind one bar of the throughput period chart (`bucketStart` = the week/month's first day). */
export async function getThroughputItems(filter: ReportFilterState, target: DrillTarget): Promise<ReportDrillItems> {
  const params = new URLSearchParams(reportQuery(filter));
  params.set("bucketStart", target.bucketStart);
  if (target.segmentAccountId) params.set("segmentAccountId", target.segmentAccountId);
  return jsonRequest<ReportDrillItems>(`/api/v1/reports/throughput/items?${params.toString()}`);
}
