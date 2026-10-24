import type { ReactNode } from "react";
import { describe, expect, test } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { MemoryRouter, useLocation, useNavigate, useNavigationType } from "react-router-dom";
import { FILTERS } from "../test/reportFixtures";
import { dropMember, dropDomainWithTeam, withFilterKey, type ReportFilterState } from "../utils/reportFilter";
import { useReportFilter } from "./useReportFilter";

const KEY = "reports.throughput";
const STORED = `flow.viewSettings.${KEY}.filter`;
const TEAM = "flow.viewSettings.reports.teamId";

type Normalize = (filter: ReportFilterState) => ReportFilterState;

/** The hook plus the router's view of the URL, so a test can see what was written and how (replace or push). */
function renderFilter(route: string, options: { normalize?: Normalize; teamRequired?: boolean; multiMember?: boolean; filters?: typeof FILTERS } = {}) {
  const { normalize, teamRequired, multiMember, filters = FILTERS } = options;
  return renderHook(
    () => ({
      hook: useReportFilter(filters, KEY, normalize, { teamRequired, multiMember }),
      location: useLocation(),
      navigationType: useNavigationType(),
      navigate: useNavigate(),
    }),
    { wrapper: ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={[route]}>{children}</MemoryRouter> },
  );
}

const urlOf = (result: { current: { location: { search: string } } }) => new URLSearchParams(result.current.location.search);
const stored = () => localStorage.getItem(STORED);
const store = (query: string) => localStorage.setItem(STORED, JSON.stringify(query));

