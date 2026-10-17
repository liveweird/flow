import type { ReportFilters, SprintConsistencySprint } from "../api/reports";
import { labelledSprints } from "./reportSprints";

/** One sprint's MD figures for the three charts, in completion order. */
export interface SprintConsistencyChartRow {
  /** `<teamId>:<sprintId>` — what a bar double-click reports (`sprintOfRow`). */
  id: string;
  label: string;
  committedMd: number;
  deliveredMd: number;
  carriedOverMd: number;
  droppedMd: number;
  addedMd: number;
  removedMd: number;
}

export function sprintConsistencyRows(
  sprints: readonly SprintConsistencySprint[],
  filters: ReportFilters,
): SprintConsistencyChartRow[] {
  return labelledSprints(sprints, filters).map(({ sprint, label }) => ({
    id: `${sprint.teamId}:${sprint.sprintId}`,
    label,
    committedMd: sprint.committedMd,
    deliveredMd: sprint.deliveredMd,
    carriedOverMd: sprint.carriedOverMd,
    droppedMd: sprint.droppedMd,
    addedMd: sprint.addedMd,
    removedMd: sprint.removedMd,
  }));
}
