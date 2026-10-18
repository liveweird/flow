import { describe, expect, test, vi } from "vitest";
import { barDoubleClickProps, dotDoubleClickProps } from "./chartDrill";

describe("barDoubleClickProps", () => {
  test("adds nothing without a handler, so a chart that is not drillable keeps its props", () => {
    expect(barDoubleClickProps("md", undefined)).toEqual({});
  });

  test("a pointer cursor and a double-click that reports the plotted row's id with the series", () => {
    const onHit = vi.fn();
    const props = barDoubleClickProps("p1", onHit) as { cursor: string; onDoubleClick: (entry: { payload?: unknown }) => void };
    expect(props.cursor).toBe("pointer");
    props.onDoubleClick({ payload: { id: "2026-09-07", label: "2026-09-07", md: 3 } });
    expect(onHit).toHaveBeenCalledExactlyOnceWith({ rowId: "2026-09-07", series: "p1" });
  });

  test("an entry without a string id (a payload recharts did not fill) is ignored, never a crash", () => {
    const onHit = vi.fn();
    const props = barDoubleClickProps("md", onHit) as { onDoubleClick: (entry: { payload?: unknown }) => void };
    props.onDoubleClick({});
    props.onDoubleClick({ payload: undefined });
    props.onDoubleClick({ payload: { id: 7 } });
    expect(onHit).not.toHaveBeenCalled();
  });
});

describe("dotDoubleClickProps", () => {
  test("adds nothing without a handler", () => {
    expect(dotDoubleClickProps("tasks", undefined)).toEqual({});
  });

  test("names the plotted row and the series, from whichever argument carries the data row", () => {
    const onHit = vi.fn();
    const props = dotDoubleClickProps("tasks", onHit) as { style: object; onDoubleClick: (...args: unknown[]) => void };
    expect(props.style).toEqual({ cursor: "pointer" });
    props.onDoubleClick({ payload: { id: "25" } }, { type: "dblclick" });
    props.onDoubleClick({ type: "dblclick" }, { payload: { id: "50" } });
    expect(onHit.mock.calls).toEqual([[{ rowId: "25", series: "tasks" }], [{ rowId: "50", series: "tasks" }]]);
  });

  test("ignores a hit that names no row", () => {
    const onHit = vi.fn();
    const props = dotDoubleClickProps("tasks", onHit) as { onDoubleClick: (...args: unknown[]) => void };
    props.onDoubleClick({ payload: {} }, null);
    props.onDoubleClick(undefined, "x");
    expect(onHit).not.toHaveBeenCalled();
  });
});