describe("useReportFilter memory", () => {
  test("a bare visit restores the remembered filter and rewrites the URL with replace", async () => {
    store("from=2026-03-01&to=2026-03-31&teamId=2&domain=FLO&activityType=Bug&view=cumulative");
    const { result } = renderFilter("/reports/throughput");
    expect(result.current.hook.filter).toEqual({ from: "2026-03-01", to: "2026-03-31", teamId: 2, domain: "FLO", activityType: "Bug" });
    await waitFor(() => expect(urlOf(result).get("teamId")).toBe("2"));
    expect(urlOf(result).get("from")).toBe("2026-03-01");
    expect(urlOf(result).get("domain")).toBe("FLO");
    expect(urlOf(result).get("view")).toBe("cumulative");
    // a rewrite, not a navigation: Back would leave the page, the restore has no entry of its own
    expect(result.current.navigationType).toBe("REPLACE");
  });

  test("a restore is not a user change: the memory is not written by it", async () => {
    store("lastSprints=3&teamId=1");
    const { result } = renderFilter("/reports/throughput");
    await waitFor(() => expect(urlOf(result).get("lastSprints")).toBe("3"));
    expect(JSON.parse(stored() ?? "null")).toBe("lastSprints=3&teamId=1");
  });

  test("a link that carries any filter param wins and is not stored over the memory", async () => {
    store("lastSprints=3&teamId=2");
    const { result } = renderFilter("/reports/throughput?from=2026-01-01&to=2026-01-31");
    expect(result.current.hook.filter).toEqual({ from: "2026-01-01", to: "2026-01-31" });
    expect(urlOf(result).has("teamId")).toBe(false);
    expect(JSON.parse(stored() ?? "null")).toBe("lastSprints=3&teamId=2");
  });

  test("the user's own change writes the memory; a link's does not until the user changes something", () => {
    const { result } = renderFilter("/reports/throughput?lastSprints=3");
    expect(stored()).toBeNull();
    act(() => result.current.hook.setFilter({ lastSprints: 6, domain: "FLO" }));
    expect(JSON.parse(stored() ?? "null")).toBe("lastSprints=6&domain=FLO");
  });

  test("a cleared filter is remembered as cleared: the bare URL it leaves is not restored over it", () => {
    store("lastSprints=3&teamId=1");
    const { result } = renderFilter("/reports/throughput?lastSprints=3&teamId=1");
    act(() => result.current.hook.setFilter({}));
    expect(JSON.parse(stored() ?? "null")).toBe("");
    expect(result.current.location.search).toBe("");
    expect(result.current.hook.filter).toEqual({});
  });

  test("values the reference data no longer knows are dropped: team and its people, sprint, domain, activity type", async () => {
    store("sprintId=99&teamId=99&accountId=acc-ann&domain=GONE&activityType=Nope&workCategory=Maintenance");
    const { result } = renderFilter("/reports/throughput");
    expect(result.current.hook.filter).toEqual({ workCategory: "Maintenance" });
    await waitFor(() => expect(result.current.location.search).toBe("?workCategory=Maintenance"));
  });

  test("a person who left the team and a sprint another team owns are dropped", () => {
    store("sprintId=21&teamId=1&accountId=acc-cy");
    const { result } = renderFilter("/reports/throughput");
    expect(result.current.hook.filter).toEqual({ teamId: 1 });
  });

  test("several people are restored on a multi-member report, each checked against the team", () => {
    store("teamId=1&accountId=acc-ann&accountId=acc-ghost&accountId=acc-bob");
    const { result } = renderFilter("/reports/throughput", { multiMember: true });
    expect(result.current.hook.filter).toEqual({ teamId: 1, accountIds: ["acc-ann", "acc-bob"] });
  });

  test("params the report has no control for are not restored, and the page's own normalize decides the rest", () => {
    store("teamId=1&accountId=acc-ann&domain=FLO&by=STATUS");
    const { result } = renderFilter("/reports/throughput", { normalize: (filter) => dropMember(dropDomainWithTeam(filter)) });
    expect(result.current.hook.filter).toEqual({ teamId: 1 });
  });

  test("a team-required report keeps the remembered team's own choice, else the required-team rule", () => {
    store("lastSprints=3");
    const { result } = renderFilter("/reports/commitments", { teamRequired: true });
    expect(result.current.hook.filter).toEqual({ lastSprints: 3, teamId: 1 });
  });

  test("corrupt storage falls back to a plain bare visit", () => {
    localStorage.setItem(STORED, "{not json");
    const { result } = renderFilter("/reports/throughput");
    expect(result.current.hook.filter).toEqual({});
    localStorage.setItem(STORED, JSON.stringify({ not: "a string" }));
    expect(renderFilter("/reports/throughput").result.current.hook.filter).toEqual({});
  });

  test("the old reports.teamId key is the fallback for a screen with no memory of its own", async () => {
    localStorage.setItem(TEAM, "2");
    const { result } = renderFilter("/reports/throughput");
    expect(result.current.hook.filter).toEqual({ teamId: 2 });
    await waitFor(() => expect(urlOf(result).get("teamId")).toBe("2"));
  });

  test("a screen with a memory of its own does not take the old team over it", () => {
    localStorage.setItem(TEAM, "2");
    store("lastSprints=3");
    const { result } = renderFilter("/reports/throughput");
    expect(result.current.hook.filter).toEqual({ lastSprints: 3 });
  });

  test("an in-page link that narrows the report is the user's change; arriving and Back are not", async () => {
    store("lastSprints=3");
    const { result } = renderFilter("/reports/throughput?lastSprints=3");
    act(() => result.current.navigate("/reports/throughput?lastSprints=3&teamId=2"));
    await waitFor(() => expect(JSON.parse(stored() ?? "null")).toBe("lastSprints=3&teamId=2"));
    store("from=2026-01-01&to=2026-01-31");
    act(() => result.current.navigate(-1));
    await waitFor(() => expect(result.current.location.search).toBe("?lastSprints=3"));
    expect(JSON.parse(stored() ?? "null")).toBe("from=2026-01-01&to=2026-01-31");
  });

  test("a bare nav click while the page is open restores the memory, and stores nothing itself", async () => {
    const { result } = renderFilter("/reports/throughput");
    act(() => result.current.hook.setFilter({ lastSprints: 6, teamId: 1 }));
    act(() => result.current.navigate("/reports/throughput"));
    await waitFor(() => expect(result.current.location.search).toBe("?lastSprints=6&teamId=1"));
    expect(JSON.parse(stored() ?? "null")).toBe("lastSprints=6&teamId=1");
  });

  test("the reference data not loaded yet restores nothing, then restores when it arrives", async () => {
    store("lastSprints=3&teamId=1");
    const { result, rerender } = renderHook(
      ({ loaded }: { loaded: boolean }) => ({
        hook: useReportFilter(loaded ? FILTERS : undefined, KEY),
        location: useLocation(),
      }),
      {
        initialProps: { loaded: false },
        wrapper: ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={["/reports/throughput"]}>{children}</MemoryRouter>,
      },
    );
    expect(result.current.hook.filter).toEqual({});
    rerender({ loaded: true });
    await waitFor(() => expect(result.current.hook.filter).toEqual({ lastSprints: 3, teamId: 1 }));
    await waitFor(() => expect(result.current.location.search).toBe("?lastSprints=3&teamId=1"));
  });

  test("opening a drill on a link's filter is no change of the filter: the memory is left alone", async () => {
    store("from=2026-01-01&to=2026-01-31&teamId=2");
    const { result } = renderFilter("/reports/throughput?lastSprints=3");
    act(() => result.current.navigate("/reports/throughput?lastSprints=3&drill=bucket:2026-03-02"));
    await waitFor(() => expect(result.current.location.search).toContain("drill="));
    expect(JSON.parse(stored() ?? "null")).toBe("from=2026-01-01&to=2026-01-31&teamId=2");
    // a real narrowing afterwards is still the user's change
    act(() => result.current.navigate("/reports/throughput?lastSprints=3&teamId=1"));
    await waitFor(() => expect(JSON.parse(stored() ?? "null")).toBe("lastSprints=3&teamId=1"));
  });

  test("with no memory at all, a drill opened on a link stores nothing", async () => {
    const { result } = renderFilter("/reports/throughput?lastSprints=3");
    act(() => result.current.navigate("/reports/throughput?lastSprints=3&drill=bucket:2026-03-02"));
    await waitFor(() => expect(result.current.location.search).toContain("drill="));
    expect(stored()).toBeNull();
  });
});

