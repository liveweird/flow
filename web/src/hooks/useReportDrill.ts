import { useEffect, useRef, useState } from "react";
import { useLocation, useNavigate, useSearchParams } from "react-router-dom";
import { DRILL_PARAM, parseDrill, serializeDrill, withSegment, type DrillTarget } from "../utils/reportDrill";

/** Set on the history entry that OPENED a drill, so closing can step back instead of stacking another entry. */
const OPENED_STATE = "drillOpened";

/** Where a drill was opened from: the pressed element (focus goes back to it) and/or the chart card's anchor (the fallback). */
interface OpenFrom {
  trigger?: HTMLElement | null;
  anchor?: string;
}

export interface DrillState {
  target: DrillTarget | null;
  /**
   * `target`, kept after the drill closes until the next one opens: the drawer is still drawing its exit transition for a
   * moment, and must not go blank (an empty title, a loader) while it slides out. Open/closed is `target !== null`.
   */
  shown: DrillTarget | null;
  /**
   * Opens the drill of a bar (a history PUSH, so browser Back closes it). `trigger` is the element that asked for it (a table
   * figure that opens the drill): focus returns to it on close; without one (a chart double-click) the focused element, else the chart card.
   * A page with several chart cards passes the `anchor` of the card the bar is in (default: the hook's own).
   */
  open: (target: DrillTarget, from?: OpenFrom) => void;
  /** Narrows the open drill to one person's share (or back to everyone) without adding a history entry. */
  setSegment: (accountId: string | undefined) => void;
  /** Closes the drawer: back one entry when this page opened it, else the param is dropped (a deep link). */
  close: () => void;
}

/** The location state without the drill's own marker, other keys kept (a hand-off from elsewhere survives). */
function stateWithoutMarker(state: unknown): object | null {
  if (state === null || typeof state !== "object") return null;
  const rest = { ...(state as Record<string, unknown>) };
  delete rest[OPENED_STATE];
  return rest;
}

/** The chart card to return focus to: the one the opener named, else the hook's `anchor` (for the bar last shown, when it depends on it). */
function cardOf(named: string | null, anchor: string | ((target: DrillTarget) => string), last: DrillTarget | null): string | null {
  if (named !== null) return named;
  if (typeof anchor === "string") return anchor;
  return last === null ? null : anchor(last);
}

function dropParam(prev: URLSearchParams): URLSearchParams {
  const next = new URLSearchParams(prev);
  next.delete(DRILL_PARAM);
  return next;
}

/**
 * A report page's drill as URL state (`drill=bucket:2026-03-02`): the page stays mounted, so the filters, the scroll position
 * and the chart view are untouched while the drawer is open. It is no filter — never sent with the report request or keyed —
 * and it keeps every other param and the location state, like `useThroughputView`'s write. The drawer closes by itself when
 * `filterKey` (the serialized filter) changes, because the bar it listed no longer exists. Focus goes back to whatever opened
 * the drawer (a table's drill figure) or, for a pointer double-click on a chart, to the element carrying
 * `data-drill-anchor="<anchor>"` (the chart card): the drawer is rendered with `returnFocus={false}` — this hook is the one owner.
 */
export function useReportDrill(filterKey: string, anchor: string | ((target: DrillTarget) => string)): DrillState {
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const target = parseDrill(params.get(DRILL_PARAM));
  const isOpen = target !== null;
  // The last bar, kept past close (derived state, adjusted during render: compared by its spelling, since `target` is re-parsed each render).
  const [kept, setKept] = useState<{ spelled: string; target: DrillTarget } | null>(null);
  if (target !== null && serializeDrill(target) !== kept?.spelled) setKept({ spelled: serializeDrill(target), target });
  const opener = useRef<HTMLElement | null>(null);
  const wasOpen = useRef(false);
  const lastFilterKey = useRef(filterKey);
  // The card named by `open(…, { anchor })`; else (a chart double-click without one, a deep link) `anchor`, resolved for the last shown bar.
  const openedFrom = useRef<string | null>(null);

  const open = (next: DrillTarget, { trigger, anchor: from }: OpenFrom = {}) => {
    openedFrom.current = from ?? null;
    const active = document.activeElement;
    // A mouse click does not focus a <button> in Safari and Firefox, so the pressed element is passed in, not read from focus.
    opener.current = trigger ?? (active instanceof HTMLElement && active !== document.body ? active : null);
    setParams(
      (prev) => {
        const query = new URLSearchParams(prev);
        query.set(DRILL_PARAM, serializeDrill(next));
        return query;
      },
      { state: { ...(location.state as object | null), [OPENED_STATE]: true } },
    );
  };
  const setSegment = (accountId: string | undefined) => {
    if (target === null) return;
    const spelled = serializeDrill(withSegment(target, accountId));
    setParams(
      (prev) => {
        const query = new URLSearchParams(prev);
        query.set(DRILL_PARAM, spelled);
        return query;
      },
      { replace: true, state: location.state },
    );
  };
  const close = () => {
    if ((location.state as Record<string, unknown> | null)?.[OPENED_STATE] === true) navigate(-1);
    else setParams(dropParam, { replace: true, state: location.state });
  };

  // A changed filter closes an open drill (replace: the entry would otherwise point at a bar of the old filter).
  const { state } = location;
  useEffect(() => {
    if (lastFilterKey.current === filterKey) return;
    lastFilterKey.current = filterKey;
    if (isOpen) setParams(dropParam, { replace: true, state: stateWithoutMarker(state) });
  }, [filterKey, isOpen, setParams, state]);

  // Focus returns to the trigger — or the chart card — once the drill has closed, however it closed (X, Escape, Back, browser Back).
  useEffect(() => {
    if (isOpen) {
      wasOpen.current = true;
      return;
    }
    if (!wasOpen.current) return;
    wasOpen.current = false;
    const trigger = opener.current?.isConnected
      ? opener.current
      : document.querySelector<HTMLElement>(`[data-drill-anchor="${cardOf(openedFrom.current, anchor, kept?.target ?? null)}"]`);
    opener.current = null;
    openedFrom.current = null;
    trigger?.focus();
  }, [isOpen, anchor, kept]);

  return { target, shown: target ?? kept?.target ?? null, open, setSegment, close };
}
