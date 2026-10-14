import type { ParseKeys } from "i18next";
import { REPORT_SPECIFIC_KEYS, type ReportSpecificKey } from "./reportFilter";

/** The ONE place the reports route family is spelled out — never hand-assemble a report URL. */
export const commitmentsPath = "/reports/commitments";
export const throughputPath = "/reports/throughput";
export const sprintConsistencyPath = "/reports/sprint-consistency";
export const cycleTimePath = "/reports/cycle-time";
export const taskAccuracyPath = "/reports/task-estimation-accuracy";
const epicAccuracyPath = "/reports/epic-estimation-accuracy";
const estimateAdjustmentsPath = "/reports/estimate-adjustments";
const reportedTimePath = "/reports/reported-time-ratio";
export const wipPath = "/reports/wip";
export const backlogPath = "/reports/backlog";
export const agingWipPath = "/reports/aging-wip";
const blockedTimePath = "/reports/blocked-time";
export const epicProgressPath = "/reports/epic-progress";
export const dataQualityPath = "/reports/data-quality";
export const costMatrixPath = "/reports/cost-matrix";
/** The Deep dive's own route; its selection params are `utils/deepDiveFilter.ts`'s, never `ReportFilterState`'s. */
export const deepDiveBasePath = "/reports/deep-dive";

export type ReportTabDef = { to: string; label: ParseKeys };

/** The Delivery group's tabs (plan §8: commitments · throughput · sprint consistency · cycle time). */
export const DELIVERY_TABS: ReadonlyArray<ReportTabDef> = [
  { to: commitmentsPath, label: "reports.tabs.commitments" },
  { to: throughputPath, label: "reports.tabs.throughput" },
  { to: sprintConsistencyPath, label: "reports.tabs.sprintConsistency" },
  { to: cycleTimePath, label: "reports.tabs.cycleTime" },
];

/** The Estimation group's tabs (task accuracy · epic accuracy · adjustments · reported time). */
export const ESTIMATION_TABS: ReadonlyArray<ReportTabDef> = [
  { to: taskAccuracyPath, label: "reports.tabs.taskAccuracy" },
  { to: epicAccuracyPath, label: "reports.tabs.epicAccuracy" },
  { to: estimateAdjustmentsPath, label: "reports.tabs.adjustments" },
  { to: reportedTimePath, label: "reports.tabs.reportedTime" },
];

/** The Flow group's tabs (WIP · estimated backlog · aging WIP · blocked time · epic progress). */
export const FLOW_TABS: ReadonlyArray<ReportTabDef> = [
  { to: wipPath, label: "reports.tabs.wip" },
  { to: backlogPath, label: "reports.tabs.backlog" },
  { to: agingWipPath, label: "reports.tabs.agingWip" },
  { to: blockedTimePath, label: "reports.tabs.blockedTime" },
  { to: epicProgressPath, label: "reports.tabs.epicProgress" },
];

/**
 * The report-specific params each report has a control for. `tailPercent` (the long-tail control above the histograms) belongs
 * to the seven reports whose response carries a `Distribution`: task/epic accuracy, adjustments, cycle time, reported time,
 * blocked time and data quality. Switching tabs must not carry a param
 * the target report cannot show or clear (a hidden `domain` filter silently narrowing Commitments).
 */
const REPORT_SPECIFIC_PARAMS: Readonly<Record<string, readonly ReportSpecificKey[]>> = {
  [commitmentsPath]: [],
  [throughputPath]: ["domainView", "domain", "activityType", "workCategory", "bucket"],
  [sprintConsistencyPath]: [],
  [taskAccuracyPath]: ["domainView", "domain", "activityType", "workCategory", "tailPercent"],
  // Epics carry no activity type, and their domain is their own space under either view.
  [epicAccuracyPath]: ["domain", "workCategory", "tailPercent"],
  [estimateAdjustmentsPath]: ["domainView", "domain", "activityType", "workCategory", "tailPercent"],
  // Cycle time trends by week/month; reported time is a plain distribution pair.
  [cycleTimePath]: ["domainView", "domain", "activityType", "workCategory", "bucket", "tailPercent"],
  [reportedTimePath]: ["domainView", "domain", "activityType", "workCategory", "tailPercent"],
  // The snapshot reports read a task's own domain (no domain view) and answer 400 to an activity
  // type or work category; WIP adds what it counts and how it keys the counts.
  [wipPath]: ["domain", "by", "itemKind"],
  [backlogPath]: ["domain"],
  // Aging WIP slices like the fact reports but has no period; blocked time also counts tasks, epics or both.
  [agingWipPath]: ["domain", "activityType", "workCategory"],
  [blockedTimePath]: ["domain", "activityType", "workCategory", "itemKind", "tailPercent"],
  // EVM is always the epic view and has no per-day activity type/work category; its scope is ONE of epic, domain or team.
  [epicProgressPath]: ["domain", "epicId"],
  // Data quality takes the domain and its view, but has no control for an activity type or work category.
  [dataQualityPath]: ["domain", "domainView", "tailPercent"],
  // The cost matrix slices the worklogs by every one of these; its rows and columns are the org drill and the domains.
  [costMatrixPath]: ["domain", "domainView", "activityType", "workCategory"],
};

/**
 * The page-local params: view switches that live beside a report's chart, not in the filter bar. They are no part of the
 * filter (never sent to the API, not in the query key), and — like the report-specific params — follow a link only to
 * the page that reads them.
 */
export const THROUGHPUT_VIEW_PARAM = "view";
const PAGE_PARAMS: Readonly<Record<string, readonly string[]>> = {
  [throughputPath]: [THROUGHPUT_VIEW_PARAM],
};

/** The reports whose member control picks several people (repeated `accountId`); every other report keeps the FIRST. */
const MULTI_ACCOUNT_PATHS: ReadonlySet<string> = new Set([commitmentsPath, throughputPath]);

/** The report-specific params a report route has a control for — what its own filter normalizer keeps. */
export function reportSpecificKeep(path: string): ReadonlySet<ReportSpecificKey> {
  return new Set(REPORT_SPECIFIC_PARAMS[path] ?? []);
}

/**
 * A report route carrying the current filter query, so switching tabs keeps the period/team/member —
 * minus the report-specific and page-local params the target does not use. Only commitments and throughput pick several
 * members: moving between them keeps every `accountId`, leaving them keeps the FIRST (what a single-member page reads from
 * the repeats), so the address bar says what the page shows.
 */
export function reportHref(path: string, search: string): string {
  const params = new URLSearchParams(search);
  const accounts = params.getAll("accountId");
  if (!MULTI_ACCOUNT_PATHS.has(path) && accounts.length > 1) params.set("accountId", accounts[0]);
  const own = PAGE_PARAMS[path] ?? [];
  for (const key of Object.values(PAGE_PARAMS).flat()) {
    if (!own.includes(key)) params.delete(key);
  }
  const keep = reportSpecificKeep(path);
  for (const key of REPORT_SPECIFIC_KEYS) {
    if (!keep.has(key)) params.delete(key);
  }
  const query = params.toString();
  return query === "" ? path : `${path}?${query}`;
}
