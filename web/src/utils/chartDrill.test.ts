import { describe, expect, test, vi } from "vitest";
import { barDoubleClickProps, dotClickProps, dotDoubleClickProps } from "./chartDrill";

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

describe("dotClickProps", () => {
  test("adds nothing without a handler", () => {
    expect(dotClickProps("value", undefined)).toEqual({});
  });

  test("names the plotted row's label (a trend row's window end) and the series, from whichever argument carries the data row", () => {
    const onHit = vi.fn();
    const props = dotClickProps("p90", onHit) as { style: object; onClick: (...args: unknown[]) => void };
    expect(props.style).toEqual({ cursor: "pointer" });
    props.onClick({ payload: { label: "2026-08-11" } }, { type: "click" });
    props.onClick({ type: "click" }, { payload: { label: "2026-08-25" } });
    expect(onHit.mock.calls).toEqual([[{ rowId: "2026-08-11", series: "p90" }], [{ rowId: "2026-08-25", series: "p90" }]]);
  });

  test("ignores a click that names no label", () => {
    const onHit = vi.fn();
    const props = dotClickProps("value", onHit) as { onClick: (...args: unknown[]) => void };
    props.onClick({ payload: {} }, null);
    props.onClick({ payload: { label: 3 } }, null);
    props.onClick(undefined, "x");
    expect(onHit).not.toHaveBeenCalled();
  });
});
