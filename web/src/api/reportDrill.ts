// The drill-to-items API: one wrapper per `/api/v1/reports/<report>/items` path (any-authenticated, read-only, like every
// report). The query string is the PARENT report's filter serialization plus the bar's identity; the response is the same
// report-shaped `{ meta, bar, items, total, truncated }` for every report.

import { jsonRequest } from "./http";
import type { paths } from "./schema";
import type { DrillTarget } from "../utils/reportDrill";
import { reportQuery, type ReportFilterState } from "../utils/reportFilter";
import { sprintDrillFilter } from "../utils/sprintDrill";

export type ReportDrillItems = paths["/api/v1/reports/throughput/items"]["get"]["responses"]["200"]["content"]["application/json"];
export type ReportDrillItem = ReportDrillItems["items"][number];

/** The `drill` target kinds that carry their own endpoint arguments. */
type BucketTarget = Extract<DrillTarget, { kind: "bucket" }>;
type SprintTarget = Extract<DrillTarget, { kind: "sprint" }>;

/**
 * The tasks behind one bar of a throughput chart. A period bar (`bucketStart` = the week/month's first day) is asked with the
 * page's own filter; a sprint bar (the sprint chart's delivered items) with the period narrowed to that one sprint and NO
 * `bucketStart` — the server's sprint mode, which drops the activity/category slice the sprint view does not carry.
 */
export async function getThroughputItems(filter: ReportFilterState, target: BucketTarget | SprintTarget): Promise<ReportDrillItems> {
  const params = new URLSearchParams(reportQuery(target.kind === "bucket" ? filter : sprintDrillFilter(filter, target)));
  if (target.kind === "bucket") params.set("bucketStart", target.bucketStart);
  if (target.segmentAccountId) params.set("segmentAccountId", target.segmentAccountId);
  return jsonRequest<ReportDrillItems>(`/api/v1/reports/throughput/items?${params.toString()}`);
}

/** The tasks behind one sprint bucket of one sprint — the shared sprint-scope drill (Commitments, Home, Sprint consistency). */
export async function getSprintScopeItems(filter: ReportFilterState, target: SprintTarget): Promise<ReportDrillItems> {
  const params = new URLSearchParams(reportQuery(sprintDrillFilter(filter, target)));
  params.set("bucket", target.bucket);
  if (target.segmentAccountId) params.set("segmentAccountId", target.segmentAccountId);
  return jsonRequest<ReportDrillItems>(`/api/v1/reports/sprint-consistency/items?${params.toString()}`);
}