describe("useReportFilter with a setter from an older render", () => {
  test("a member picked through a stale control keeps the period that landed after that render", () => {
    const { result } = renderFilter("/reports/commitments?teamId=1");
    // The control (Member) was rendered with this filter and setter, before the period changed.
    const stale = result.current.hook;
    act(() => result.current.hook.setFilter({ ...result.current.hook.filter, from: "2026-09-25", to: "2026-10-24" }));
    expect(urlOf(result).get("from")).toBe("2026-09-25");
    act(() => stale.setFilter(withFilterKey(stale.filter, "accountId", "acc-ann")));
    const url = urlOf(result);
    expect(url.get("from")).toBe("2026-09-25");
    expect(url.get("to")).toBe("2026-10-24");
    expect(url.get("teamId")).toBe("1");
    expect(url.get("accountId")).toBe("acc-ann");
    expect(result.current.hook.filter).toEqual({ from: "2026-09-25", to: "2026-10-24", teamId: 1, accountId: "acc-ann" });
    // the memory is the merged filter too, not the stale one
    // (a period matching a preset as of today also carries its `preset=` marker)
    expect(JSON.parse(stored() ?? "null")).toMatch(/^from=2026-09-25&to=2026-10-24&teamId=1&accountId=acc-ann/);
  });

  test("a stale period pick still swaps the whole period and keeps a member picked since", () => {
    const { result } = renderFilter("/reports/commitments?lastSprints=3&teamId=1");
    const stale = result.current.hook;
    act(() => result.current.hook.setFilter(withFilterKey(result.current.hook.filter, "accountId", "acc-ann")));
    act(() => stale.setFilter({ from: "2026-09-25", to: "2026-10-24", teamId: 1 }));
    expect(result.current.hook.filter).toEqual({ from: "2026-09-25", to: "2026-10-24", teamId: 1, accountId: "acc-ann" });
    expect(urlOf(result).has("lastSprints")).toBe(false);
  });

  test("a URL changed by navigation, not by the hook, is the base of a stale write too", async () => {
    const { result } = renderFilter("/reports/commitments?teamId=1");
    const stale = result.current.hook;
    act(() => result.current.navigate("/reports/commitments?teamId=1&from=2026-09-25&to=2026-10-24&foo=bar"));
    await waitFor(() => expect(urlOf(result).get("from")).toBe("2026-09-25"));
    act(() => stale.setFilter(withFilterKey(stale.filter, "accountId", "acc-ann")));
    const url = urlOf(result);
    expect(url.get("from")).toBe("2026-09-25");
    expect(url.get("accountId")).toBe("acc-ann");
    expect(url.get("foo")).toBe("bar");
  });

  test("two stale writes in one event (period, then member) keep both", () => {
    const { result } = renderFilter("/reports/commitments?teamId=1");
    const stale = result.current.hook;
    act(() => {
      stale.setFilter({ ...stale.filter, from: "2026-09-25", to: "2026-10-24" });
      stale.setFilter(withFilterKey(stale.filter, "accountId", "acc-ann"));
    });
    expect(result.current.hook.filter).toEqual({ from: "2026-09-25", to: "2026-10-24", teamId: 1, accountId: "acc-ann" });
    expect(urlOf(result).get("from")).toBe("2026-09-25");
    expect(urlOf(result).get("accountId")).toBe("acc-ann");
  });
});
