// The drill-to-items API: one wrapper per `/api/v1/reports/<report>/items` path (any-authenticated, read-only, like every
// report). The query string is the PARENT report's filter serialization plus the bar's identity; the response is the same
// report-shaped `{ meta, bar, items, total, truncated }` for every report.

import { jsonRequest } from "./http";
import type { paths } from "./schema";
import type { CellDrillTarget, CostDrillTarget, DrillTarget, HistDrillTarget, MixDrillTarget } from "../utils/reportDrill";
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

/** The query of a histogram drill: the parent's filter, the distribution (`view`), the bar (`bucketIndex`) and an optional person. */
function histogramQuery(filter: ReportFilterState, target: HistDrillTarget): string {
  const params = new URLSearchParams(reportQuery(filter));
  params.set("view", target.view);
  params.set("bucketIndex", String(target.bucketIndex));
  if (target.segmentAccountId) params.set("segmentAccountId", target.segmentAccountId);
  return params.toString();
}

/** The tasks (or, for an `EPIC_*` view, the epics) behind one bar of a cycle-time histogram. */
export async function getCycleTimeItems(filter: ReportFilterState, target: HistDrillTarget): Promise<ReportDrillItems> {
  return jsonRequest<ReportDrillItems>(`/api/v1/reports/cycle-time/items?${histogramQuery(filter, target)}`);
}

/** The tasks behind one bar of the reported-time ratio or flow-efficiency histogram. */
export async function getReportedTimeItems(filter: ReportFilterState, target: HistDrillTarget): Promise<ReportDrillItems> {
  return jsonRequest<ReportDrillItems>(`/api/v1/reports/reported-time-ratio/items?${histogramQuery(filter, target)}`);
}

/** The tasks behind one bar of the task-accuracy ratio histograms (`AT_START` / `AT_DONE`); a segment narrows to one picked person. */
export async function getTaskAccuracyItems(filter: ReportFilterState, target: HistDrillTarget): Promise<ReportDrillItems> {
  return jsonRequest<ReportDrillItems>(`/api/v1/reports/task-estimation-accuracy/items?${histogramQuery(filter, target)}`);
}

/** The epics behind one bar of the epic-accuracy ratio histograms (`AT_START` / `AT_DONE`). Epics carry no people, so no segment. */
export async function getEpicAccuracyItems(filter: ReportFilterState, target: HistDrillTarget): Promise<ReportDrillItems> {
  return jsonRequest<ReportDrillItems>(`/api/v1/reports/epic-estimation-accuracy/items?${histogramQuery(filter, target)}`);
}

/**
 * The DONE items behind one bar of the blocked-time histograms. The item kind is always sent, like the report's own request does
 * (the server's default, TASK, made explicit), so the list is exactly the population the bar counted.
 */
export async function getBlockedTimeItems(filter: ReportFilterState, target: HistDrillTarget): Promise<ReportDrillItems> {
  return jsonRequest<ReportDrillItems>(
    `/api/v1/reports/blocked-time/items?${histogramQuery({ ...filter, itemKind: filter.itemKind ?? "TASK" }, target)}`,
  );
}

/**
 * The DONE items behind one bar of the estimate-adjustments change histograms (`TASKS` / `EPICS`); a segment narrows the tasks' bar
 * to one picked person.
 */
export async function getEstimateAdjustmentsItems(filter: ReportFilterState, target: HistDrillTarget): Promise<ReportDrillItems> {
  return jsonRequest<ReportDrillItems>(`/api/v1/reports/estimate-adjustments/items?${histogramQuery(filter, target)}`);
}

/**
 * The items behind one person's cell of Compare people's table — the page's own filter (the two `accountId`s travel in it) plus the row's
 * `view` and the person (`segmentAccountId`, required by the server). A histogram bar (`hist`, the two cycle-time views) adds its
 * `bucketIndex`; a work-mix key (`mix`) is the `DELIVERED` cell narrowed by the slice the row groups by (the same filter key the bar offers).
 */
export async function getComparePeopleItems(filter: ReportFilterState, target: HistDrillTarget | CellDrillTarget | MixDrillTarget): Promise<ReportDrillItems> {
  const sliced: ReportFilterState = target.kind === "mix" ? { ...filter, [target.by]: target.key } : filter;
  const params = new URLSearchParams(reportQuery(sliced));
  params.set("view", target.kind === "mix" ? "DELIVERED" : target.view);
  if (target.kind === "hist") params.set("bucketIndex", String(target.bucketIndex));
  if (target.segmentAccountId) params.set("segmentAccountId", target.segmentAccountId);
  return jsonRequest<ReportDrillItems>(`/api/v1/reports/compare-people/items?${params.toString()}`);
}

/**
 * The tasks behind one slice of the cost matrix's activity-type or work-category donut: the page's own filter (without the
 * matrix-only `split`, which the drill does not take) plus the breakdown and the slice's keys, repeated — one per folded type for "Other".
 */
export async function getCostMatrixItems(filter: ReportFilterState, target: CostDrillTarget): Promise<ReportDrillItems> {
  const params = new URLSearchParams(reportQuery({ ...filter, split: undefined }));
  params.set("by", target.by);
  for (const key of target.keys) params.append("key", key);
  return jsonRequest<ReportDrillItems>(`/api/v1/reports/cost-matrix/items?${params.toString()}`);
}
