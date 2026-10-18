import { useCallback, useLayoutEffect, useRef } from "react";
import type { FocusEvent, KeyboardEvent } from "react";

/**
 * ONE Tab stop for a table of buttons, arrow keys between them (the sibling of `useRovingGrid` for tables whose navigable cells are
 * whatever buttons their renderers drew -- `useRovingGrid` needs every cell to carry its position, which a column renderer that
 * decides by itself whether a figure is a button cannot give). Positions are read from the DOM: Left/Right step along the row, Up/Down
 * to the next row's button in the same column (rows without one are skipped), Home/End to the row's first/last button, with Ctrl to
 * the table's. Exactly one button has `tabIndex` 0 -- the last one focused, else the first -- so a long page does not cost a dozen Tab
 * presses per chart. Attach `ref`, `onKeyDown` and `onFocus` to an element wrapping the table.
 */
export function useRovingButtons() {
  const ref = useRef<HTMLDivElement>(null);
  const stop = useRef<HTMLElement | null>(null);

  const buttons = useCallback(() => Array.from(ref.current?.querySelectorAll<HTMLElement>("button") ?? []), []);

  /** Marks the tab stop on the DOM (the buttons are drawn by cell renderers, which know nothing of it). */
  const mark = useCallback(() => {
    const all = buttons();
    const active = stop.current !== null && all.includes(stop.current) ? stop.current : (all[0] ?? null);
    stop.current = active;
    for (const button of all) button.tabIndex = button === active ? 0 : -1;
  }, [buttons]);

  // After every render: figures that appeared or vanished (a re-derive) must not leave the table without a tab stop.
  useLayoutEffect(mark);

  const onFocus = (event: FocusEvent<HTMLElement>) => {
    const target = event.target as HTMLElement;
    if (target.tagName !== "BUTTON") return;
    stop.current = target;
    mark();
    // The table is revealed by this very focus (`:focus-within`), after the browser has scrolled: bring the button into view again.
    requestAnimationFrame(() => target.scrollIntoView?.({ block: "nearest", inline: "nearest" }));
  };

  const onKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    const from = event.target as HTMLElement;
    if (from.tagName !== "BUTTON" || event.altKey || event.metaKey) return;
    const all = buttons();
    const rowOf = (button: HTMLElement) => button.closest("tr");
    const colOf = (button: HTMLElement) => button.closest("td")?.cellIndex ?? -1;
    const sameRow = all.filter((button) => rowOf(button) === rowOf(from));
    const at = sameRow.indexOf(from);
    let to: HTMLElement | undefined;
    switch (event.key) {
      case "ArrowRight":
        to = sameRow[at + 1];
        break;
      case "ArrowLeft":
        to = sameRow[at - 1];
        break;
      case "ArrowDown":
        to = all.slice(all.indexOf(from) + 1).find((button) => colOf(button) === colOf(from) && rowOf(button) !== rowOf(from));
        break;
      case "ArrowUp":
        to = all
          .slice(0, all.indexOf(from))
          .reverse()
          .find((button) => colOf(button) === colOf(from) && rowOf(button) !== rowOf(from));
        break;
      case "Home":
        to = event.ctrlKey ? all[0] : sameRow[0];
        break;
      case "End":
        to = event.ctrlKey ? all.at(-1) : sameRow.at(-1);
        break;
      default:
        return;
    }
    event.preventDefault();
    to?.focus();
  };

  return { ref, onKeyDown, onFocus };
}
