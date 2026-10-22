import type { CostsSprint, ReportFilters } from "../api/reports";
import { OTHER_EPICS, topEpicIds } from "./epicSegments";
import type { CostSprintDrillTarget } from "./reportDrill";
import { labelledSprints, sprintRowId } from "./reportSprints";
import type { EpicSegmentTarget } from "./sprintEpics";

/**
 * The Costs sprint widgets' model (pure): the capacity chart's rows, the targets of their drills and the figure a drill is held
 * against. A sprint's chart row is identified by `<teamId>:<connectionId>:<sprintId>` (`sprintRowId`: Costs draws one bar per connection and sprint, and a sprint id can exist on two connections).
 */

/** One sprint of the capacity chart: its actual cost (AC) and the capacity beside it. */
export interface CostsCapacityRow {
  /** The row's identity in the drill: `<teamId>:<connectionId>:<sprintId>`. */
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
    id: sprintRowId(sprint),
    label,
    acMd: sprint.acMd,
    capacityMd: sprint.capacityMd,
    capacitySource: sprint.capacitySource,
  }));
}

/** The (team, connection, sprint) a row id names, `null` for any other spelling. */
function sprintOfCostsRow(rowId: string): Pick<CostSprintDrillTarget, "teamId" | "connectionId" | "sprintId"> | null {
  const parts = rowId.split(":");
  if (parts.length !== 3 || !parts.every((part) => /^[1-9]\d*$/.test(part))) return null;
  const [teamId, connectionId, sprintId] = parts.map(Number);
  return { teamId, connectionId, sprintId };
}

/** The target of a sprint's WHOLE actual-cost bar, `null` for a row id that names no sprint. */
export function costsSprintTarget(rowId: string): CostSprintDrillTarget | null {
  const sprint = sprintOfCostsRow(rowId);
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

/** The sprint a target names, by team, connection and sprint id: the very bar the chart drew. */
function sprintOf(sprints: readonly CostsSprint[] | undefined, target: Pick<CostSprintDrillTarget, "teamId" | "connectionId" | "sprintId">): CostsSprint | undefined {
  return sprints?.find(
    (candidate) => candidate.teamId === target.teamId && candidate.connectionId === target.connectionId && candidate.sprintId === target.sprintId,
  );
}

/** The man-days the chart draws for the open drill (the sprint's AC, or one epic segment's): what the drawer's recomputed bar is held against. */
export function costsChartMd(sprints: readonly CostsSprint[] | undefined, target: CostSprintDrillTarget): number | undefined {
  const sprint = sprintOf(sprints, target);
  if (sprint === undefined) return undefined;
  if (target.epicSegment === undefined) return sprint.acMd;
  return sprint.byEpic.find((segment) => segment.key === target.epicSegment)?.acMd;
}

/** The sprint's name as the page labels it; `undefined` while the report is not in hand or does not list it. */
export function costsSprintName(sprints: readonly CostsSprint[] | undefined, target: CostSprintDrillTarget): string | undefined {
  return sprintOf(sprints, target)?.name;
}
