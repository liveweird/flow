import type { ReactNode } from "react";
import { afterEach, describe, expect, test } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { MemoryRouter, useLocation, useNavigate } from "react-router-dom";
import { useReportDrill } from "./useReportDrill";

const WEEK = { kind: "bucket", bucketStart: "2026-09-07" } as const;

/** The hook beside the router's own location and navigate, so a test reads the address bar and presses browser Back. */
function useHarness(filterKey: string, anchor = "card") {
  const drill = useReportDrill(filterKey, anchor);
  const location = useLocation();
  const navigate = useNavigate();
  return { drill, location, navigate };
}

function setup(initial = "/reports/throughput?teamId=1", filterKey = "k1") {
  const wrapper = ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={[initial]}>{children}</MemoryRouter>;
  return renderHook(({ key }) => useHarness(key), { wrapper, initialProps: { key: filterKey } });
}

afterEach(() => {
  document.body.innerHTML = "";
});

describe("useReportDrill", () => {
  test("no drill param is no target", () => {
    const { result } = setup();
    expect(result.current.drill.target).toBeNull();
  });

  test("open PUSHES the param and keeps the rest of the query; browser Back closes it", () => {
    const { result } = setup();
    act(() => result.current.drill.open(WEEK));
    expect(result.current.drill.target).toEqual(WEEK);
    expect(result.current.location.search).toBe("?teamId=1&drill=bucket%3A2026-09-07");
    act(() => result.current.navigate(-1));
    expect(result.current.drill.target).toBeNull();
    expect(result.current.location.search).toBe("?teamId=1");
  });

  test("close after an open steps back one entry instead of stacking another", () => {
    const { result } = setup();
    act(() => result.current.drill.open(WEEK));
    act(() => result.current.drill.close());
    expect(result.current.drill.target).toBeNull();
    expect(result.current.location.search).toBe("?teamId=1");
    // Back again leaves the page's own entry: the history was never longer than open + close.
    expect(result.current.location.state).toBeNull();
  });

  test("a deep link has nothing to step back to: close drops the param in place", () => {
    const { result } = setup("/reports/throughput?teamId=1&drill=bucket:2026-09-07");
    expect(result.current.drill.target).toEqual(WEEK);
    act(() => result.current.drill.close());
    expect(result.current.drill.target).toBeNull();
    expect(result.current.location.search).toBe("?teamId=1");
  });

  test("setSegment REPLACES the entry: one Back from a segment still closes the drill", () => {
    const { result } = setup();
    act(() => result.current.drill.open(WEEK));
    act(() => result.current.drill.setSegment("712020:bob"));
    expect(result.current.drill.target).toEqual({ ...WEEK, segmentAccountId: "712020:bob" });
    act(() => result.current.drill.setSegment(undefined));
    expect(result.current.drill.target).toEqual(WEEK);
    act(() => result.current.drill.close());
    expect(result.current.drill.target).toBeNull();
    expect(result.current.location.search).toBe("?teamId=1");
  });

  test("setSegment without an open drill does nothing", () => {
    const { result } = setup();
    act(() => result.current.drill.setSegment("acc-bob"));
    expect(result.current.location.search).toBe("?teamId=1");
  });

  test("a changed filter closes an open drill (in place); an unchanged one leaves it alone", () => {
    const { result, rerender } = setup();
    act(() => result.current.drill.open(WEEK));
    rerender({ key: "k1" });
    expect(result.current.drill.target).toEqual(WEEK);
    rerender({ key: "k2" });
    expect(result.current.drill.target).toBeNull();
    expect(result.current.location.search).toBe("?teamId=1");
    // Replaced, not pushed: Back leaves the page's entry rather than re-opening the stale drill.
    act(() => result.current.navigate(-1));
    expect(result.current.location.pathname).toBe("/reports/throughput");
  });

  test("a changed filter with no drill open does nothing", () => {
    const { result, rerender } = setup();
    rerender({ key: "k2" });
    expect(result.current.location.search).toBe("?teamId=1");
  });

  test("the first render never counts as a filter change (a deep link survives the mount)", () => {
    const { result } = setup("/reports/throughput?teamId=1&drill=bucket:2026-09-07", "k9");
    expect(result.current.drill.target).toEqual(WEEK);
  });

  test("other location state survives open and a filter-change close", () => {
    const wrapper = ({ children }: { children: ReactNode }) => (
      <MemoryRouter initialEntries={[{ pathname: "/reports/throughput", search: "?teamId=1", state: { from: "elsewhere" } }]}>{children}</MemoryRouter>
    );
    const { result, rerender } = renderHook(({ key }) => useHarness(key), { wrapper, initialProps: { key: "k1" } });
    act(() => result.current.drill.open(WEEK));
    expect(result.current.location.state).toEqual({ from: "elsewhere", drillOpened: true });
    rerender({ key: "k2" });
    expect(result.current.location.state).toEqual({ from: "elsewhere" });
  });

  test("shown keeps the last bar after the drill closes, for the drawer's exit transition, and follows the next one", () => {
    const { result } = setup();
    expect(result.current.drill.shown).toBeNull();
    act(() => result.current.drill.open(WEEK));
    expect(result.current.drill.shown).toEqual(WEEK);
    act(() => result.current.drill.close());
    expect(result.current.drill.target).toBeNull();
    expect(result.current.drill.shown).toEqual(WEEK);
    const next = { kind: "bucket", bucketStart: "2026-09-14" } as const;
    act(() => result.current.drill.open(next));
    expect(result.current.drill.shown).toEqual(next);
    act(() => result.current.drill.setSegment("acc-bob"));
    expect(result.current.drill.shown).toEqual({ ...next, segmentAccountId: "acc-bob" });
  });

  describe("focus", () => {
    test("returns to the trigger passed to open even though a click never focused it (Safari, Firefox)", () => {
      const trigger = document.createElement("button");
      document.body.append(trigger);
      const { result } = setup();
      expect(trigger).not.toHaveFocus();
      act(() => result.current.drill.open(WEEK, { trigger }));
      act(() => result.current.drill.close());
      expect(trigger).toHaveFocus();
    });

    test("returns to the element that was focused when the drill opened, however it closed", () => {
      const trigger = document.createElement("button");
      document.body.append(trigger);
      trigger.focus();
      const { result } = setup();
      act(() => result.current.drill.open(WEEK));
      document.body.focus();
      act(() => result.current.drill.close());
      expect(trigger).toHaveFocus();
    });

    test("falls back to the chart card carrying data-drill-anchor when nothing focusable opened it (a pointer double-click)", () => {
      const card = document.createElement("div");
      card.tabIndex = -1;
      card.dataset.drillAnchor = "card";
      document.body.append(card);
      const { result } = setup();
      act(() => result.current.drill.open(WEEK));
      act(() => result.current.navigate(-1));
      expect(card).toHaveFocus();
    });

    test("a page with several chart cards names the card the bar was in: focus goes to that one, then back to the default", () => {
      const period = document.createElement("div");
      const sprint = document.createElement("div");
      for (const [card, name] of [[period, "card"], [sprint, "sprint-card"]] as const) {
        card.tabIndex = -1;
        card.dataset.drillAnchor = name;
        document.body.append(card);
      }
      const { result } = setup();
      act(() => result.current.drill.open({ kind: "sprint", teamId: 1, sprintId: 12, bucket: "DELIVERED" }, { anchor: "sprint-card" }));
      act(() => result.current.navigate(-1));
      expect(sprint).toHaveFocus();
      sprint.blur();
      act(() => result.current.drill.open(WEEK));
      act(() => result.current.navigate(-1));
      expect(period).toHaveFocus();
    });

    test("an anchor that depends on the bar: a deep link with a sprint bar returns focus to the sprint card", () => {
      const period = document.createElement("div");
      const sprint = document.createElement("div");
      for (const [card, name] of [[period, "period"], [sprint, "sprint"]] as const) {
        card.tabIndex = -1;
        card.dataset.drillAnchor = name;
        document.body.append(card);
      }
      const wrapper = ({ children }: { children: ReactNode }) => (
        <MemoryRouter initialEntries={["/reports/throughput?drill=sprint:1:12:DELIVERED"]}>{children}</MemoryRouter>
      );
      const { result } = renderHook(
        () => {
          const drill = useReportDrill("k1", (target) => (target.kind === "sprint" ? "sprint" : "period"));
          return { drill, location: useLocation() };
        },
        { wrapper },
      );
      act(() => result.current.drill.close());
      expect(sprint).toHaveFocus();
      expect(period).not.toHaveFocus();
    });

    test("a trigger that left the page falls back to the card too", () => {
      const trigger = document.createElement("button");
      const card = document.createElement("div");
      card.tabIndex = -1;
      card.dataset.drillAnchor = "card";
      document.body.append(trigger, card);
      trigger.focus();
      const { result } = setup();
      act(() => result.current.drill.open(WEEK));
      trigger.remove();
      act(() => result.current.drill.close());
      expect(card).toHaveFocus();
    });

    test("nothing is focused while no drill has been closed yet", () => {
      const card = document.createElement("div");
      card.tabIndex = -1;
      card.dataset.drillAnchor = "card";
      document.body.append(card);
      setup();
      expect(card).not.toHaveFocus();
    });
  });
});
