import { describe, expect, test } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { rovingProps, useRovingGrid, type GridPos } from "./useRovingGrid";

/**
 * A 3 × 3 body (rows 1…3, columns 0…3) under two header rows: the lower one (r 0) holds A (col 1), B (col 2) and C (col 3),
 * the upper one (r -1) a spanning "AB" over cols 1–2 and "C2" over col 3; the corner cell spans both header rows (r -1, col 0).
 */
const ROWS = 3;
const COLS = 3;
const CORNER_ROW = -1;

function Grid({ blocked = false }: { blocked?: boolean }) {
  const { ref, active, onKeyDown, onFocus, tabStop } = useRovingGrid({
    bounds: { rMin: CORNER_ROW, rMax: ROWS, cMax: COLS },
    isValid: (pos: GridPos) => !blocked && (pos.r >= 1 ? pos.r <= ROWS : pos.r >= CORNER_ROW) && pos.c <= COLS,
    fallback: { r: 1, c: 0 },
  });
  const at = (r: number, c: number) => active.r === r && active.c === c;
  return (
    <>
      <table ref={ref} role="grid" aria-label="g" onKeyDown={onKeyDown} onFocus={onFocus}>
        <thead>
          <tr>
            <th rowSpan={2} {...rovingProps(CORNER_ROW, 0, at(CORNER_ROW, 0))}>
              corner
            </th>
            <th colSpan={2} {...rovingProps(CORNER_ROW, 1, at(CORNER_ROW, 1), 2)}>
              AB
            </th>
            <th {...rovingProps(CORNER_ROW, 3, at(CORNER_ROW, 3))}>C2</th>
          </tr>
          <tr>
            <th {...rovingProps(0, 1, at(0, 1))}>A</th>
            <th {...rovingProps(0, 2, at(0, 2))}>B</th>
            <th {...rovingProps(0, 3, at(0, 3))}>C</th>
          </tr>
        </thead>
        <tbody>
          {[1, 2, 3].map((r) => (
            <tr key={r}>
              {[0, 1, 2, 3].map((c) => (
                <td key={c} {...rovingProps(r, c, at(r, c))}>
                  {`r${r}c${c}`}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      <button onClick={() => tabStop()?.focus()}>to tab stop</button>
    </>
  );
}

const cell = (name: string) => screen.getByText(name);
/** Focus inside `act`, so the focus handler's state update is flushed before the next assertion. */
const focus = (name: string) => act(() => cell(name).focus());
const press = (name: string, key: string, init: Partial<KeyboardEventInit> = {}) => fireEvent.keyDown(cell(name), { key, ...init });

describe("rovingProps", () => {
  test("carries the position, the covered end column and the tab stop", () => {
    expect(rovingProps(2, 1, true)).toEqual({ "data-grid-pos": "2,1", "data-grid-end": 1, tabIndex: 0 });
    expect(rovingProps(-1, 1, false, 2)).toEqual({ "data-grid-pos": "-1,1", "data-grid-end": 2, tabIndex: -1 });
  });
});

describe("useRovingGrid", () => {
  test("exactly one element is a tab stop: the first body cell to begin with", () => {
    render(<Grid />);
    const stops = document.querySelectorAll('[data-grid-pos][tabindex="0"]');
    expect(stops).toHaveLength(1);
    expect(stops[0]).toBe(cell("r1c0"));
  });

  test("arrows move focus one cell and stop at the edges of the body", () => {
    render(<Grid />);
    focus("r1c0");
    press("r1c0", "ArrowRight");
    expect(cell("r1c1")).toHaveFocus();
    press("r1c1", "ArrowDown");
    expect(cell("r2c1")).toHaveFocus();
    press("r2c1", "ArrowLeft");
    expect(cell("r2c0")).toHaveFocus();
    press("r2c0", "ArrowLeft");
    expect(cell("r2c0")).toHaveFocus();
    press("r2c0", "ArrowUp");
    expect(cell("r1c0")).toHaveFocus();
    focus("r3c3");
    press("r3c3", "ArrowRight");
    press("r3c3", "ArrowDown");
    expect(cell("r3c3")).toHaveFocus();
  });

  test("arrow keys are prevented from scrolling; other keys are left alone", () => {
    render(<Grid />);
    focus("r1c0");
    expect(fireEvent.keyDown(cell("r1c0"), { key: "a" })).toBe(true);
    expect(fireEvent.keyDown(cell("r1c0"), { key: "PageDown" })).toBe(true);
    expect(cell("r1c0")).toHaveFocus();
    expect(fireEvent.keyDown(cell("r1c0"), { key: "ArrowDown" })).toBe(false);
    expect(cell("r2c0")).toHaveFocus();
  });

  test("Home/End go to the row's first/last cell; with Ctrl, to the grid's first/last", () => {
    render(<Grid />);
    focus("r2c2");
    press("r2c2", "End");
    expect(cell("r2c3")).toHaveFocus();
    press("r2c3", "Home");
    expect(cell("r2c0")).toHaveFocus();
    press("r2c0", "End", { ctrlKey: true });
    expect(cell("r3c3")).toHaveFocus();
    press("r3c3", "Home", { ctrlKey: true });
    expect(cell("corner")).toHaveFocus();
  });

  test("ArrowUp walks into the header rows, skipping the holes a spanning cell leaves", () => {
    render(<Grid />);
    focus("r1c2");
    press("r1c2", "ArrowUp");
    expect(cell("B")).toHaveFocus();
    // the spanning AB covers col 2 one row up
    press("B", "ArrowUp");
    expect(cell("AB")).toHaveFocus();
    press("AB", "ArrowUp");
    expect(cell("AB")).toHaveFocus();
    // from the corner (row -1) a step down skips the hole under it (r 0, col 0) into the body
    focus("corner");
    press("corner", "ArrowDown");
    expect(cell("r1c0")).toHaveFocus();
  });

  test("ArrowRight/Left step over a spanning header by the columns it covers", () => {
    render(<Grid />);
    focus("corner");
    press("corner", "ArrowRight");
    expect(cell("AB")).toHaveFocus();
    press("AB", "ArrowRight");
    expect(cell("C2")).toHaveFocus();
    press("C2", "ArrowLeft");
    expect(cell("AB")).toHaveFocus();
  });

  test("Alt and Meta chords are not the grid's", () => {
    render(<Grid />);
    focus("r1c0");
    expect(fireEvent.keyDown(cell("r1c0"), { key: "ArrowRight", altKey: true })).toBe(true);
    expect(fireEvent.keyDown(cell("r1c0"), { key: "ArrowRight", metaKey: true })).toBe(true);
    expect(cell("r1c0")).toHaveFocus();
  });

  test("a key from outside any grid element does nothing", () => {
    render(<Grid />);
    expect(fireEvent.keyDown(screen.getByRole("grid"), { key: "ArrowRight" })).toBe(true);
  });

  test("whatever takes focus becomes the one tab stop, and tabStop() finds it", () => {
    render(<Grid />);
    focus("r3c2");
    expect(cell("r3c2")).toHaveAttribute("tabindex", "0");
    expect(cell("r1c0")).toHaveAttribute("tabindex", "-1");
    expect(document.querySelectorAll('[data-grid-pos][tabindex="0"]')).toHaveLength(1);
    focus("r1c1");
    cell("r3c2").blur();
    fireEvent.click(screen.getByText("to tab stop"));
    expect(cell("r1c1")).toHaveFocus();
  });

  test("focus on a spanning header records its first column", () => {
    render(<Grid />);
    focus("AB");
    expect(cell("AB")).toHaveAttribute("tabindex", "0");
  });

  test("a tab stop whose position no longer exists falls back", () => {
    const { rerender } = render(<Grid />);
    focus("r2c2");
    expect(cell("r2c2")).toHaveAttribute("tabindex", "0");
    rerender(<Grid blocked />);
    expect(cell("r2c2")).toHaveAttribute("tabindex", "-1");
    expect(cell("r1c0")).toHaveAttribute("tabindex", "0");
  });
});
