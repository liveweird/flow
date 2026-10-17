import type { ReactNode } from "react";
import { describe, expect, test } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { useAgingKind } from "./useAgingKind";
import { useThroughputView } from "./useThroughputView";

const wrapper = (route: string) =>
  function Wrapper({ children }: { children: ReactNode }) {
    return <MemoryRouter initialEntries={[route]}>{children}</MemoryRouter>;
  };

const KEY = (screen: string) => `flow.viewSettings.${screen}.filter`;
const stored = (screen: string) => JSON.parse(localStorage.getItem(KEY(screen)) ?? "null") as string | null;

describe("the page-local switches are part of the screen's remembered filter", () => {
  test("throughput's chart view is written over the existing memory, and its default is the absence", () => {
    localStorage.setItem(KEY("reports.throughput"), JSON.stringify("lastSprints=6&teamId=2"));
    const { result } = renderHook(() => ({ view: useThroughputView(), location: useLocation() }), {
      wrapper: wrapper("/reports/throughput?teamId=1&lastSprints=3"),
    });
    act(() => result.current.view.setView("CUMULATIVE"));
    // The filter in the URL came from a link and is never stored: only the switch changes in the memory.
    expect(stored("reports.throughput")).toBe("lastSprints=6&teamId=2&view=cumulative");
    expect(result.current.location.search).toContain("view=cumulative");
    act(() => result.current.view.setView("PERIOD"));
    expect(stored("reports.throughput")).toBe("lastSprints=6&teamId=2");
  });

  test("with no memory the switch alone is remembered, never the link's filter", () => {
    const { result } = renderHook(() => ({ view: useThroughputView() }), {
      wrapper: wrapper("/reports/throughput?teamId=1&lastSprints=3&accountId=acc-ann"),
    });
    act(() => result.current.view.setView("CUMULATIVE"));
    expect(stored("reports.throughput")).toBe("view=cumulative");
  });

  test("aging WIP's Tasks | Epics switch is stored the same way", () => {
    localStorage.setItem(KEY("reports.aging-wip"), JSON.stringify("domain=FLO"));
    const { result } = renderHook(() => ({ kind: useAgingKind(), location: useLocation() }), {
      wrapper: wrapper("/reports/aging-wip?teamId=1"),
    });
    act(() => result.current.kind.setKind("EPIC"));
    expect(stored("reports.aging-wip")).toBe("domain=FLO&kind=epics");
    act(() => result.current.kind.setKind("TASK"));
    expect(stored("reports.aging-wip")).toBe("domain=FLO");
  });
});
