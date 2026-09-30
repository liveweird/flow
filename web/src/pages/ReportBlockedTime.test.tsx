import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import ReportBlockedTime from "./ReportBlockedTime";
import { jsonResponse } from "../test/http";
import { BLOCKED_EMPTY, BLOCKED_NONE, BLOCKED_TEAM_BOTH, BLOCKED_TIME, FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout): the histograms are probes.
vi.mock("@mantine/charts", () => ({
  BarChart: (props: { data: { count: number }[]; xAxisLabel?: string }) => (
    <div data-testid="bar-chart" data-counts={props.data.map((r) => r.count).join(",")} data-x-label={props.xAxisLabel} />
  ),
  ChartTooltip: () => null,
}));

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/blocked-time?";

function serve(mockFetch: FetchMock, respond?: (url: string) => Response) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) {
      return Promise.resolve(
        respond?.(url) ??
          jsonResponse(200, new URL(url, "http://x").searchParams.get("itemKind") === "BOTH" ? BLOCKED_TEAM_BOTH : BLOCKED_TIME),
      );
    }
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function renderPage(route = "/reports/blocked-time") {
  return renderWithProviders(
    <Routes>
      <Route path="/reports/blocked-time" element={<ReportBlockedTime />} />
    </Routes>,
    { route },
  );
}

const calls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(URL_PREFIX));
const rowsOf = (table: HTMLElement) =>
  within(table).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));

