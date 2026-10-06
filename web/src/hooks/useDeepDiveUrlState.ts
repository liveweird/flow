import { useCallback, useMemo } from "react";
import { useSearchParams } from "react-router-dom";
import {
  applyDeepDiveSelection,
  applyDeepDiveView,
  deepDiveMode,
  deepDiveQuery,
  parseDeepDiveSelection,
  parseDeepDiveView,
  type DeepDiveSelection,
  type DeepDiveView,
} from "../utils/deepDiveFilter";

/**
 * The Deep dive's URL state: the search params ARE the selection and the open view (`utils/deepDiveFilter.ts` owns the
 * format). `selectionKey` is the canonical serialization of the selection (the query and matrix key), `complete` whether
 * it names a mode at all. `applySelection` writes the selection as a new history entry; `setView` swaps the view in place
 * (`replace`), so hopping between the tabs never fills the Back stack. Params this feature does not own are kept.
 */
export function useDeepDiveUrlState() {
  const [params, setParams] = useSearchParams();
  const selection = useMemo(() => parseDeepDiveSelection(params), [params]);
  const selectionKey = deepDiveQuery(selection);
  const view = parseDeepDiveView(params);
  const complete = deepDiveMode(selection) !== null;

  const applySelection = useCallback(
    (next: DeepDiveSelection) => setParams(applyDeepDiveSelection(params, next)),
    [params, setParams],
  );
  const setView = useCallback(
    (next: DeepDiveView) => setParams(applyDeepDiveView(params, next), { replace: true }),
    [params, setParams],
  );

  return { selection, selectionKey, view, complete, applySelection, setView };
}
