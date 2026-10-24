import { afterEach, describe, expect, test } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { setTelemetryPanelOpen, useTelemetryPanelOpen } from "./useTelemetryPanel";

describe("useTelemetryPanelOpen", () => {
  afterEach(() => {
    localStorage.clear();
  });

  test("is closed by default, follows a write in every mounted consumer and survives a remount", () => {
    const first = renderHook(() => useTelemetryPanelOpen());
    const second = renderHook(() => useTelemetryPanelOpen());
    expect(first.result.current).toBe(false);
    act(() => setTelemetryPanelOpen(true));
    expect(first.result.current).toBe(true);
    expect(second.result.current).toBe(true);
    expect(localStorage.getItem("flow.viewSettings.telemetry.panelOpen")).toBe("true");
    first.unmount();
    expect(renderHook(() => useTelemetryPanelOpen()).result.current).toBe(true);
    act(() => setTelemetryPanelOpen(false));
    expect(second.result.current).toBe(false);
  });

  test("closing hands focus to the page's main region", () => {
    const main = document.createElement("main");
    main.id = "main-content";
    main.tabIndex = -1;
    document.body.append(main);
    try {
      act(() => setTelemetryPanelOpen(true));
      expect(document.activeElement).not.toBe(main);
      act(() => setTelemetryPanelOpen(false));
      expect(document.activeElement).toBe(main);
    } finally {
      main.remove();
    }
  });

  test("anything but a stored true reads as closed", () => {
    localStorage.setItem("flow.viewSettings.telemetry.panelOpen", '"yes"');
    expect(renderHook(() => useTelemetryPanelOpen()).result.current).toBe(false);
    localStorage.setItem("flow.viewSettings.telemetry.panelOpen", "not json");
    expect(renderHook(() => useTelemetryPanelOpen()).result.current).toBe(false);
  });
});
