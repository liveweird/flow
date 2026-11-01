// The drill-to-items API: one wrapper per `/api/v1/reports/<report>/items` path (any-authenticated, read-only, like every
// report). The query string is the PARENT report's filter serialization plus the bar's identity; the response is the same
// report-shaped `{ meta, bar, items, total, truncated }` for every report.

import { jsonRequest } from "./http";
import type { paths } from "./schema";
import type { CodeDrillTarget } from "../utils/codeActivityDrill";
import type {
  ActualCostBucketTarget,
  ActualCostSprintTarget,
  CellDrillTarget,
  CostDrillTarget,
  CostSprintDrillTarget,
  DrillTarget,
  HistDrillTarget,
  MixDrillTarget,
} from "../utils/reportDrill";
import { reportQuery, type ReportFilterState } from "../utils/reportFilter";
import { sprintDrillFilter } from "../utils/sprintDrill";

export type ReportDrillItems = paths["/api/v1/reports/throughput/items"]["get"]["responses"]["200"]["content"]["application/json"];
export type ReportDrillItem = ReportDrillItems["items"][number];

/** The `drill` target kinds that carry their own endpoint arguments. */
type BucketTarget = Extract<DrillTarget, { kind: "bucket" }>;
type SprintTarget = Extract<DrillTarget, { kind: "sprint" }>;

/** A by-epic bar's narrowing: `epicSegment` and, for "Other epics", the chart's top epics as the repeated `topEpicId`. */
function appendEpicSegment(params: URLSearchParams, target: Pick<SprintTarget, "epicSegment" | "topEpicIds">): void {
  if (target.epicSegment === undefined) return;
  params.set("epicSegment", target.epicSegment);
  for (const id of target.topEpicIds ?? []) params.append("topEpicId", String(id));
}

/**
 * The tasks behind one bar of a throughput chart. A period bar (`bucketStart` = the week/month's first day) is asked with the
 * page's own filter; a sprint bar (the sprint chart's delivered items) with the period narrowed to that one sprint and NO
 * `bucketStart` — the server's sprint mode, which drops the activity/category slice the sprint view does not carry.
 */
export async function getThroughputItems(filter: ReportFilterState, target: BucketTarget | SprintTarget): Promise<ReportDrillItems> {
  const params = new URLSearchParams(reportQuery(target.kind === "bucket" ? filter : sprintDrillFilter(filter, target)));
  if (target.kind === "bucket") params.set("bucketStart", target.bucketStart);
  if (target.segmentAccountId) params.set("segmentAccountId", target.segmentAccountId);
  if (target.kind === "sprint") appendEpicSegment(params, target);
  return jsonRequest<ReportDrillItems>(`/api/v1/reports/throughput/items?${params.toString()}`);
}

/**
 * The tasks the ACTUAL COST of one bar of a throughput chart is logged on (`measure=ACTUAL_COST`): the same identity as the delivered
 * drill — a period bar by its `bucketStart` with the page's own filter, a sprint bar by the sprint alone (no `bucketStart`) — one row per task,
 * the figure the MD logged on it inside the bar. At USER level `segmentAccountId` narrows to one person's own logged time.
 */
export async function getThroughputCostItems(filter: ReportFilterState, target: ActualCostBucketTarget | ActualCostSprintTarget): Promise<ReportDrillItems> {
  const params = new URLSearchParams(reportQuery(target.kind === "ac-bucket" ? filter : sprintDrillFilter(filter, target)));
  if (target.kind === "ac-bucket") params.set("bucketStart", target.bucketStart);
  params.set("measure", "ACTUAL_COST");
  if (target.segmentAccountId) params.set("segmentAccountId", target.segmentAccountId);
  return jsonRequest<ReportDrillItems>(`/api/v1/reports/throughput/items?${params.toString()}`);
}

