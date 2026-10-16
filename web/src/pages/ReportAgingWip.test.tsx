import type { ReactElement } from "react";
import { afterEach, beforeEach, describe, expect, onTestFinished, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import type { AgingWipReport } from "../api/reports";
import ReportAgingWip from "./ReportAgingWip";
import { jsonResponse } from "../test/http";
import { AGING_BOARD, AGING_EMPTY, AGING_HIDDEN, AGING_TEAM, AGING_TRUNCATED, AGING_UNIT, FILTERS } from "../test/reportFixtures";
import { openReportFilters } from "../test/reportFilters";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

interface Point {
  x: number;
  y: number;
  i: number;
}

// recharts draws nothing under happy-dom: the chart is a probe of its props that also draws each point's own shape.
vi.mock("@mantine/charts", () => ({
  ScatterChart: (props: {
    data: { data: Point[] }[];
    xAxisProps: { ticks: number[]; tickFormatter: (value: number) => string };
    referenceLines: { label: { value: string } }[];
    scatterProps: { shape: (props: { cx: number; cy: number; payload: Point }) => ReactElement };
  }) => (
    <div
      data-testid="scatter"
      data-count={props.data[0].data.length}
      data-lines={props.referenceLines.map((line) => line.label.value).join("|")}
      data-labels={props.xAxisProps.ticks.map((tick) => props.xAxisProps.tickFormatter(tick)).join("|")}
    >
      <svg>
        {props.data[0].data.map((point) => (
          <g key={point.i}>{props.scatterProps.shape({ cx: 1, cy: 1, payload: point })}</g>
        ))}
      </svg>
    </div>
  ),
}));

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/aging-wip";

function serve(mockFetch: FetchMock, response: unknown = AGING_UNIT, status = 200) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) return Promise.resolve(jsonResponse(status, response));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <div data-testid="where">{useLocation().search}</div>;
}