describe("ReportBlockedTime page", () => {
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

  test("sends the item kind explicitly (tasks) and shows both distributions, each with its own accounting", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "Blocked time" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByTestId("bar-chart")).toHaveLength(2));
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}itemKind=TASK`]);
    expect(screen.getAllByTestId("bar-chart").map((c) => [c.getAttribute("data-counts"), c.getAttribute("data-x-label")])).toEqual([
      ["12,4,2,1,1", "blocked working days"],
      ["9,4,2,1,1", "blocked ÷ cycle"],
    ]);
    // Every item is in the first distribution (zeros included); the share leaves out those without a cycle.
    expect(screen.getByText("20 = 20")).toBeInTheDocument();
    expect(screen.getByText("17 + 2 + 1 = 20")).toBeInTheDocument();
    expect(screen.getByText(/Never started, so no cycle/)).toBeInTheDocument();
    expect(screen.getByText(/Cycle of zero working days/)).toBeInTheDocument();
    expect(screen.getByText("9 of 20 finished items were blocked at all; the rest are zeros and stay in the distribution.")).toBeInTheDocument();
    const strips = screen.getAllByRole("group", { name: "Percentiles" });
    expect(within(strips[0]).getByText("0.5")).toBeInTheDocument();
    expect(within(strips[1]).getByText("5%")).toBeInTheDocument();
  });

  test("a distribution below the minimum sample is the counts-only note, not percentiles", async () => {
    serve(mockFetch, () => jsonResponse(200, { ...BLOCKED_TIME, shareOfCycle: { n: 3, hidden: true, histogram: [] } }));
    renderPage();
    expect(await screen.findByText(/Only 3 items in this selection — at least 5 are needed/)).toBeInTheDocument();
    expect(await screen.findAllByTestId("bar-chart")).toHaveLength(1);
  });

  test("the most-blocked items, in the server's order, with a dash where there is no cycle or share", async () => {
    serve(mockFetch);
    renderPage();
    const table = await screen.findByRole("table", { name: "Most blocked items, as a table" });
    expect(rowsOf(table)).toEqual([
      ["FLO-10Waiting on vendor", "Alpha", "2026-09-20", "6.5", "10", "65%"],
      ["FLO-11", "Unassigned", "2026-09-12", "4", "0", "—"],
    ]);
    expect(within(table).queryByRole("columnheader", { name: "Kind" })).not.toBeInTheDocument();
  });

  test("the groups table: hidden shares keep their counts and show a dash, teams link in", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("table", { name: "Most blocked items, as a table" });
    const groups = screen.getByRole("heading", { name: "By team" }).closest("div") as HTMLElement;
    expect(rowsOf(groups)).toEqual([
      ["Alpha", "12", "5", "0.4", "10", "4%"],
      ["Beta", "5", "2", "0.6", "4", "—"],
      ["Unassigned", "3", "2", "—", "3", "—"],
    ]);
    expect(within(groups).getAllByRole("link")).toHaveLength(2);
    expect(screen.getByText("Groups below the minimum sample show counts only.")).toBeInTheDocument();
  });

  test("the item kind switches to epics and to both; at team level both says the groups are tasks only", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("table", { name: "Most blocked items, as a table" });
    expect(screen.getByRole("radio", { name: "Tasks" })).toBeChecked();
    expect(screen.queryByText(/The groups cover tasks only/)).not.toBeInTheDocument();
    await user.click(screen.getByText("Epics"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}itemKind=EPIC`));
    await user.click(screen.getByText("Tasks and epics"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}itemKind=BOTH`));
    // The BOTH fixture is TEAM level: per-assignee groups over the tasks, an epic in the top list.
    expect(await screen.findByText(/The groups cover tasks only/)).toBeInTheDocument();
    const top = screen.getByRole("table", { name: "Most blocked items, as a table" });
    // A table that can outgrow its box scrolls in a focusable, labelled region (axe: scrollable-region-focusable).
    expect(top.closest('[role="region"]')).toHaveAttribute("tabindex", "0");
    expect(within(top).getByRole("columnheader", { name: "Kind" })).toBeInTheDocument();
    expect(within(top).getByText("FLO-E2")).toBeInTheDocument();
    expect(within(top).getByText("Epic")).toBeInTheDocument();
  });

  test("an epic with no owner team reads 'No owner team', a task 'Unassigned'", async () => {
    const epic = { ...BLOCKED_TEAM_BOTH.topItems[2], teamId: null };
    serve(mockFetch, () => jsonResponse(200, { ...BLOCKED_TEAM_BOTH, topItems: [BLOCKED_TIME.topItems[1], epic] }));
    renderPage("/reports/blocked-time?itemKind=BOTH");
    const table = await screen.findByRole("table", { name: "Most blocked items, as a table" });
    expect(rowsOf(table).map((r) => [r[0].slice(0, 6), r[2]])).toEqual([["FLO-11", "Unassigned"], ["FLO-E2", "No owner team"]]);
  });

  test("the blocked count agrees in number: one was, several were", async () => {
    serve(mockFetch, () => jsonResponse(200, { ...BLOCKED_TIME, blockedItems: 1 }));
    renderPage();
    expect(await screen.findByText("1 of 20 finished items was blocked at all; the rest are zeros and stay in the distribution.")).toBeInTheDocument();
  });

  test("nothing blocked: the zeros stay in, and the top list says nobody was blocked", async () => {
    serve(mockFetch, () => jsonResponse(200, BLOCKED_NONE));
    renderPage();
    expect(await screen.findByText("No finished item was blocked in this period.")).toBeInTheDocument();
    expect(screen.getByText("0 of 20 finished items were blocked at all; the rest are zeros and stay in the distribution.")).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: "Most blocked items, as a table" })).not.toBeInTheDocument();
  });

  test("no finished item in the period is the empty state — no top list card", async () => {
    serve(mockFetch, () => jsonResponse(200, BLOCKED_EMPTY));
    renderPage();
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Most blocked items" })).not.toBeInTheDocument();
  });

  test("the filters round-trip into the query string; a bare link with a remembered team is scoped to it", async () => {
    serve(mockFetch);
    const { unmount } = renderPage("/reports/blocked-time?itemKind=EPIC&teamId=1&to=2026-09-29&from=2026-09-01&domain=FLO");
    await screen.findByRole("table", { name: "Most blocked items, as a table" });
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}from=2026-09-01&to=2026-09-29&teamId=1&domain=FLO&itemKind=EPIC`]);
    unmount();
    mockFetch.mockClear();
    localStorage.setItem("flow.viewSettings.reports.teamId", "2");
    serve(mockFetch);
    renderPage();
    await waitFor(() => expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=2&itemKind=TASK`]));
  });

  test("offers domain, activity type, work category and the item kind — no domain view or bucket", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("table", { name: "Most blocked items, as a table" });
    for (const name of ["Domain", "Activity type", "Work category"]) expect(screen.getByRole("combobox", { name })).toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Delivered in" })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Week" })).not.toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Blocked time" })).toHaveAttribute("aria-selected", "true");
  });

  test("a failure is an inline alert", async () => {
    serve(mockFetch, () => jsonResponse(500, { title: "boom", status: 500 }));
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
  });
});
