import type { ParseKeys } from "i18next";

/** The ONE place the reports route family is spelled out — never hand-assemble a report URL. */
export const velocityPath = "/reports/velocity";

export type ReportTabDef = { to: string; label: ParseKeys };

/** The Delivery group's tabs — later reports of the group append here as their pages land. */
export const DELIVERY_TABS: ReadonlyArray<ReportTabDef> = [
  { to: velocityPath, label: "reports.tabs.velocity" },
];

/** A report route carrying the current filter query, so switching tabs keeps the period/team. */
export function reportHref(path: string, search: string): string {
  return search === "" ? path : `${path}?${search.replace(/^\?/, "")}`;
}
