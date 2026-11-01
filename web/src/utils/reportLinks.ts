import type { ParseKeys } from "i18next";
import { DRILL_PARAM } from "./reportDrill";
import { REPORT_SPECIFIC_KEYS, type ReportSpecificKey } from "./reportFilter";

/** The ONE place the reports route family is spelled out — never hand-assemble a report URL. */
export const commitmentsPath = "/reports/commitments";
export const throughputPath = "/reports/throughput";
export const sprintConsistencyPath = "/reports/sprint-consistency";
export const cycleTimePath = "/reports/cycle-time";
export const taskAccuracyPath = "/reports/task-estimation-accuracy";
const taskEstimatesPath = "/reports/task-estimates";
export const epicAccuracyPath = "/reports/epic-estimation-accuracy";
const estimateAdjustmentsPath = "/reports/estimate-adjustments";
const reportedTimePath = "/reports/reported-time-ratio";
export const wipPath = "/reports/wip";
export const backlogPath = "/reports/backlog";
export const agingWipPath = "/reports/aging-wip";
const blockedTimePath = "/reports/blocked-time";
export const epicProgressPath = "/reports/epic-progress";
export const dataQualityPath = "/reports/data-quality";
export const costsPath = "/reports/costs";
export const comparePeoplePath = "/reports/compare-people";
export const personTrendPath = "/reports/person-trend";
export const codeActivityPath = "/reports/code-activity";
export const taskCodePath = "/reports/task-code";
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

/** The Estimation group's tabs (task accuracy · epic accuracy · adjustments · reported time · task estimates). */
export const ESTIMATION_TABS: ReadonlyArray<ReportTabDef> = [
  { to: taskAccuracyPath, label: "reports.tabs.taskAccuracy" },
  { to: epicAccuracyPath, label: "reports.tabs.epicAccuracy" },
  { to: estimateAdjustmentsPath, label: "reports.tabs.adjustments" },
  { to: reportedTimePath, label: "reports.tabs.reportedTime" },
  { to: taskEstimatesPath, label: "reports.tabs.taskEstimates" },
];

/** The Flow group's tabs (WIP · estimated backlog · aging WIP · blocked time · epic progress). */
export const FLOW_TABS: ReadonlyArray<ReportTabDef> = [
  { to: wipPath, label: "reports.tabs.wip" },
  { to: backlogPath, label: "reports.tabs.backlog" },
  { to: agingWipPath, label: "reports.tabs.agingWip" },
  { to: blockedTimePath, label: "reports.tabs.blockedTime" },
  { to: epicProgressPath, label: "reports.tabs.epicProgress" },
];

/** The Code group's tabs (Code activity · Tasks and code): the merge requests by their author's team, and the done tasks by the code that merged for them. */
export const CODE_TABS: ReadonlyArray<ReportTabDef> = [
  { to: codeActivityPath, label: "reports.tabs.codeActivity" },
  { to: taskCodePath, label: "reports.tabs.taskCode" },
];

/**
 * The report-specific params each report has a control for. `tailPercent` (the long-tail control above the histograms) belongs
 * to the eight reports whose response carries a `Distribution`: task/epic accuracy, task estimates, adjustments, cycle time,
 * reported time, blocked time and data quality; `touchedWithinDays` (the staleness slider) belongs to the estimated backlog alone. Switching tabs must not carry a param
 * the target report cannot show or clear (a hidden `domain` filter silently narrowing Commitments).
 */
const REPORT_SPECIFIC_PARAMS: Readonly<Record<string, readonly ReportSpecificKey[]>> = {
  [commitmentsPath]: [],
  [throughputPath]: ["domainView", "domain", "activityType", "workCategory", "bucket"],
  // The scope rows' task slice: live-only figures, no snapshot.
  [sprintConsistencyPath]: ["activityType", "workCategory"],
  [taskAccuracyPath]: ["domainView", "domain", "activityType", "workCategory", "tailPercent"],
  // Epics carry no activity type, and their domain is their own space under either view.
  [epicAccuracyPath]: ["domain", "workCategory", "tailPercent"],
  // Task estimates reads the very population of task accuracy (the same slices), so it keeps the same params.
  [taskEstimatesPath]: ["domainView", "domain", "activityType", "workCategory", "tailPercent"],
  [estimateAdjustmentsPath]: ["domainView", "domain", "activityType", "workCategory", "tailPercent"],
  // Cycle time trends by week/month; reported time is a plain distribution pair.
  [cycleTimePath]: ["domainView", "domain", "activityType", "workCategory", "bucket", "tailPercent"],
  [reportedTimePath]: ["domainView", "domain", "activityType", "workCategory", "tailPercent"],
  // The snapshot reports read a task's own domain (no domain view) and answer 400 to an activity
  // type or work category; WIP adds what it counts and how it keys the counts.
  [wipPath]: ["domain", "by", "itemKind"],
  [backlogPath]: ["domain", "touchedWithinDays"],
  // Aging WIP slices like the fact reports but has no period; blocked time also counts tasks, epics or both.
  [agingWipPath]: ["domain", "activityType", "workCategory"],
  [blockedTimePath]: ["domain", "activityType", "workCategory", "itemKind", "tailPercent"],
  // EVM is always the epic view and has no per-day activity type/work category; its scope is ONE of epic, domain or team.
  // Its one control of its own is the basis (this period / since start).
  [epicProgressPath]: ["domain", "epicId", "basis"],
  // Data quality takes the domain and its view, but has no control for an activity type or work category.
  [dataQualityPath]: ["domain", "domainView", "tailPercent"],
  // Costs slices the worklogs by every one of these.
  [costsPath]: ["domain", "domainView", "activityType", "workCategory"],
  // Compare people slices both people alike and draws two cycle-time histograms (so the long tail); no bucket, WIP keying or item kind.
  [comparePeoplePath]: ["domain", "domainView", "activityType", "workCategory", "tailPercent"],
  // Person trend slices alike but draws no histogram: no long tail, bucket, WIP keying or item kind.
  [personTrendPath]: ["domain", "domainView", "activityType", "workCategory"],
  // Code activity reads the GitLab code layer, which no Jira slice touches; the long tail is the only control of its own (the histograms).
  [codeActivityPath]: ["tailPercent"],
  // Tasks and code is Cycle time's population (the done tasks: domain and its view, activity type, work category) joined to the code layer; two distributions, so the long tail.
  [taskCodePath]: ["domainView", "domain", "activityType", "workCategory", "tailPercent"],
};

