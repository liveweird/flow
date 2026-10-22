import { describe, expect, test, vi } from "vitest";
import { renderHook } from "@testing-library/react";
import type { DrillState } from "./useReportDrill";
import { useHistogramDrill } from "./useHistogramDrill";

function drillSpy() {
  const open = vi.fn();
  return { open, drill: { target: null, shown: null, open, setSegment: vi.fn(), close: vi.fn() } as DrillState };
}

describe("useHistogramDrill", () => {
  test("a bar of everyone opens the whole bar, a person's segment opens that person's share", () => {
    const { drill, open } = drillSpy();
    const { result } = renderHook(() => useHistogramDrill(drill));
    const trigger = document.createElement("button");
    result.current("AT_DONE")(3, undefined, trigger);
    expect(open).toHaveBeenLastCalledWith({ kind: "hist", view: "AT_DONE", bucketIndex: 3 }, { trigger, anchor: undefined });
    result.current("AT_DONE")(3, "acc-1");
    expect(open).toHaveBeenLastCalledWith(
      { kind: "hist", view: "AT_DONE", bucketIndex: 3, segmentAccountId: "acc-1" },
      { trigger: undefined, anchor: undefined },
    );
  });

  test("segment: none ignores the person (reports without people)", () => {
    const { drill, open } = drillSpy();
    const { result } = renderHook(() => useHistogramDrill(drill, { segment: "none" }));
    result.current("TASKS")(1, "acc-1");
    expect(open).toHaveBeenCalledWith({ kind: "hist", view: "TASKS", bucketIndex: 1 }, { trigger: undefined, anchor: undefined });
  });

  test("segment: required opens nothing for a bar with no person, and names the hook's card", () => {
    const { drill, open } = drillSpy();
    const { result } = renderHook(() => useHistogramDrill(drill, { anchor: "card", segment: "required" }));
    result.current("CYCLE")(2);
    expect(open).not.toHaveBeenCalled();
    result.current("CYCLE")(2, "acc-2");
    expect(open).toHaveBeenCalledWith(
      { kind: "hist", view: "CYCLE", bucketIndex: 2, segmentAccountId: "acc-2" },
      { trigger: undefined, anchor: "card" },
    );
  });

  test("an opener's own card wins over the hook's", () => {
    const { drill, open } = drillSpy();
    const { result } = renderHook(() => useHistogramDrill(drill, { anchor: "tasks" }));
    result.current("EPIC_WORKING_DAYS", "epics")(0);
    expect(open).toHaveBeenCalledWith({ kind: "hist", view: "EPIC_WORKING_DAYS", bucketIndex: 0 }, { trigger: undefined, anchor: "epics" });
  });
});
