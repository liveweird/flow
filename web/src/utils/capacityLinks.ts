/**
 * The ONE place the Capacity route family is spelled out — never hand-assemble the URL. The URL IS
 * the selection (the reports' rule): `?teamId=` picks the team, `&sprintId=` the sprint, so a
 * calendar is deep-linkable and survives a reload.
 */
export const capacityPath = "/capacity";
export const capacityTeamPath = (teamId: number) => `${capacityPath}?teamId=${teamId}`;
export const capacitySprintPath = (teamId: number, sprintId: number) => `${capacityTeamPath(teamId)}&sprintId=${sprintId}`;

export interface CapacitySelection {
  teamId: number | undefined;
  /** Only ever set together with a team: a sprint belongs to the team it is planned for. */
  sprintId: number | undefined;
}

function positiveInteger(raw: string | null): number | undefined {
  if (raw === null || !/^\d+$/.test(raw)) return undefined;
  const value = Number(raw);
  return Number.isSafeInteger(value) && value > 0 ? value : undefined;
}

/** The selection a query string carries; anything malformed reads as "not picked". */
export function parseCapacitySelection(params: URLSearchParams): CapacitySelection {
  const teamId = positiveInteger(params.get("teamId"));
  return { teamId, sprintId: teamId === undefined ? undefined : positiveInteger(params.get("sprintId")) };
}
