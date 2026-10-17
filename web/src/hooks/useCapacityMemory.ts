import { useCallback, useEffect } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { ApiError } from "../api/http";
import { capacitySprintPath, capacityTeamPath, parseCapacitySelection, type CapacitySelection } from "../utils/capacityLinks";
import { readRememberedQuery, writeRememberedQuery } from "./useStoredState";

/** The view-settings key of the Capacity page's last selection (`flow.viewSettings.capacity.filter`). */
const STORAGE_KEY = "capacity";
/** The `location.state` field marking a sprint that came out of the memory (its value is the sprint id), not from the user or a link. */
const RESTORED_STATE = "capacityRestoredSprint";

const restoredSprint = (state: unknown): unknown =>
  typeof state === "object" && state !== null ? (state as Record<string, unknown>)[RESTORED_STATE] : undefined;

/**
 * Capacity remembers the team and sprint the user last picked (`capacity.filter`, the URL query the pickers write). A bare visit
 * (neither `teamId` nor `sprintId` in the URL) restores it by `replace` once the team picker's list is in (`teamIds`): a team the
 * list no longer holds is dropped (the page stays on the empty picker). The sprint is restored WITH the team, whether or not the
 * picker's first-100 list holds it — the calendar read answers for any sprint of the team; only a `404`/`400` for it (`calendarError`)
 * drops a restored sprint (`staleRestoredSprint`, which the page uses to hide that error): the memory and the URL fall back to the
 * team. A URL that names a team or sprint is taken as written, and only a pick through the pickers (`remember`) writes the memory.
 */
export function useCapacityMemory(
  params: URLSearchParams,
  selection: CapacitySelection,
  teamIds: readonly number[] | undefined,
  calendarError: unknown,
): { remember: (picked: CapacitySelection) => void; staleRestoredSprint: boolean } {
  const navigate = useNavigate();
  const { state } = useLocation();
  const bare = !params.has("teamId") && !params.has("sprintId");
  const stored = readRememberedQuery(STORAGE_KEY);

  useEffect(() => {
    if (!bare || stored === null || teamIds === undefined) return;
    const remembered = parseCapacitySelection(new URLSearchParams(stored));
    if (remembered.teamId === undefined || !teamIds.includes(remembered.teamId)) return;
    if (remembered.sprintId === undefined) navigate(capacityTeamPath(remembered.teamId), { replace: true });
    else navigate(capacitySprintPath(remembered.teamId, remembered.sprintId), { replace: true, state: { [RESTORED_STATE]: remembered.sprintId } });
  }, [bare, stored, teamIds, navigate]);

  const { teamId, sprintId } = selection;
  const refused = calendarError instanceof ApiError && (calendarError.status === 404 || calendarError.status === 400);
  const staleRestoredSprint = refused && sprintId !== undefined && restoredSprint(state) === sprintId;
  useEffect(() => {
    if (!staleRestoredSprint || teamId === undefined) return;
    writeRememberedQuery(STORAGE_KEY, `teamId=${teamId}`);
    navigate(capacityTeamPath(teamId), { replace: true });
  }, [staleRestoredSprint, teamId, navigate]);

  const remember = useCallback((picked: CapacitySelection) => {
    const query = new URLSearchParams();
    if (picked.teamId !== undefined) query.set("teamId", String(picked.teamId));
    if (picked.teamId !== undefined && picked.sprintId !== undefined) query.set("sprintId", String(picked.sprintId));
    writeRememberedQuery(STORAGE_KEY, query.toString());
  }, []);

  return { remember, staleRestoredSprint };
}
