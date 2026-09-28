import type { ReportFilters, VelocitySprint } from "../api/reports";

export interface VelocityChartRow {
  /** The x-axis category: the sprint's name, prefixed with its team when several teams share the chart. */
  label: string;
  initialMd: number;
  finalMd: number;
  initialItems: number;
  finalItems: number;
}

/** Sprints in completion order (an open sprint, no completion date, last), then by id. */
export function sortSprints<S extends Pick<VelocitySprint, "completedAt" | "sprintId">>(sprints: readonly S[]): S[] {
  return [...sprints].sort(
    (a, b) => (a.completedAt ?? Infinity) - (b.completedAt ?? Infinity) || a.sprintId - b.sprintId,
  );
}

export function teamNameOf(filters: ReportFilters, teamId: number): string {
  return filters.teams.find((team) => team.id === teamId)?.name ?? `#${teamId}`;
}

/** The bar-chart rows: one per sprint, in completion order. */
export function velocityChartRows(sprints: readonly VelocitySprint[], filters: ReportFilters): VelocityChartRow[] {
  const multiTeam = new Set(sprints.map((sprint) => sprint.teamId)).size > 1;
  return sortSprints(sprints).map((sprint) => ({
    label: multiTeam ? `${teamNameOf(filters, sprint.teamId)} · ${sprint.name}` : sprint.name,
    initialMd: sprint.initialMd,
    finalMd: sprint.finalMd,
    initialItems: sprint.initialItems,
    finalItems: sprint.finalItems,
  }));
}
