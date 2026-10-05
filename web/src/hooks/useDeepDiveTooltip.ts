import { useCallback, useEffect, useRef, useState } from "react";
import type { FocusEvent, MouseEvent } from "react";

/** Where the tooltip's cell is on screen, which one it is (a visible row index and a time-column index) and how it opened. */
export interface CellTip {
  row: number;
  col: number;
  left: number;
  top: number;
  bottom: number;
  source: "focus" | "pointer";
}

const HIDE_DELAY_MS = 120;
/** While a card is open, the pointer must be able to travel to it across neighbouring cells without each one replacing it. */
const SHOW_DELAY_MS = 120;

const cellOf = (target: EventTarget | null): HTMLElement | null =>
  target instanceof Element
    ? target.closest<HTMLElement>("td[data-cell]")
    : null;

const outside = (inner: DOMRect, port: DOMRect) =>
  inner.bottom < port.top ||
  inner.top > port.bottom ||
  inner.right < port.left ||
  inner.left > port.right;

/**
 * The matrix's ONE shared tooltip (WCAG 1.4.13): it opens when the pointer rests on a cell OR the cell takes
 * keyboard focus, stays open while the pointer is over the tooltip itself (hoverable — the card abuts its cell, and a
 * different cell only replaces an open card after a short delay, so the pointer can reach it), stays until the
 * pointer or focus leaves (persistent) or Escape dismisses it (dismissible). Handlers are delegated — the table
 * carries them, cells carry only `data-row` / `data-col` — so a grid of thousands of cells has no per-cell closures.
 * A dismissed cell stays quiet until pointer or focus moves to another one. A scroll inside the card never closes it;
 * a scroll elsewhere closes a pointer-opened card, and for a focus-opened one repositions it, closing only when the
 * focused cell has left its scroller.
 */
export function useDeepDiveTooltip() {
  const [tip, setTip] = useState<CellTip | null>(null);
  const hideTimer = useRef<number | undefined>(undefined);
  const showTimer = useRef<number | undefined>(undefined);
  /** The key (`row:col`) of the open card, so the same cell never re-reads its rectangle. */
  const current = useRef<string | null>(null);
  const dismissed = useRef<string | null>(null);
  const anchor = useRef<HTMLElement | null>(null);
  const tipRef = useRef<HTMLDivElement | null>(null);

  const cancelHide = useCallback(() => {
    window.clearTimeout(hideTimer.current);
  }, []);
  const cancelShow = useCallback(() => {
    window.clearTimeout(showTimer.current);
  }, []);
  const close = useCallback(() => {
    cancelHide();
    cancelShow();
    dismissed.current = null;
    current.current = null;
    anchor.current = null;
    setTip(null);
  }, [cancelHide, cancelShow]);
  const hideSoon = useCallback(() => {
    cancelHide();
    cancelShow();
    hideTimer.current = window.setTimeout(close, HIDE_DELAY_MS);
  }, [cancelHide, cancelShow, close]);

  const open = useCallback((cell: HTMLElement, source: CellTip["source"]) => {
    const row = Number(cell.dataset.row);
    const col = Number(cell.dataset.col);
    current.current = `${row}:${col}`;
    dismissed.current = null;
    anchor.current = cell;
    const rect = cell.getBoundingClientRect();
    setTip({ row, col, left: rect.left, top: rect.top, bottom: rect.bottom, source });
  }, []);

  const show = useCallback(
    (cell: HTMLElement, source: CellTip["source"]) => {
      cancelHide();
      cancelShow();
      const key = `${Number(cell.dataset.row)}:${Number(cell.dataset.col)}`;
      if (current.current === key || dismissed.current === key) return;
      if (source === "pointer" && current.current !== null) {
        showTimer.current = window.setTimeout(() => open(cell, source), SHOW_DELAY_MS);
        return;
      }
      open(cell, source);
    },
    [cancelHide, cancelShow, open],
  );

  const tipKey = tip === null ? null : `${tip.row}:${tip.col}`;
  const source = tip?.source;
  useEffect(() => {
    if (tipKey === null) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      cancelHide();
      cancelShow();
      dismissed.current = tipKey;
      current.current = null;
      setTip(null);
    };
    const onScroll = (event: Event) => {
      if (event.target instanceof Node && tipRef.current?.contains(event.target)) return;
      const cell = anchor.current;
      if (source !== "focus" || cell === null || !cell.isConnected) {
        close();
        return;
      }
      const rect = cell.getBoundingClientRect();
      const port = cell.closest<HTMLElement>('[role="region"]')?.getBoundingClientRect();
      if (port !== undefined && outside(rect, port)) close();
      else setTip((prev) => (prev === null ? prev : { ...prev, left: rect.left, top: rect.top, bottom: rect.bottom }));
    };
    document.addEventListener("keydown", onKeyDown);
    window.addEventListener("scroll", onScroll, true);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("scroll", onScroll, true);
    };
  }, [tipKey, source, cancelHide, cancelShow, close]);

  useEffect(
    () => () => {
      window.clearTimeout(hideTimer.current);
      window.clearTimeout(showTimer.current);
    },
    [],
  );

  const onFocus = useCallback(
    (event: FocusEvent<HTMLElement>) => {
      const cell = cellOf(event.target);
      if (cell === null) close();
      else show(cell, "focus");
    },
    [show, close],
  );
  const onBlur = useCallback(
    (event: FocusEvent<HTMLElement>) => {
      if (!event.currentTarget.contains(event.relatedTarget)) close();
    },
    [close],
  );
  const onMouseOver = useCallback(
    (event: MouseEvent<HTMLElement>) => {
      const cell = cellOf(event.target);
      if (cell === null) hideSoon();
      else show(cell, "pointer");
    },
    [show, hideSoon],
  );
  const onTipEnter = useCallback(() => {
    cancelHide();
    cancelShow();
  }, [cancelHide, cancelShow]);

  return {
    tip,
    tipRef,
    /** Spread on the grid's `<table>`. */
    gridProps: { onFocus, onBlur, onMouseOver, onMouseLeave: hideSoon },
    /** Spread on the tooltip element (the pointer may travel onto it). */
    tipProps: { onMouseEnter: onTipEnter, onMouseLeave: hideSoon },
  };
}
