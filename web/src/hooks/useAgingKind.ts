import type { AgingKind } from "../utils/agingChart";
import { AGING_KIND_EPICS, AGING_KIND_PARAM } from "../utils/reportLinks";
import { rememberPageParam } from "./useReportFilter";
import { useLiveSearchParams } from "./useLiveSearchParams";

const STORAGE_KEY = "reports.aging-wip";

/**
 * Aging WIP's Tasks | Epics switch as URL state (`kind=epics`; Tasks, the default, is the absent param — and so is any other value).
 * It is no filter — it picks which side of the one response the page shows, so it never reaches the request or the query key — and it
 * keeps every other param and the location state, like the filter writes do. Aging WIP's own memory (`useReportFilter`) keeps the choice too.
 */
export function useAgingKind(): { kind: AgingKind; setKind: (kind: AgingKind) => void } {
  const [params, setParams, live] = useLiveSearchParams();
  const kind: AgingKind = params.get(AGING_KIND_PARAM) === AGING_KIND_EPICS ? "EPIC" : "TASK";
  const setKind = (next: AgingKind) => {
    const withKind = (prev: URLSearchParams) => {
      const nextParams = new URLSearchParams(prev);
      if (next === "EPIC") nextParams.set(AGING_KIND_PARAM, AGING_KIND_EPICS);
      else nextParams.delete(AGING_KIND_PARAM);
      return nextParams;
    };
    rememberPageParam(STORAGE_KEY, AGING_KIND_PARAM, next === "EPIC" ? AGING_KIND_EPICS : null);
    setParams(withKind, { state: live.state() });
  };
  return { kind, setKind };
}
