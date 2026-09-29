import type { ReportFilters, VelocitySprint } from "../api/reports";
import { labelledSprints } from "./reportSprints";

export interface VelocityChartRow {
  /** The x-axis category: the sprint's name, prefixed with its team when several teams share the chart. */
  label: string;
  initialMd: number;
  finalMd: number;
  initialItems: number;
  finalItems: number;
}

/** The bar-chart rows: one per sprint, in completion order. */
export function velocityChartRows(sprints: readonly VelocitySprint[], filters: ReportFilters): VelocityChartRow[] {
  return labelledSprints(sprints, filters).map(({ sprint, label }) => ({
    label,
    initialMd: sprint.initialMd,
    finalMd: sprint.finalMd,
    initialItems: sprint.initialItems,
    finalItems: sprint.finalItems,
  }));
}