/** The tasks behind one sprint bucket of one sprint — the shared sprint-scope drill (Commitments, Home, Sprint consistency). */
export async function getSprintScopeItems(filter: ReportFilterState, target: SprintTarget): Promise<ReportDrillItems> {
  const params = new URLSearchParams(reportQuery(sprintDrillFilter(filter, target)));
  params.set("bucket", target.bucket);
  if (target.segmentAccountId) params.set("segmentAccountId", target.segmentAccountId);
  appendEpicSegment(params, target);
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

/** The tasks behind one bar of the task-estimates histograms (`AT_DONE` / `AT_START`, fixed 0.5-MD bars); a segment narrows to one picked person. */
export async function getTaskEstimatesItems(filter: ReportFilterState, target: HistDrillTarget): Promise<ReportDrillItems> {
  return jsonRequest<ReportDrillItems>(`/api/v1/reports/task-estimates/items?${histogramQuery(filter, target)}`);
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
 * The items behind one point of a Person trend chart — the page's own filter (the one `accountId` and the range travel in it) plus the row's
 * `view` and the window's last day (`pointEnd`, one of the report's `windows[].end`): Compare people's list for that person over the window.
 */
export async function getPersonTrendItems(filter: ReportFilterState, view: string, pointEnd: string): Promise<ReportDrillItems> {
  const params = new URLSearchParams(reportQuery(filter));
  params.set("view", view);
  params.set("pointEnd", pointEnd);
  return jsonRequest<ReportDrillItems>(`/api/v1/reports/person-trend/items?${params.toString()}`);
}

/**
 * The tasks behind one bar of the Costs charts. A donut slice: the page's own filter plus the breakdown and the slice's keys, repeated
 * — one per folded type for "Other". A sprint's actual-cost bar: the filter with the period narrowed to that one sprint (and its team) and
 * NO `by`; one by-epic segment of it adds `by=EPIC` and `epicSegment` (with the chart's `topEpicId`s for "Other epics"). A sprint id can
 * exist on two connections and the chart draws one bar per (connection, sprint), so a sprint bar also pins the bar's own `connectionId`.
 */
export async function getCostsItems(filter: ReportFilterState, target: CostDrillTarget | CostSprintDrillTarget): Promise<ReportDrillItems> {
  const scoped = target.kind === "cost" ? filter : { ...sprintDrillFilter(filter, target), connectionId: target.connectionId };
  const params = new URLSearchParams(reportQuery(scoped));
  if (target.kind === "cost") {
    params.set("by", target.by);
    for (const key of target.keys) params.append("key", key);
  } else if (target.epicSegment !== undefined) {
    params.set("by", "EPIC");
    appendEpicSegment(params, target);
  }
  return jsonRequest<ReportDrillItems>(`/api/v1/reports/costs/items?${params.toString()}`);
}

/**
 * The commits or merge requests behind one figure of Code activity: a histogram bar (`hist`: its `view` and `bucketIndex`) or a tile, a member's
 * cell or a weekly bar (`code`). The page's own filter travels with it. A member's cell at TEAM level (no picked member) is asked as the USER
 * level with that member as the only pick — the same rows, attributed by the same anchors; at USER level the picks stay and the member is the
 * `segmentAccountId` that narrows them.
 */
export async function getCodeActivityItems(filter: ReportFilterState, target: HistDrillTarget | CodeDrillTarget): Promise<ReportDrillItems> {
  const member = target.kind === "code" && (filter.accountIds ?? []).length === 0 ? target.segmentAccountId : undefined;
  const scoped = member === undefined ? filter : { ...filter, accountIds: [member] };
  const params = new URLSearchParams(reportQuery(scoped));
  params.set("view", target.view);
  if (target.kind === "hist") params.set("bucketIndex", String(target.bucketIndex));
  else if (target.bucketStart !== undefined) params.set("bucketStart", target.bucketStart);
  if (target.segmentAccountId && member === undefined) params.set("segmentAccountId", target.segmentAccountId);
  return jsonRequest<ReportDrillItems>(`/api/v1/reports/code-activity/items?${params.toString()}`);
}
