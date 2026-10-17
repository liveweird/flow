import { useLocation, useSearchParams } from "react-router-dom";
import type { AgingKind } from "../utils/agingChart";
import { AGING_KIND_PARAM } from "../utils/reportLinks";

const EPICS_VALUE = "epics";

/**
 * Aging WIP's Tasks | Epics switch as URL state (`kind=epics`; Tasks, the default, is the absent param — and so is any other value).
 * It is no filter — it picks which side of the one response the page shows, so it never reaches the request or the query key — and it
 * keeps every other param and the location state, like the filter writes do.
 */
export function useAgingKind(): { kind: AgingKind; setKind: (kind: AgingKind) => void } {
  const [params, setParams] = useSearchParams();
  const { state } = useLocation();
  const kind: AgingKind = params.get(AGING_KIND_PARAM) === EPICS_VALUE ? "EPIC" : "TASK";
  const setKind = (next: AgingKind) =>
    setParams(
      (prev) => {
        const nextParams = new URLSearchParams(prev);
        if (next === "EPIC") nextParams.set(AGING_KIND_PARAM, EPICS_VALUE);
        else nextParams.delete(AGING_KIND_PARAM);
        return nextParams;
      },
      { state },
    );
  return { kind, setKind };
}
