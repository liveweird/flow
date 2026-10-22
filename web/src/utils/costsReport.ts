import type { ReportMeta } from "../api/reports";
import type { CostBreakdownBy } from "./costBreakdown";
import type { DrillTarget } from "./reportDrill";
import { dropReportSpecific, type ReportFilterState } from "./reportFilter";
import { costsPath, reportSpecificKeep } from "./reportLinks";

/**
 * Makes a filter the Costs report can answer, and says what its page can show. The report reads the domain view (default
 * EPIC), a domain, an activity type and a work category, and the page has a control for each, while `bucket`, `by`, `itemKind`,
 * `epicId` and `connectionId` have none here. Every one the page cannot show or clear is dropped off the request AND the URL.
 */
export function normalizeCostsFilter(filter: ReportFilterState): ReportFilterState {
  const next = dropReportSpecific(filter, reportSpecificKeep(costsPath));
  delete next.connectionId; // an ingestion concept with no control here (the API still accepts it): a stale link's value is dropped
  return next;
}

/** True for a `lastSprints`/`sprintId` period: the server resolves sprints and sends no `from`/`to`. */
export function isSprintRelative(meta: ReportMeta): boolean {
  return meta.from === null;
}

/** The focus-return anchors of the Costs drill cards: a drill opened by a click on a donut slice returns focus to its card. */
const BREAKDOWN_ANCHORS: Record<CostBreakdownBy, string> = { ACTIVITY_TYPE: "cost-activity-type", WORK_CATEGORY: "cost-work-category" };

/** The breakdown card's anchor. */
export const breakdownAnchor = (by: CostBreakdownBy): string => BREAKDOWN_ANCHORS[by];

/** The focus-return anchors of the sprint widgets: the by-epic card (a segment's drill) and the capacity card (a sprint's whole bar). */
export const COSTS_EPIC_ANCHOR = "costs-epics";
export const COSTS_CAPACITY_ANCHOR = "costs-capacity";

/** The card the page's ONE drill returns focus to: the target's own (a donut's, or the sprint widget's); any other (hand-typed) target falls back to the first card. */
export function costDrillAnchor(target: DrillTarget): string {
  if (target.kind === "cost") return BREAKDOWN_ANCHORS[target.by];
  if (target.kind === "cost-sprint") return target.epicSegment === undefined ? COSTS_CAPACITY_ANCHOR : COSTS_EPIC_ANCHOR;
  return BREAKDOWN_ANCHORS.ACTIVITY_TYPE;
}