function renderPage(route = "/reports/aging-wip") {
  return renderWithProviders(
    <Routes>
      <Route
        path="/reports/aging-wip"
        element={
          <>
            <ReportAgingWip />
            <Where />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

/** Epics with visible thresholds of their own (3 / 5 / 8 working days) — a different scale from the tasks' 8 / 15 / 25. */
const WITH_EPIC_THRESHOLDS: AgingWipReport = {
  ...AGING_UNIT,
  epicThresholds: { n: 9, hidden: false, percentiles: [{ percentile: 50, workingDays: 3 }, { percentile: 85, workingDays: 5 }, { percentile: 95, workingDays: 8 }] },
  items: AGING_UNIT.items.map((item) => (item.itemKind === "EPIC" ? { ...item, band: "P95" } : item)),
};

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

  test("open work, oldest first as the server sends it, with team, assignee, start, age, blocked and band — tasks by default", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "Aging WIP" })).toBeInTheDocument();
    const table = await screen.findByRole("table", { name: "Open work, oldest first" });
    expect(rowsOf(table)).toEqual([
      ["FLO-1Stuck on review", "Alpha", "Ann Author", "2026-08-10", "30", "Blocked", "—", "Above p95"],
      ["FLO-2Slow but moving", "Beta", "Cy Coder", "2026-09-07", "15.5", "—", "—", "Above p85"],
      ["FLO-3", "Beta", "Cy Coder", "2026-09-15", "9", "—", "Waiting", "Above p50"],
      ["FLO-4Fresh", "Unassigned", "—", "2026-09-22", "6", "—", "—", "Within"],
    ]);
    // No invented Jira link: an issue key is plain text.
    expect(within(table).queryByRole("link", { name: /FLO-1/ })).not.toBeInTheDocument();
  });

  test("the page's Tasks | Epics switch shows one kind in the thresholds, the chart and the table; the choice is in the URL", async () => {
    serve(mockFetch, WITH_EPIC_THRESHOLDS);
    renderPage();
    const table = await screen.findByRole("table", { name: "Open work, oldest first" });
    // Default: tasks. One switch on the page (the chart has none), no `kind` in the URL.
    expect(screen.getAllByRole("radiogroup")).toHaveLength(1);
    expect(screen.getByRole("radio", { name: "Tasks" })).toBeChecked();
    expect(screen.getByTestId("where")).toHaveTextContent("");
    expect(rowsOf(table).map((r) => r[0].slice(0, 5))).toEqual(["FLO-1", "FLO-2", "FLO-3", "FLO-4"]);
    expect(screen.getByRole("group", { name: "Tasks" })).toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Epics" })).not.toBeInTheDocument();
    expect(within(screen.getByRole("group", { name: "Tasks" })).getByText("25")).toBeInTheDocument();
    expect(await screen.findByTestId("scatter")).toHaveAttribute("data-count", "4");
    expect(screen.getByTestId("scatter")).toHaveAttribute("data-lines", expect.stringContaining("p95 · 25 wd"));

    await userEvent.click(screen.getByRole("radio", { name: "Epics" }));
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent("kind=epics"));
    const epicTable = screen.getByRole("table", { name: "Open work, oldest first" });
    // No epic waits, so the epics' table has no Waiting column either.
    expect(rowsOf(epicTable)).toEqual([["FLO-E1Reporting epic", "Alpha", "—", "2026-07-20", "40", "—", "Above p95"]]);
    expect(screen.getByRole("group", { name: "Epics" })).toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Tasks" })).not.toBeInTheDocument();
    expect(within(screen.getByRole("group", { name: "Epics" })).getByText("8")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId("scatter")).toHaveAttribute("data-count", "1"));
    expect(screen.getByTestId("scatter")).toHaveAttribute("data-lines", expect.stringContaining("p95 · 8 wd"));
    // The switch is a view of the one response: no refetch.
    expect(calls(mockFetch)).toHaveLength(1);

    await userEvent.click(screen.getByRole("radio", { name: "Tasks" }));
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent(/^$/));
    expect(rowsOf(screen.getByRole("table", { name: "Open work, oldest first" }))).toHaveLength(4);
  });

  test("a link with kind=epics opens on the epics; an unknown value reads as Tasks; other params survive the switch", async () => {
    serve(mockFetch, WITH_EPIC_THRESHOLDS);
    const { unmount } = renderPage("/reports/aging-wip?teamId=1&kind=epics");
    const table = await screen.findByRole("table", { name: "Open work, oldest first" });
    expect(screen.getByRole("radio", { name: "Epics" })).toBeChecked();
    expect(rowsOf(table).map((r) => r[0].slice(0, 6))).toEqual(["FLO-E1"]);
    // The choice never reaches the request.
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}?teamId=1`]);
    await userEvent.click(screen.getByRole("radio", { name: "Tasks" }));
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent("?teamId=1"));
    expect(screen.getByTestId("where")).not.toHaveTextContent("kind");
    unmount();

    renderPage("/reports/aging-wip?kind=bogus");
    await screen.findByRole("table", { name: "Open work, oldest first" });
    expect(screen.getByRole("radio", { name: "Tasks" })).toBeChecked();
  });

  test("an epic-less list has no switch (tasks only), and kind=epics at user level reads as Tasks", async () => {
    serve(mockFetch, AGING_HIDDEN);
    const { unmount } = renderPage("/reports/aging-wip?kind=epics");
    await screen.findByRole("table", { name: "Open work, oldest first" });
    expect(screen.queryByRole("radiogroup")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Tasks" })).toBeInTheDocument();
    unmount();

    serve(mockFetch, { ...AGING_UNIT, meta: { ...AGING_UNIT.meta, level: "USER" } });
    renderPage("/reports/aging-wip?teamId=1&accountId=acc-ann&kind=epics");
    const table = await screen.findByRole("table", { name: "Open work, oldest first" });
    expect(screen.queryByRole("radiogroup")).not.toBeInTheDocument();
    expect(rowsOf(table).map((r) => r[0].slice(0, 5))).toEqual(["FLO-1", "FLO-2", "FLO-3", "FLO-4"]);
  });

  test("a kind with nothing in progress says so, while the other still has items", async () => {
    const tasksOnly = { ...WITH_EPIC_THRESHOLDS, items: WITH_EPIC_THRESHOLDS.items.filter((item) => item.itemKind === "TASK") };
    serve(mockFetch, tasksOnly);
    renderPage("/reports/aging-wip?kind=epics");
    expect(await screen.findByText("No epic is in progress right now.")).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: "Open work, oldest first" })).not.toBeInTheDocument();
    expect(screen.queryByTestId("scatter")).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Aging chart" })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("radio", { name: "Tasks" }));
    expect(await screen.findByRole("table", { name: "Open work, oldest first" })).toBeInTheDocument();
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

  test("a BLOCKED-stage item wears the Blocked badge and is never read as waiting (A36)", async () => {
    const held = {
      issueKey: "FLO-5", summary: "Held by a customer", itemKind: "TASK" as const, teamId: 2, assigneeAccountId: "acc-cy", assignee: "Cy Coder",
      startedAt: Date.UTC(2026, 8, 10), ageWorkingDays: 12, blocked: true, waiting: false, band: "P50", statusId: "s-prog", statusName: "In progress",
      stage: "BLOCKED" as const, position: "s-prog",
    };
    serve(mockFetch, { ...AGING_UNIT, items: [...AGING_UNIT.items, held] });
    renderPage();
    const table = await screen.findByRole("table", { name: "Open work, oldest first" });
    expect(rowsOf(table).at(-1)).toEqual(["FLO-5Held by a customer", "Beta", "Cy Coder", "2026-09-10", "12", "Blocked", "—", "Above p50"]);
    // Still only FLO-3 waits: the header and its badge.
    expect(within(table).getAllByText("Waiting")).toHaveLength(2);
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

  test("the thresholds as a tile row for the chosen kind, the window they came from, and the unit-level drill hint", async () => {
    serve(mockFetch);
    renderPage();
    const tasks = await screen.findByRole("group", { name: "Tasks" });
    expect(within(tasks).getAllByText(/^p\d+$/).map((el) => el.textContent)).toEqual(["p50", "p85", "p95"]);
    expect(within(tasks).getByText("15")).toBeInTheDocument();
    expect(within(tasks).getByText("25")).toBeInTheDocument();
    expect(screen.getByText("Based on the last 40 finished items.")).toBeInTheDocument();
    expect(screen.queryByText(/Only 2 finished items in the window/)).not.toBeInTheDocument();
    expect(screen.getByText(/At unit level the window spans the whole unit/)).toBeInTheDocument();
    // The epics' window is below the minimum: on the Epics side, a note, and no epic is banded.
    await userEvent.click(screen.getByRole("radio", { name: "Epics" }));
    expect(await screen.findAllByText(/Only 2 finished items in the window — at least 5 are needed to show thresholds/)).not.toHaveLength(0);
    expect(screen.queryByText("Based on the last 40 finished items.")).not.toBeInTheDocument();
  });

  test("hidden thresholds: the note says why, no tiles, no item banded, and no epic row when there are no epics", async () => {
    serve(mockFetch, AGING_HIDDEN);
    renderPage();
    // Once in the thresholds card and once above the chart (the dots still render, unbanded).
    await waitFor(() =>
      expect(screen.getAllByText(/Only 3 finished items in the window — at least 5 are needed to show thresholds, so no item is banded/)).toHaveLength(2),
    );
    expect(await screen.findByTestId("scatter")).toHaveAttribute("data-count", "4");
    expect(screen.queryByRole("group", { name: "Tasks" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Epics" })).not.toBeInTheDocument();
    expect(screen.queryByRole("radiogroup")).not.toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Open work, oldest first" });
    // A table that can outgrow its box scrolls in a focusable, labelled region (axe: scrollable-region-focusable).
    expect(table.closest('[role="region"]')).toHaveAttribute("tabindex", "0");
    expect(rowsOf(table).map((r) => r.at(-1))).toEqual(["—", "—", "—", "—"]);
    expect(within(table).queryByText(/Above|Within/)).not.toBeInTheDocument();
  });

  test("an empty thresholds window says no item is banded, in words that do not read as 'nothing to measure'", async () => {
    serve(mockFetch, { ...AGING_HIDDEN, thresholds: { ...AGING_HIDDEN.thresholds, n: 0 } });
    renderPage();
    expect(await screen.findAllByText("No finished item in the window yet, so there are no thresholds and no item is banded.")).toHaveLength(2);
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
    await openReportFilters();
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
    await openReportFilters();
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
    // The note sits under the table and under the chart; both plot and list the same items.
    expect(await screen.findAllByText("Showing the oldest 4 items; more match. Narrow the team or the domain to see the rest.")).toHaveLength(2);
    expect(await screen.findByTestId("scatter")).toHaveAttribute("data-count", "4");
  });

  test("a truncated mixed list counts the shown kind's items; a kind absent from the cap says so instead of 'nothing in progress'", async () => {
    // 4 tasks + 1 epic listed, more matching: the Epics side shows 1 item, never the list's 5.
    serve(mockFetch, { ...WITH_EPIC_THRESHOLDS, itemsTruncated: true });
    const { unmount } = renderPage("/reports/aging-wip?kind=epics");
    expect(await screen.findAllByText("Showing the oldest 1 items; more match. Narrow the team or the domain to see the rest.")).toHaveLength(2);
    unmount();

    // The cap kept only tasks: Epics has none among them, but the list is cut — so epics may still exist.
    const tasksOnly = { ...WITH_EPIC_THRESHOLDS, itemsTruncated: true, items: WITH_EPIC_THRESHOLDS.items.filter((item) => item.itemKind === "TASK") };
    serve(mockFetch, tasksOnly);
    renderPage("/reports/aging-wip?kind=epics");
    expect(await screen.findByText(/No epic among the oldest 4 items; more items match/)).toBeInTheDocument();
    expect(screen.queryByText("No epic is in progress right now.")).not.toBeInTheDocument();
  });

  test("nothing in progress is said plainly, the thresholds still shown", async () => {
    serve(mockFetch, AGING_EMPTY);
    renderPage();
    expect(await screen.findByText("Nothing is in progress right now.")).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: "Open work, oldest first" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Aging chart" })).not.toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Tasks" })).toBeInTheDocument();
  });

  test("the aging chart sits between the thresholds and the table it is the picture of", async () => {
    serve(mockFetch);
    renderPage();
    const chart = await screen.findByTestId("scatter");
    expect(screen.getAllByRole("heading", { level: 3 }).map((heading) => heading.textContent)).toEqual([
      "Thresholds",
      "Aging chart",
      "Open work, oldest first",
    ]);
    // One dot per task (the epic is on its own plot), over the statuses the tasks hold, in the server's order.
    expect(chart).toHaveAttribute("data-count", "4");
    expect(chart).toHaveAttribute("data-labels", "In progress|In review|On hold");
    expect(screen.getByRole("radio", { name: "Tasks" })).toBeChecked();
  });

  test("one team with a board: the x axis is its columns in board order", async () => {
    serve(mockFetch, AGING_BOARD);
    renderPage("/reports/aging-wip?teamId=1");
    expect(await screen.findByTestId("scatter")).toHaveAttribute("data-labels", "Doing|Review|(no column)");
  });

  test("clicking a dot highlights its table row and scrolls to it; clicking it again lets go", async () => {
    const scrollIntoView = vi.fn();
    const original = Element.prototype.scrollIntoView;
    Element.prototype.scrollIntoView = scrollIntoView;
    onTestFinished(() => {
      Element.prototype.scrollIntoView = original;
    });
    serve(mockFetch);
    const { container } = renderPage();
    const table = await screen.findByRole("table", { name: "Open work, oldest first" });
    await screen.findByTestId("scatter");
    const row = (key: string) => within(table).getByText(key).closest("tr") as HTMLElement;
    const dot = (key: string) => container.querySelector(`[data-item="${key}"]`) as Element;
    expect(table.querySelector("[data-selected]")).toBeNull();
    await userEvent.click(dot("TASK:FLO-2"));
    expect(row("FLO-2")).toHaveAttribute("data-selected", "true");
    expect(table.querySelectorAll("[data-selected]")).toHaveLength(1);
    expect(scrollIntoView).toHaveBeenCalled();
    await userEvent.click(dot("TASK:FLO-4"));
    expect(row("FLO-4")).toHaveAttribute("data-selected", "true");
    expect(row("FLO-2")).not.toHaveAttribute("data-selected");
    await userEvent.click(dot("TASK:FLO-4"));
    expect(table.querySelector("[data-selected]")).toBeNull();
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
