import { useCallback, useEffect, useMemo } from "react";
import { useLocation } from "react-router-dom";
import type { ReportFilters } from "../api/reports";
import { rememberedDeepDive, restoreDeepDive } from "../utils/deepDiveRemembered";
import {
  applyDeepDiveSelection,
  applyDeepDiveView,
  deepDiveMode,
  deepDiveQuery,
  hasRetiredParams,
  hasSelectionParams,
  parseDeepDiveSelection,
  parseDeepDiveView,
  type DeepDiveSelection,
  type DeepDiveView,
} from "../utils/deepDiveFilter";
import { readRememberedQuery, writeRememberedQuery } from "./useStoredState";
import { useLiveSearchParams } from "./useLiveSearchParams";

/** The view-settings key of the Deep dive's last shown selection (`flow.viewSettings.reports.deepDive.filter`). */
const STORAGE_KEY = "reports.deepDive";
/** The `location.state` field marking a selection that came out of the memory (its value is the selection's key), not from the user or a link. */
const RESTORED_STATE = "deepDiveRestored";

const stateRecord = (state: unknown): Record<string, unknown> => (typeof state === "object" && state !== null ? (state as Record<string, unknown>) : {});

/**
 * The Deep dive's URL state: the search params ARE the selection and the open view (`utils/deepDiveFilter.ts` owns the
 * format). `selectionKey` is the canonical serialization of the selection (the query and matrix key), `complete` whether
 * it names a mode at all. `applySelection` writes the selection as a new history entry; `setView` swaps the view in place
 * (`replace`), so hopping between the tabs never fills the Back stack. Params this feature does not own are kept; `retired`
 * says the URL still carries a key the page dropped (an old "Tasks of an epic" link), so the page can say so.
 *
 * MEMORY: the last selection the user SHOWED (and the view they left open) is remembered under `reports.deepDive.filter` — written by
 * `applySelection` and `setView` only. A bare visit (the URL carries no selection key at all, not even a retired one) restores it once
 * the reference data (`filters`) has loaded: the domain and the connection are checked against it, the rest goes through the parser a
 * link goes through, and the URL is rewritten (replace) so it says what the page shows. A URL that carries any selection param is taken
 * as written and never stored. `restoredFromMemory` says the shown selection came out of the memory and nothing has changed it since,
 * so the page can `forgetRestored()` one the server refuses (a deleted epic, a sprint that is gone).
 */
export function useDeepDiveUrlState(filters?: ReportFilters) {
  const [params, setParams, live] = useLiveSearchParams();
  const { state: locationState } = useLocation();
  const parsed = useMemo(() => parseDeepDiveSelection(params), [params]);
  const bare = !hasSelectionParams(params);
  const stored = readRememberedQuery(STORAGE_KEY);
  const restored = useMemo(
    () => (bare && stored !== null && filters !== undefined ? restoreDeepDive(stored, filters) : undefined),
    [bare, stored, filters],
  );
  const restoredSelection = restored !== undefined && deepDiveMode(restored.selection) !== null ? restored.selection : undefined;
  const selection = restoredSelection ?? parsed;
  const selectionKey = deepDiveQuery(selection);
  const viewInUrl = params.has("view");
  const view = viewInUrl || restored === undefined ? parseDeepDiveView(params) : restored.view;
  const complete = deepDiveMode(selection) !== null;
  const retired = hasRetiredParams(params);
  const needsRestore = restoredSelection !== undefined || (restored !== undefined && !viewInUrl && restored.view !== "matrix");

  useEffect(() => {
    if (!needsRestore) return;
    setParams(
      (prev) => {
        const withSelection = restoredSelection === undefined ? prev : applyDeepDiveSelection(prev, restoredSelection);
        return applyDeepDiveView(withSelection, view);
      },
      {
        replace: true,
        state: restoredSelection === undefined ? live.state() : { ...stateRecord(live.state()), [RESTORED_STATE]: deepDiveQuery(restoredSelection) },
      },
    );
  }, [needsRestore, restoredSelection, view, live, setParams]);

  // The selection and view as the LATEST params say them (a write not yet committed counts), for the memory a write stores.
  const liveSelection = useCallback(() => {
    const latest = live.params();
    return latest === params ? selection : parseDeepDiveSelection(latest);
  }, [live, params, selection]);
  const liveView = useCallback(() => {
    const latest = live.params();
    return latest === params ? view : parseDeepDiveView(latest);
  }, [live, params, view]);

  const applySelection = useCallback(
    (next: DeepDiveSelection) => {
      writeRememberedQuery(STORAGE_KEY, rememberedDeepDive(next, liveView()));
      setParams((prev) => applyDeepDiveSelection(prev, next));
    },
    [liveView, setParams],
  );
  const setView = useCallback(
    (next: DeepDiveView) => {
      writeRememberedQuery(STORAGE_KEY, rememberedDeepDive(liveSelection(), next));
      setParams((prev) => applyDeepDiveView(prev, next), { replace: true, state: live.state() });
    },
    [live, liveSelection, setParams],
  );
  const restoredFromMemory = complete && stateRecord(locationState)[RESTORED_STATE] === selectionKey;
  /** Drops a restored selection the server refused: the memory is emptied and the URL goes back to the bare page. */
  const forgetRestored = useCallback(() => {
    writeRememberedQuery(STORAGE_KEY, "");
    setParams((prev) => applyDeepDiveSelection(prev, {}), { replace: true, state: null });
  }, [setParams]);

  return { selection, selectionKey, view, complete, retired, restoredFromMemory, forgetRestored, applySelection, setView };
}