/**
 * The page-local params: view switches that live beside a report's chart, not in the filter bar. They are no part of the
 * filter (never sent to the API, not in the query key), and — like the report-specific params — follow a link only to
 * the page that reads them.
 */
export const THROUGHPUT_VIEW_PARAM = "view";
/** The one value the throughput chart switch writes (`view=cumulative`); the per-period default is the absent param. */
export const THROUGHPUT_VIEW_CUMULATIVE = "cumulative";
/** Aging WIP's Tasks | Epics switch (`kind=epics`; the Tasks default is the absent param). */
export const AGING_KIND_PARAM = "kind";
/** The one value the aging switch writes (`kind=epics`). */
export const AGING_KIND_EPICS = "epics";
const PAGE_PARAMS: Readonly<Record<string, readonly string[]>> = {
  [throughputPath]: [THROUGHPUT_VIEW_PARAM],
  [agingWipPath]: [AGING_KIND_PARAM],
};
/** The only value each page-local param is ever WRITTEN with (the absent param is the default) — what a remembered filter may carry back. */
const PAGE_PARAM_VALUES: Readonly<Record<string, string>> = {
  [THROUGHPUT_VIEW_PARAM]: THROUGHPUT_VIEW_CUMULATIVE,
  [AGING_KIND_PARAM]: AGING_KIND_EPICS,
};

/** The page-local params (`view`, `kind`) a report route reads, each with the one non-default value it is written with. */
export function pageParamsOf(path: string): ReadonlyArray<{ key: string; value: string }> {
  return (PAGE_PARAMS[path] ?? []).map((key) => ({ key, value: PAGE_PARAM_VALUES[key] }));
}

/** The reports whose member control picks several people (repeated `accountId`); every other report keeps the FIRST. */
const MULTI_ACCOUNT_PATHS: ReadonlySet<string> = new Set([commitmentsPath, throughputPath, cycleTimePath, reportedTimePath, estimateAdjustmentsPath, taskAccuracyPath, taskEstimatesPath, wipPath, comparePeoplePath, codeActivityPath]);

/** The reports with no member control (always the whole team): leaving for one drops the `accountId`s, so the address bar says what the page shows. */
const NO_MEMBER_PATHS: ReadonlySet<string> = new Set([sprintConsistencyPath, epicAccuracyPath, epicProgressPath, backlogPath, taskCodePath]);

/** The report-specific params a report route has a control for — what its own filter normalizer keeps. */
export function reportSpecificKeep(path: string): ReadonlySet<ReportSpecificKey> {
  return new Set(REPORT_SPECIFIC_PARAMS[path] ?? []);
}

/**
 * A report route carrying the current filter query, so switching tabs keeps the period/team/member —
 * minus the report-specific and page-local params the target does not use. Only the `MULTI_ACCOUNT_PATHS` reports pick several members: moving between them keeps every `accountId`, leaving them keeps the FIRST (what a single-member page reads from
 * the repeats), so the address bar says what the page shows.
 */
export function reportHref(path: string, search: string): string {
  const params = new URLSearchParams(search);
  const accounts = params.getAll("accountId");
  if (!MULTI_ACCOUNT_PATHS.has(path) && accounts.length > 1) params.set("accountId", accounts[0]);
  if (NO_MEMBER_PATHS.has(path)) params.delete("accountId");
  // A drill names a bar of ONE report's chart: it means nothing on another report, so it never follows a tab link.
  params.delete(DRILL_PARAM);
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
