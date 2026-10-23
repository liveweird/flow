import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { useDeepDiveTooltip } from "./useDeepDiveTooltip";

// A bare grid: the hook only needs delegated handlers on a table, `td[data-cell]` cells and a region around the table (the scroller).
function Harness() {
  const { tip, tipRef, gridProps, tipProps } = useDeepDiveTooltip();
  return (
    <div role="region" aria-label="scroller">
      <table {...gridProps}>
        <thead>
          <tr>
            <th>cell A</th>
            <th>cell B</th>
            <th>other</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td data-cell data-row="0" data-col="0" tabIndex={0}>
              first
            </td>
            <td data-cell data-row="0" data-col="1" tabIndex={0}>
              second
            </td>
            <td tabIndex={0}>plain</td>
          </tr>
        </tbody>
      </table>
      <button>elsewhere</button>
      {tip && (
        <div ref={tipRef} data-testid="tip" {...tipProps}>
          {`${tip.row}:${tip.col}:${tip.source}:${tip.top}`}
        </div>
      )}
    </div>
  );
}

const first = () => screen.getByText("first");
const second = () => screen.getByText("second");
const tipText = () => screen.queryByTestId("tip")?.textContent ?? null;
const advance = (ms: number) => act(() => vi.advanceTimersByTime(ms));

describe("useDeepDiveTooltip", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  test("the pointer resting on a cell opens its card; leaving closes it after a short grace, and a pointer on the card keeps it", () => {
    render(<Harness />);
    fireEvent.mouseOver(first());
    expect(tipText()).toBe("0:0:pointer:0");
    fireEvent.mouseLeave(first().closest("table")!);
    advance(60);
    fireEvent.mouseEnter(screen.getByTestId("tip"));
    advance(500);
    expect(tipText()).toBe("0:0:pointer:0");
    fireEvent.mouseLeave(screen.getByTestId("tip"));
    advance(119);
    expect(tipText()).not.toBeNull();
    advance(1);
    expect(tipText()).toBeNull();
  });

  test("a different cell only replaces an open card after a delay (so the pointer can cross to the card); the same cell does nothing", () => {
    render(<Harness />);
    fireEvent.mouseOver(first());
    fireEvent.mouseOver(first());
    expect(tipText()).toBe("0:0:pointer:0");
    fireEvent.mouseOver(second());
    expect(tipText()).toBe("0:0:pointer:0");
    advance(119);
    expect(tipText()).toBe("0:0:pointer:0");
    advance(1);
    expect(tipText()).toBe("0:1:pointer:0");
  });

  test("moving off every cell onto plain content closes the card softly", () => {
    render(<Harness />);
    fireEvent.mouseOver(first());
    fireEvent.mouseOver(screen.getByText("plain"));
    expect(tipText()).not.toBeNull();
    advance(120);
    expect(tipText()).toBeNull();
  });

  test("a card the keyboard opened stays while the pointer comes and goes over the still-focused cell, and closes when focus leaves the grid", () => {
    render(<Harness />);
    act(() => first().focus());
    expect(tipText()).toBe("0:0:focus:0");
    fireEvent.mouseOver(first());
    fireEvent.mouseLeave(first().closest("table")!);
    advance(1000);
    expect(tipText()).toBe("0:0:focus:0");
    // focus moving to the next cell replaces it at once (no delay for the keyboard)
    act(() => second().focus());
    expect(tipText()).toBe("0:1:focus:0");
    // focus moving to non-cell content in the grid closes it
    act(() => screen.getByText("plain").focus());
    expect(tipText()).toBeNull();
    act(() => first().focus());
    expect(tipText()).not.toBeNull();
    act(() => screen.getByText("elsewhere").focus());
    expect(tipText()).toBeNull();
  });

  test("Escape dismisses the card and the dismissed cell stays quiet until another cell is visited", () => {
    render(<Harness />);
    fireEvent.mouseOver(first());
    fireEvent.keyDown(document, { key: "Enter" });
    expect(tipText()).not.toBeNull();
    fireEvent.keyDown(document, { key: "Escape" });
    expect(tipText()).toBeNull();
    fireEvent.mouseOver(first());
    expect(tipText()).toBeNull();
    fireEvent.mouseOver(second());
    expect(tipText()).toBe("0:1:pointer:0");
    fireEvent.mouseOver(first());
    advance(120);
    expect(tipText()).toBe("0:0:pointer:0");
  });

  test("a scroll elsewhere closes a pointer-opened card; a scroll inside the card never does", () => {
    render(<Harness />);
    fireEvent.mouseOver(first());
    fireEvent.scroll(screen.getByTestId("tip"));
    expect(tipText()).not.toBeNull();
    fireEvent.scroll(window);
    expect(tipText()).toBeNull();
  });

  test("a scroll repositions a focus-opened card and closes it only when its cell has left the scroller", () => {
    render(<Harness />);
    act(() => first().focus());
    const cellRect = vi.spyOn(first(), "getBoundingClientRect");
    cellRect.mockReturnValue({ top: 40, bottom: 60, left: 10, right: 50 } as DOMRect);
    vi.spyOn(screen.getByRole("region"), "getBoundingClientRect").mockReturnValue({ top: 0, bottom: 100, left: 0, right: 200 } as DOMRect);
    fireEvent.scroll(window);
    expect(tipText()).toBe("0:0:focus:40");
    cellRect.mockReturnValue({ top: 140, bottom: 160, left: 10, right: 50 } as DOMRect);
    fireEvent.scroll(window);
    expect(tipText()).toBeNull();
  });

  test("a focus-opened card whose cell left the document closes on scroll", () => {
    const { unmount } = render(<Harness />);
    act(() => first().focus());
    const cell = first();
    vi.spyOn(cell, "isConnected", "get").mockReturnValue(false);
    fireEvent.scroll(window);
    expect(tipText()).toBeNull();
    unmount();
  });

  test("unmounting with a timer pending leaves nothing to fire", () => {
    const { unmount } = render(<Harness />);
    fireEvent.mouseOver(first());
    fireEvent.mouseOver(second());
    fireEvent.mouseLeave(first().closest("table")!);
    unmount();
    expect(() => advance(1000)).not.toThrow();
    expect(vi.getTimerCount()).toBe(0);
  });
});
