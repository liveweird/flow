import { useLocation, useSearchParams } from "react-router-dom";
import { THROUGHPUT_VIEW_PARAM } from "../utils/reportLinks";

/** How the throughput charts draw the series: one bar per week/month or sprint, or the running totals as stacked areas. */
export type ThroughputView = "PERIOD" | "CUMULATIVE";

const CUMULATIVE_VALUE = "cumulative";

/**
 * Throughput's chart view as URL state (`view=cumulative`; the per-period default is the absent param). It is no filter —
 * it changes how the same report is drawn, so it never reaches the request or the query key — and it keeps every other
 * param and the location state, like the filter writes do.
 */
export function useThroughputView(): { view: ThroughputView; setView: (view: ThroughputView) => void } {
  const [params, setParams] = useSearchParams();
  const { state } = useLocation();
  const view: ThroughputView = params.get(THROUGHPUT_VIEW_PARAM) === CUMULATIVE_VALUE ? "CUMULATIVE" : "PERIOD";
  const setView = (next: ThroughputView) =>
    setParams(
      (prev) => {
        const nextParams = new URLSearchParams(prev);
        if (next === "CUMULATIVE") nextParams.set(THROUGHPUT_VIEW_PARAM, CUMULATIVE_VALUE);
        else nextParams.delete(THROUGHPUT_VIEW_PARAM);
        return nextParams;
      },
      { state },
    );
  return { view, setView };
}
