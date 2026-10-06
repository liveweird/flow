import { useCallback, useRef, useState } from "react";
import type { FocusEvent, KeyboardEvent } from "react";

/**
 * A roving-tab-stop grid for a `<table role="grid">`: one Tab stop for the whole grid, arrow keys (and Home/End, Ctrl for the
 * first/last row or column) between its cells. It knows nothing about what the cells show — only positions.
 *
 * Positions are `{ r, c }`. Body rows are 1…n; the header rows above are 0, -1, -2 … from the bottom up (so a header row
 * added later never renumbers the body); column 0 is the first (item) column, the rest 1…n. Every navigable element in the
 * grid carries its position via `rovingProps` (a header cell may span columns: `end` is the last one it covers); exactly one
 * has `tabIndex` 0.
 */
export interface GridPos {
  r: number;
  c: number;
}

/** The grid's extent: the topmost header row, the last body row and the last column. */
export interface GridBounds {
  rMin: number;
  rMax: number;
  cMax: number;
}

interface ParsedPos {
  r: number;
  c0: number;
  c1: number;
}

const parsePos = (element: HTMLElement): ParsedPos | null => {
  const raw = element.dataset.gridPos;
  if (raw === undefined) return null;
  const [r, c0] = raw.split(",").map(Number);
  return { r, c0, c1: Number(element.dataset.gridEnd ?? c0) };
};

/** The roving tab stop: every navigable element carries its position (and the last column a spanning header covers), exactly one has `tabIndex` 0. */
export const rovingProps = (r: number, c: number, active: boolean, end: number = c) => ({
  "data-grid-pos": `${r},${c}`,
  "data-grid-end": end,
  tabIndex: active ? 0 : -1,
});

/** The element at a grid position: an exact body cell, or the header cell of that row that COVERS the column. */
function cellAt(table: HTMLElement, r: number, c: number): HTMLElement | null {
  if (r >= 1) return table.querySelector<HTMLElement>(`[data-grid-pos="${r},${c}"]`);
  for (const element of table.querySelectorAll<HTMLElement>(`thead [data-grid-pos^="${r},"]`)) {
    const pos = parsePos(element);
    if (pos !== null && c >= pos.c0 && c <= pos.c1) return element;
  }
  return null;
}

/** Steps from a position until something is there (header rows have holes where a cell spans down) or the grid ends. */
function scan(table: HTMLElement, r: number, c: number, dr: number, dc: number, b: GridBounds): HTMLElement | null {
  let row = r;
  let col = c;
  while (row >= b.rMin && row <= b.rMax && col >= 0 && col <= b.cMax) {
    const found = cellAt(table, row, col);
    if (found !== null) return found;
    row += dr;
    col += dc;
  }
  return null;
}

const NAVIGATION_KEYS = ["ArrowRight", "ArrowLeft", "ArrowDown", "ArrowUp", "Home", "End"];

function navigate(table: HTMLElement, from: HTMLElement, key: string, ctrl: boolean, b: GridBounds): HTMLElement | null {
  const pos = parsePos(from);
  if (pos === null) return null;
  switch (key) {
    case "ArrowRight":
      return scan(table, pos.r, pos.c1 + 1, 0, 1, b);
    case "ArrowLeft":
      return scan(table, pos.r, pos.c0 - 1, 0, -1, b);
    case "ArrowDown":
      return scan(table, pos.r + 1, pos.c0, 1, 0, b);
    case "ArrowUp":
      return scan(table, pos.r - 1, pos.c0, -1, 0, b);
    case "Home":
      return ctrl ? cellAt(table, b.rMin, 0) : scan(table, pos.r, 0, 0, 1, b);
    case "End":
      return ctrl ? cellAt(table, b.rMax, b.cMax) : scan(table, pos.r, b.cMax, 0, -1, b);
    default:
      return null;
  }
}

/**
 * `bounds` is the grid's current extent; `isValid` says whether a position still exists after a re-render (a drill can remove
 * the header cell the tab stop sat on) and `fallback` is where the tab stop goes when it does not. Attach `ref`, `onKeyDown`
 * and `onFocus` to the table and mark every navigable element with `rovingProps(r, c, active)` against `active`.
 */
export function useRovingGrid({
  bounds,
  isValid,
  fallback,
}: {
  bounds: GridBounds;
  isValid: (pos: GridPos) => boolean;
  fallback: GridPos;
}) {
  const ref = useRef<HTMLTableElement>(null);
  const [last, setLast] = useState<GridPos>({ r: 1, c: 0 });
  const active: GridPos = isValid(last) ? last : fallback;

  const onKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    const from = (event.target as HTMLElement).closest<HTMLElement>("[data-grid-pos]");
    const table = ref.current;
    if (from === null || table === null || event.altKey || event.metaKey) return;
    const to = navigate(table, from, event.key, event.ctrlKey, bounds);
    if (!NAVIGATION_KEYS.includes(event.key)) return;
    event.preventDefault();
    to?.focus();
  };

  /** Whatever takes focus becomes the tab stop. */
  const onFocus = (event: FocusEvent<HTMLElement>) => {
    const element = (event.target as HTMLElement).closest<HTMLElement>("[data-grid-pos]");
    const pos = element === null ? null : parsePos(element);
    if (pos !== null) setLast((prev) => (prev.r === pos.r && prev.c === pos.c0 ? prev : { r: pos.r, c: pos.c0 }));
  };

  /** The element holding the tab stop right now, if the grid is mounted. */
  const tabStop = useCallback(() => ref.current?.querySelector<HTMLElement>('[data-grid-pos][tabindex="0"]') ?? null, []);

  return { ref, active, onKeyDown, onFocus, tabStop };
}
