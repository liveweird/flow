import type { ParseKeys } from "i18next";
import { REPORT_SPECIFIC_KEYS, type ReportSpecificKey } from "./reportFilter";

/** The ONE place the reports route family is spelled out — never hand-assemble a report URL. */
export const velocityPath = "/reports/velocity";
const throughputPath = "/reports/throughput";
const sprintConsistencyPath = "/reports/sprint-consistency";
const cycleTimePath = "/reports/cycle-time";
export const taskAccuracyPath = "/reports/task-estimation-accuracy";
const epicAccuracyPath = "/reports/epic-estimation-accuracy";
const estimateAdjustmentsPath = "/reports/estimate-adjustments";
const reportedTimePath = "/reports/reported-time-ratio";
export const wipPath = "/reports/wip";
const backlogPath = "/reports/backlog";
const agingWipPath = "/reports/aging-wip";
const blockedTimePath = "/reports/blocked-time";
export const epicProgressPath = "/reports/epic-progress";
export const dataQualityPath = "/reports/data-quality";
export const costMatrixPath = "/reports/cost-matrix";

export type ReportTabDef = { to: string; label: ParseKeys };

/** The Delivery group's tabs (plan §8: velocity · throughput · sprint consistency · cycle time). */
export const DELIVERY_TABS: ReadonlyArray<ReportTabDef> = [
  { to: velocityPath, label: "reports.tabs.velocity" },
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
 * The report-specific params each report has a control for. Switching tabs must not carry a param
 * the target report cannot show or clear (a hidden `domain` filter silently narrowing Velocity).
 */
const REPORT_SPECIFIC_PARAMS: Readonly<Record<string, readonly ReportSpecificKey[]>> = {
  [velocityPath]: [],
  [throughputPath]: ["domainView", "domain", "activityType", "workCategory", "bucket"],
  [sprintConsistencyPath]: [],
  [taskAccuracyPath]: ["domainView", "domain", "activityType", "workCategory"],
  // Epics carry no activity type, and their domain is their own space under either view.
  [epicAccuracyPath]: ["domain", "workCategory"],
  [estimateAdjustmentsPath]: ["domainView", "domain", "activityType", "workCategory"],
  // Cycle time trends by week/month; reported time is a plain distribution pair.
  [cycleTimePath]: ["domainView", "domain", "activityType", "workCategory", "bucket"],
  [reportedTimePath]: ["domainView", "domain", "activityType", "workCategory"],
  // The snapshot reports read a task's own domain (no domain view) and answer 400 to an activity
  // type or work category; WIP adds what it counts and how it keys the counts.
  [wipPath]: ["domain", "by", "itemKind"],
  [backlogPath]: ["domain"],
  // Aging WIP slices like the fact reports but has no period; blocked time also counts tasks, epics or both.
  [agingWipPath]: ["domain", "activityType", "workCategory"],
  [blockedTimePath]: ["domain", "activityType", "workCategory", "itemKind"],
  // EVM is always the epic view and has no per-day activity type/work category; its scope is ONE of epic, domain or team.
  [epicProgressPath]: ["domain", "epicId"],
  // Data quality takes the domain and its view, but has no control for an activity type or work category.
  [dataQualityPath]: ["domain", "domainView"],
  // The cost matrix slices the worklogs by every one of these; its rows and columns are the org drill and the domains.
  [costMatrixPath]: ["domain", "domainView", "activityType", "workCategory"],
};

/**
 * A report route carrying the current filter query, so switching tabs keeps the period/team/member —
 * minus the report-specific params the target's controls do not use.
 */
export function reportHref(path: string, search: string): string {
  const params = new URLSearchParams(search);
  const allowed = REPORT_SPECIFIC_PARAMS[path] ?? [];
  for (const key of REPORT_SPECIFIC_KEYS) {
    if (!allowed.includes(key)) params.delete(key);
  }
  const query = params.toString();
  return query === "" ? path : `${path}?${query}`;
}
