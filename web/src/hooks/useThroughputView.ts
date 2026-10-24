import { THROUGHPUT_VIEW_CUMULATIVE, THROUGHPUT_VIEW_PARAM } from "../utils/reportLinks";
import { rememberPageParam } from "./useReportFilter";
import { useLiveSearchParams } from "./useLiveSearchParams";

const STORAGE_KEY = "reports.throughput";

/** How the throughput charts draw the series: one bar per week/month or sprint, or the running totals as stacked areas. */
export type ThroughputView = "PERIOD" | "CUMULATIVE";

/**
 * Throughput's chart view as URL state (`view=cumulative`; the per-period default is the absent param). It is no filter —
 * it changes how the same report is drawn, so it never reaches the request or the query key — and it keeps every other
 * param and the location state, like the filter writes do. Throughput's own memory (`useReportFilter`) keeps the choice too.
 */
export function useThroughputView(): { view: ThroughputView; setView: (view: ThroughputView) => void } {
  const [params, setParams, live] = useLiveSearchParams();
  const view: ThroughputView = params.get(THROUGHPUT_VIEW_PARAM) === THROUGHPUT_VIEW_CUMULATIVE ? "CUMULATIVE" : "PERIOD";
  const setView = (next: ThroughputView) => {
    const withView = (prev: URLSearchParams) => {
      const nextParams = new URLSearchParams(prev);
      if (next === "CUMULATIVE") nextParams.set(THROUGHPUT_VIEW_PARAM, THROUGHPUT_VIEW_CUMULATIVE);
      else nextParams.delete(THROUGHPUT_VIEW_PARAM);
      return nextParams;
    };
    rememberPageParam(STORAGE_KEY, THROUGHPUT_VIEW_PARAM, next === "CUMULATIVE" ? THROUGHPUT_VIEW_CUMULATIVE : null);
    setParams(withView, { state: live.state() });
  };
  return { view, setView };
}
