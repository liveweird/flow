import type { ReactNode } from "react";
import { afterEach, describe, expect, test, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { FILTERS } from "../test/reportFixtures";
import { useReportFilter } from "./useReportFilter";
import { useThroughputView } from "./useThroughputView";

const KEY = "reports.throughput";
const STORED = `flow.viewSettings.${KEY}.filter`;
const stored = () => JSON.parse(localStorage.getItem(STORED) ?? "null") as string | null;

function renderFilter(route: string) {
  return renderHook(
    () => ({ hook: useReportFilter(FILTERS, KEY), view: useThroughputView(), location: useLocation() }),
    { wrapper: ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={[route]}>{children}</MemoryRouter> },
  );
}

// Europe/Warsaw (the fixtures' zone): noon UTC is the same calendar day there.
const at = (iso: string) => vi.useFakeTimers({ toFake: ["Date"], now: new Date(`${iso}T12:00:00Z`) });

describe("remembered relative presets", () => {
  afterEach(() => vi.useRealTimers());

  test("a preset is stored symbolically beside its dates, a custom range is not", () => {
    at("2026-03-10");
    const { result } = renderFilter("/reports/throughput?teamId=1");
    act(() => result.current.hook.setFilter({ teamId: 1, from: "2026-02-09", to: "2026-03-10" }));
    expect(stored()).toBe("from=2026-02-09&to=2026-03-10&teamId=1&preset=last30");
    act(() => result.current.hook.setFilter({ teamId: 1, from: "2026-02-01", to: "2026-03-10" }));
    expect(stored()).toBe("from=2026-02-01&to=2026-03-10&teamId=1");
  });

  test("a restore N days later resolves the preset against today", async () => {
    at("2026-03-15");
    localStorage.setItem(STORED, JSON.stringify("from=2026-02-09&to=2026-03-10&teamId=1&preset=last30"));
    const { result } = renderFilter("/reports/throughput");
    expect(result.current.hook.filter).toEqual({ from: "2026-02-14", to: "2026-03-15", teamId: 1 });
    await waitFor(() => expect(result.current.location.search).toBe("?from=2026-02-14&to=2026-03-15&teamId=1"));
  });

  test("month-based presets move with the calendar too", () => {
    at("2026-04-02");
    localStorage.setItem(STORED, JSON.stringify("from=2026-03-01&to=2026-03-10&preset=thisMonth"));
    expect(renderFilter("/reports/throughput").result.current.hook.filter).toEqual({ from: "2026-04-01", to: "2026-04-02" });
  });

  test("a custom range and an unknown preset id restore verbatim", () => {
    at("2026-03-15");
    localStorage.setItem(STORED, JSON.stringify("from=2026-01-05&to=2026-01-20&teamId=1"));
    expect(renderFilter("/reports/throughput").result.current.hook.filter).toEqual({ from: "2026-01-05", to: "2026-01-20", teamId: 1 });
    localStorage.setItem(STORED, JSON.stringify("from=2026-01-05&to=2026-01-20&preset=bogus"));
    expect(renderFilter("/reports/throughput").result.current.hook.filter).toEqual({ from: "2026-01-05", to: "2026-01-20" });
  });

  test("a preset in a memory without dates (sprint-relative periods) changes nothing", () => {
    at("2026-03-15");
    localStorage.setItem(STORED, JSON.stringify("lastSprints=3&preset=last30"));
    expect(renderFilter("/reports/throughput").result.current.hook.filter).toEqual({ lastSprints: 3 });
  });

  test("a page-local switch change leaves the recorded preset alone", () => {
    at("2026-03-10");
    const { result } = renderFilter("/reports/throughput?teamId=1");
    act(() => result.current.hook.setFilter({ teamId: 1, from: "2026-02-09", to: "2026-03-10" }));
    act(() => result.current.view.setView("CUMULATIVE"));
    expect(stored()).toBe("from=2026-02-09&to=2026-03-10&teamId=1&preset=last30&view=cumulative");
  });
});
