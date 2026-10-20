import type { ReportFilterTeam } from "../api/reports";

/** The most teams Home shows (the server's `HOME_MAX_TEAMS`: `teamId` repeated 1..3). */
export const HOME_MAX_TEAMS = 3;

/** Validator for the remembered pick (`flow.viewSettings.home.teams`): `null` (= the default) or up to three positive whole team ids. */
export function isTeamPick(v: unknown): v is number[] | null {
  return (
    v === null ||
    (Array.isArray(v) && v.length <= HOME_MAX_TEAMS && v.every((id) => typeof id === "number" && Number.isInteger(id) && id >= 1))
  );
}

/**
 * The default teams: those the viewer manages first, then the rest, both in the reference data's (name) order, at most
 * `HOME_MAX_TEAMS` — returned in the reference data's order, the order the server lists them in. The rule lives here, not on the
 * server: a report's content never depends on who asks.
 */
export function defaultHomeTeams(teams: ReadonlyArray<ReportFilterTeam>, userId: number | null): number[] {
  const managed = teams.filter((team) => userId !== null && team.managerUserId === userId);
  const rest = teams.filter((team) => !managed.includes(team));
  const chosen = new Set([...managed, ...rest].slice(0, HOME_MAX_TEAMS).map((team) => team.id));
  return teams.filter((team) => chosen.has(team.id)).map((team) => team.id);
}

/** The remembered pick limited to the teams that still exist, in the reference data's order; `null` when nothing of it is left. */
export function livePick(stored: readonly number[] | null, teams: ReadonlyArray<ReportFilterTeam>): number[] | null {
  if (stored === null) return null;
  const kept = teams.filter((team) => stored.includes(team.id)).map((team) => team.id);
  return kept.length === 0 ? null : kept.slice(0, HOME_MAX_TEAMS);
}

/** The teams Home asks for: the live pick, else the default. */
export function effectiveHomeTeams(stored: readonly number[] | null, teams: ReadonlyArray<ReportFilterTeam>, userId: number | null): number[] {
  return livePick(stored, teams) ?? defaultHomeTeams(teams, userId);
}
