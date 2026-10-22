import type { CostsSprint, ReportFilters } from "../api/reports";
import { OTHER_EPICS, topEpicIds } from "./epicSegments";
import type { CostSprintDrillTarget, DrillTarget } from "./reportDrill";
import { labelledSprints } from "./reportSprints";
import { sprintOfRow } from "./sprintDrill";
import type { EpicSegmentTarget } from "./sprintEpics";

/**
 * The Costs sprint widgets' model (pure): the capacity chart's rows, the targets of their drills and the figure a drill is held
 * against. A sprint's chart row is identified like every sprint chart's, `<teamId>:<sprintId>` (`labelledSprints` order).
 */

/** One sprint of the capacity chart: its actual cost (AC) and the capacity beside it. */
export interface CostsCapacityRow {
  /** The row's identity in the drill: `<teamId>:<sprintId>`. */
  id: string;
  /** The x-axis category: the sprint's name. */
  label: string;
  acMd: number;
  /** The sprint's capacity in MD; `null` = none (always at USER level), and the line has no marker there. */
  capacityMd: number | null;
  /** Where the capacity came from; `null` exactly when there is none. */
  capacitySource: CostsSprint["capacitySource"];
}

/** The capacity chart's rows, one per sprint in completion order. */
export function costsCapacityRows(sprints: readonly CostsSprint[], filters: ReportFilters): CostsCapacityRow[] {
  return labelledSprints(sprints, filters).map(({ sprint, label }) => ({
    id: `${sprint.teamId}:${sprint.sprintId}`,
    label,
    acMd: sprint.acMd,
    capacityMd: sprint.capacityMd,
    capacitySource: sprint.capacitySource,
  }));
}

/** The target of a sprint's WHOLE actual-cost bar, `null` for a row id that names no sprint. */
export function costsSprintTarget(rowId: string): CostSprintDrillTarget | null {
  const sprint = sprintOfRow(rowId);
  return sprint === null ? null : { kind: "cost-sprint", ...sprint };
}

/**
 * The target of one by-epic segment of a sprint's bar (the by-epic section's `toTarget`): `epicSegment`, and for "Other epics" the
 * chart's own top epics. The bucket is none of Costs' business — its bars are cost, not a sprint bucket.
 */
export const costsEpicTarget: EpicSegmentTarget = (rowId, _bucket, segment, segments) => {
  const base = costsSprintTarget(rowId);
  if (base === null) return null;
  const tops = segment.key === OTHER_EPICS ? topEpicIds(segments) : undefined;
  return { ...base, epicSegment: segment.key, ...(tops === undefined ? {} : { topEpicIds: tops }) };
};

/** The man-days the chart draws for the open drill (the sprint's AC, or one epic segment's): what the drawer's recomputed bar is held against. */
export function costsChartMd(sprints: readonly CostsSprint[] | undefined, target: Extract<DrillTarget, { kind: "cost-sprint" }>): number | undefined {
  const sprint = sprints?.find((candidate) => candidate.teamId === target.teamId && candidate.sprintId === target.sprintId);
  if (sprint === undefined) return undefined;
  if (target.epicSegment === undefined) return sprint.acMd;
  return sprint.byEpic.find((segment) => segment.key === target.epicSegment)?.acMd;
}

/** The sprint's name as the page labels it, by team and id; `undefined` while the report is not in hand or does not list it. */
export function costsSprintName(sprints: readonly CostsSprint[] | undefined, target: { teamId: number; sprintId: number }): string | undefined {
  return sprints?.find((candidate) => candidate.teamId === target.teamId && candidate.sprintId === target.sprintId)?.name;
}

/** The connection the sprint bar was drawn from (a sprint id can exist on two connections); `undefined` while the report is not in hand or does not list it. */
export function costsSprintConnection(sprints: readonly CostsSprint[] | undefined, target: { teamId: number; sprintId: number }): number | undefined {
  return sprints?.find((candidate) => candidate.teamId === target.teamId && candidate.sprintId === target.sprintId)?.connectionId;
}
