import { useCallback, useLayoutEffect, useMemo, useRef } from "react";
import { useLocation, useSearchParams, type NavigateOptions } from "react-router-dom";

type Update = URLSearchParams | ((prev: URLSearchParams) => URLSearchParams);

/** The URL as a write sees it: the latest params and `location.state` this hook knows of (read when called, never captured). */
export interface LiveUrl {
  params: () => URLSearchParams;
  state: () => unknown;
}

/**
 * `useSearchParams` for URL WRITERS. react-router hands a functional update the params of the render its setter came from, so a handler
 * captured by an older render (a control opened before another change landed) built its write on stale params and overwrote the newer ones.
 *
 * Guarantees: the functional form receives the LATEST params, and the setter is stable. A write updates the live params and state AT ONCE
 * (react-router commits a navigation inside a transition, so the rendered location lags behind history), so a handler fired between a write
 * and its commit, or a second write in the same event, builds on the first. The live values are replaced by the rendered location whenever
 * IT changes (a commit, Back/forward); a re-render that still shows the old location never overwrites them. A plain value passes through.
 * `live` reads the same values for the state (`live.state()`, hand it to the write's `state` option) and for derived reads. Read-only callers keep `useSearchParams`.
 */
export function useLiveSearchParams(): [URLSearchParams, (next: Update, options?: NavigateOptions) => void, LiveUrl] {
  const [params, setSearchParams] = useSearchParams();
  const location = useLocation();
  const latest = useRef({ params, state: location.state as unknown, setSearchParams });
  useLayoutEffect(() => {
    latest.current = { params, state: location.state, setSearchParams };
  }, [params, setSearchParams, location]);
  const setParams = useCallback((next: Update, options?: NavigateOptions) => {
    const current = latest.current;
    const value = typeof next === "function" ? next(new URLSearchParams(current.params)) : next;
    latest.current = { ...current, params: new URLSearchParams(value), state: options?.state ?? null };
    current.setSearchParams(value, options);
  }, []);
  const live = useMemo<LiveUrl>(() => ({ params: () => latest.current.params, state: () => latest.current.state }), []);
  return [params, setParams, live];
}
