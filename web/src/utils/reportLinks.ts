import type { ParseKeys } from "i18next";
import { REPORT_SPECIFIC_KEYS, type ReportSpecificKey } from "./reportFilter";

/** The ONE place the reports route family is spelled out — never hand-assemble a report URL. */
export const velocityPath = "/reports/velocity";
const throughputPath = "/reports/throughput";
const sprintConsistencyPath = "/reports/sprint-consistency";
export const taskAccuracyPath = "/reports/task-estimation-accuracy";
const epicAccuracyPath = "/reports/epic-estimation-accuracy";
const estimateAdjustmentsPath = "/reports/estimate-adjustments";

export type ReportTabDef = { to: string; label: ParseKeys };

/** The Delivery group's tabs — later reports of the group append here as their pages land. */
export const DELIVERY_TABS: ReadonlyArray<ReportTabDef> = [
  { to: velocityPath, label: "reports.tabs.velocity" },
  { to: throughputPath, label: "reports.tabs.throughput" },
  { to: sprintConsistencyPath, label: "reports.tabs.sprintConsistency" },
];

/** The Estimation group's tabs — reported time joins in the next part. */
export const ESTIMATION_TABS: ReadonlyArray<ReportTabDef> = [
  { to: taskAccuracyPath, label: "reports.tabs.taskAccuracy" },
  { to: epicAccuracyPath, label: "reports.tabs.epicAccuracy" },
  { to: estimateAdjustmentsPath, label: "reports.tabs.adjustments" },
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
