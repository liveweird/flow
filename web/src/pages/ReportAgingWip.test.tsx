import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { Route, Routes } from "react-router-dom";
import ReportAgingWip from "./ReportAgingWip";
import { jsonResponse } from "../test/http";
import { AGING_EMPTY, AGING_HIDDEN, AGING_TEAM, AGING_TRUNCATED, AGING_UNIT, FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/aging-wip";

function serve(mockFetch: FetchMock, response: unknown = AGING_UNIT, status = 200) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) return Promise.resolve(jsonResponse(status, response));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function renderPage(route = "/reports/aging-wip") {
  return renderWithProviders(
    <Routes>
      <Route path="/reports/aging-wip" element={<ReportAgingWip />} />
    </Routes>,
    { route },
  );
}

const calls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(URL_PREFIX));
const rowsOf = (table: HTMLElement) =>
  within(table).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));

describe("ReportAgingWip page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });

  test("open work, oldest first as the server sends it, with team, assignee, start, age, blocked and band", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "Aging WIP" })).toBeInTheDocument();
    const table = await screen.findByRole("table", { name: "Open work, oldest first" });
    expect(rowsOf(table)).toEqual([
      ["FLO-E1Reporting epic", "Epic", "Alpha", "—", "2026-07-20", "40", "—", "—", "—"],
      ["FLO-1Stuck on review", "Task", "Alpha", "Ann Author", "2026-08-10", "30", "Blocked", "—", "Above p95"],
      ["FLO-2Slow but moving", "Task", "Beta", "Cy Coder", "2026-09-07", "15.5", "—", "—", "Above p85"],
      ["FLO-3", "Task", "Beta", "Cy Coder", "2026-09-15", "9", "—", "Waiting", "Above p50"],
      ["FLO-4Fresh", "Task", "Unassigned", "—", "2026-09-22", "6", "—", "—", "Within"],
    ]);
    // No invented Jira link: an issue key is plain text.
    expect(within(table).queryByRole("link", { name: /FLO-1/ })).not.toBeInTheDocument();
  });

  test("a waiting item wears a Waiting badge with the word; without one there is no Waiting column", async () => {
    serve(mockFetch);
    renderPage();
    const table = await screen.findByRole("table", { name: "Open work, oldest first" });
    expect(within(table).getByRole("columnheader", { name: "Waiting" })).toBeInTheDocument();
    expect(within(table).getAllByText("Waiting")).toHaveLength(2); // the header and FLO-3's badge
    expect(within(within(table).getByText("FLO-3").closest("tr") as HTMLElement).getByText("Waiting")).toBeInTheDocument();
    expect(within(within(table).getByText("FLO-1").closest("tr") as HTMLElement).queryByText("Waiting")).not.toBeInTheDocument();
  });

  test("with nothing waiting the table keeps its familiar columns", async () => {
    serve(mockFetch, { ...AGING_UNIT, items: AGING_UNIT.items.map((item) => ({ ...item, waiting: false })) });
    renderPage();
    const table = await screen.findByRole("table", { name: "Open work, oldest first" });
    expect(within(table).queryByRole("columnheader", { name: "Waiting" })).not.toBeInTheDocument();
  });

  test("the band is the server's — a band that contradicts the age is drawn as sent, never corrected", async () => {
    // Age 40 is above p95 and age 1 is within, yet the server said otherwise: only render-what-was-sent passes.
    const items = [
      { ...AGING_UNIT.items[1], issueKey: "ON-95", ageWorkingDays: 40, band: "WITHIN" },
      { ...AGING_UNIT.items[1], issueKey: "ON-85", ageWorkingDays: 25, band: "P85" },
      { ...AGING_UNIT.items[1], issueKey: "ON-50", ageWorkingDays: 1, band: "P95" },
    ];
    serve(mockFetch, { ...AGING_UNIT, items });
    renderPage();
    const table = await screen.findByRole("table", { name: "Open work, oldest first" });
    expect(rowsOf(table).map((r) => [r[0].slice(0, 5), r.at(-1)])).toEqual([
      ["ON-95", "Within"],
      ["ON-85", "Above p85"],
      ["ON-50", "Above p95"],
    ]);
    expect(within(table).getByText("Above p95").closest("[data-band]")).toHaveAttribute("data-band", "P95");
    expect(within(table).getByText("Within").closest("[data-band]")).toHaveAttribute("data-band", "WITHIN");
  });

  test("the thresholds as a tile row per kind, the window they came from, and the unit-level drill hint", async () => {
    serve(mockFetch);
    renderPage();
    const tasks = await screen.findByRole("group", { name: "Tasks" });
    expect(within(tasks).getAllByText(/^p\d+$/).map((el) => el.textContent)).toEqual(["p50", "p85", "p95"]);
    expect(within(tasks).getByText("15")).toBeInTheDocument();
    expect(within(tasks).getByText("25")).toBeInTheDocument();
    expect(screen.getByText("Based on the last 40 finished items.")).toBeInTheDocument();
    // The epics' window is below the minimum: a note, and no epic is banded.
    expect(screen.getByText(/Only 2 finished items in the window — at least 5 are needed to show thresholds/)).toBeInTheDocument();
    expect(screen.getByText(/At unit level the window spans the whole unit/)).toBeInTheDocument();
  });

  test("hidden thresholds: the note says why, no tiles, no item banded, and no epic row when there are no epics", async () => {
    serve(mockFetch, AGING_HIDDEN);
    renderPage();
    expect(await screen.findByText(/Only 3 finished items in the window — at least 5 are needed to show thresholds, so no item is banded/)).toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Tasks" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Epics" })).not.toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Open work, oldest first" });
    // A table that can outgrow its box scrolls in a focusable, labelled region (axe: scrollable-region-focusable).
    expect(table.closest('[role="region"]')).toHaveAttribute("tabindex", "0");
    expect(rowsOf(table).map((r) => r.at(-1))).toEqual(["—", "—", "—", "—"]);
    expect(within(table).queryByText(/Above|Within/)).not.toBeInTheDocument();
  });

  test("an empty thresholds window says no item is banded, in words that do not read as 'nothing to measure'", async () => {
    serve(mockFetch, { ...AGING_HIDDEN, thresholds: { ...AGING_HIDDEN.thresholds, n: 0 } });
    renderPage();
    expect(await screen.findByText("No finished item in the window yet, so there are no thresholds and no item is banded.")).toBeInTheDocument();
    expect(screen.queryByText("Nothing to measure in this selection.")).not.toBeInTheDocument();
  });

  test("at user level there is no epics row — that assignee's tasks only — even if an epic window exists", async () => {
    serve(mockFetch, { ...AGING_UNIT, meta: { ...AGING_UNIT.meta, level: "USER" } });
    renderPage("/reports/aging-wip?teamId=1&accountId=acc-ann");
    await screen.findByRole("group", { name: "Tasks" });
    expect(screen.queryByRole("heading", { name: "Epics" })).not.toBeInTheDocument();
    expect(screen.getByText(/Thresholds belong to the team/)).toBeInTheDocument();
  });

  test("no period is offered and none is sent, even when the link carries one", async () => {
    serve(mockFetch);
    renderPage("/reports/aging-wip?lastSprints=3&teamId=1&domain=FLO");
    await screen.findByRole("table", { name: "Open work, oldest first" });
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}?teamId=1&domain=FLO`]);
    expect(screen.queryByRole("combobox", { name: "Period" })).not.toBeInTheDocument();
    for (const name of ["Team", "Domain", "Activity type", "Work category"]) {
      expect(screen.getByRole("combobox", { name })).toBeInTheDocument();
    }
  });

  test("a bare link with a remembered team is already scoped to it", async () => {
    localStorage.setItem("flow.viewSettings.reports.teamId", "1");
    serve(mockFetch);
    renderPage();
    await screen.findByRole("table", { name: "Open work, oldest first" });
    await waitFor(() => expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("Alpha"));
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}?teamId=1`]);
  });

  test("at unit level a team name drills to that team; at team level an assignee name drills to that member", async () => {
    serve(mockFetch);
    const { unmount } = renderPage("/reports/aging-wip?domain=FLO");
    const table = await screen.findByRole("table", { name: "Open work, oldest first" });
    const team = within(table).getAllByRole("link", { name: "Show Beta" })[0];
    expect(team.getAttribute("href")).toContain("teamId=2");
    expect(within(table).queryByRole("link", { name: "Show Ann Author" })).not.toBeInTheDocument();
    unmount();

    serve(mockFetch, AGING_TEAM);
    renderPage("/reports/aging-wip?teamId=1");
    const teamTable = await screen.findByRole("table", { name: "Open work, oldest first" });
    expect(within(teamTable).getByRole("link", { name: "Show Ann Author" }).getAttribute("href")).toContain("accountId=acc-ann");
    expect(within(teamTable).queryByRole("link", { name: "Show Alpha" })).not.toBeInTheDocument();
  });

  test("a truncated list says so and how to narrow it", async () => {
    serve(mockFetch, AGING_TRUNCATED);
    renderPage();
    expect(await screen.findByText("Showing the oldest 5 items; more match. Narrow the team or the domain to see the rest.")).toBeInTheDocument();
  });

  test("nothing in progress is said plainly, the thresholds still shown", async () => {
    serve(mockFetch, AGING_EMPTY);
    renderPage();
    expect(await screen.findByText("Nothing is in progress right now.")).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: "Open work, oldest first" })).not.toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Tasks" })).toBeInTheDocument();
  });

  test("Aging WIP is the third tab of the Flow group", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("table", { name: "Open work, oldest first" });
    expect(screen.getByRole("tab", { name: "Aging WIP" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual(["WIP", "Estimated backlog", "Aging WIP", "Blocked time", "Epic progress"]);
  });

  test("a failure is reported once, as an inline alert", async () => {
    serve(mockFetch, { title: "boom", status: 500 }, 500);
    renderPage();
    expect(await screen.findAllByRole("alert")).toHaveLength(1);
    expect(screen.getByRole("alert")).toHaveTextContent("Load failed (500)");
  });
});
