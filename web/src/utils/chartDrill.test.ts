import { describe, expect, test, vi } from "vitest";
import { barDoubleClickProps } from "./chartDrill";

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
