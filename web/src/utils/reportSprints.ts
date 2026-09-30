import type { ReportFilters, ReportFilterTeam } from "../api/reports";
import type { ReportFilterState } from "./reportFilter";
import { teamLabel } from "./reportFormat";

/** The identity every sprint row of every sprint-shaped report carries. */
export interface SprintIdentity {
  sprintId: number;
  name: string;
  teamId: number;
  completedAt: number | null;
}

/** Sprints in completion order (an open sprint, no completion date, last), then by id. */
export function sortSprints<S extends Pick<SprintIdentity, "completedAt" | "sprintId">>(sprints: readonly S[]): S[] {
  return [...sprints].sort(
    (a, b) => (a.completedAt ?? Infinity) - (b.completedAt ?? Infinity) || a.sprintId - b.sprintId,
  );
}

/**
 * The sprints in completion order, each with its chart category label: the sprint's name,
 * prefixed with its team when several teams share the chart.
 */
export function labelledSprints<S extends SprintIdentity>(
  sprints: readonly S[],
  filters: ReportFilters,
): { sprint: S; label: string }[] {
  const multiTeam = new Set(sprints.map((sprint) => sprint.teamId)).size > 1;
  return sortSprints(sprints).map((sprint) => ({
    sprint,
    label: multiTeam ? `${teamLabel(sprint.teamId, filters.teams)} · ${sprint.name}` : sprint.name,
  }));
}

/**
 * The sprints the picker offers, newest first (completion, else start): the picked team's — or,
 * for a sprint link without a team, the team that lists that sprint.
 */
export function pickerSprints(teams: ReadonlyArray<ReportFilterTeam>, filter: ReportFilterState) {
  const team =
    teams.find((candidate) => candidate.id === filter.teamId) ??
    (filter.sprintId === undefined
      ? undefined
      : teams.find((candidate) => candidate.sprints.some((sprint) => sprint.sprintId === filter.sprintId)));
  return [...(team?.sprints ?? [])].sort(
    (a, b) => (b.completeAt ?? b.startAt ?? 0) - (a.completeAt ?? a.startAt ?? 0) || b.sprintId - a.sprintId,
  );
}
