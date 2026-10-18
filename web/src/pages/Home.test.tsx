import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { Route, Routes } from "react-router-dom";
import Home from "./Home";
import { jsonResponse } from "../test/http";
import { HOME, HOME_NOT_DERIVED } from "../test/homeFixtures";
import { QueryClient } from "@tanstack/react-query";
import { headingOutline } from "../test/headings";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

const ROLES_KEY = "flow.auth.roles";

vi.mock("@mantine/charts", async () => (await import("../test/homeChartsMock")).homeChartsMock);

type FetchMock = ReturnType<typeof vi.fn>;

function serve(mockFetch: FetchMock, answer: () => Response = () => jsonResponse(200, HOME)) {
  mockFetch.mockImplementation((url: string) =>
    Promise.resolve(url === "/api/v1/reports/home" ? answer() : jsonResponse(404, { title: "Not Found", status: 404 })),
  );
}

const requested = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string);

function renderHome() {
  return renderWithProviders(
    <Routes>
      <Route path="/" element={<Home />} />
    </Routes>,
  );
}

const section = (name: string) => screen.getByRole("region", { name });
/** The page has loaded once its last section is on screen. */
const ready = () => screen.findByRole("region", { name: "What is missing from the data" });
const rowsOf = (table: HTMLElement) =>
  within(table).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
const headersOf = (table: HTMLElement) => within(table).getAllByRole("columnheader").map((h) => h.textContent);
const table = (name: string) => screen.getByRole("table", { name: `${name}, as a table` });
/** A link's destination as a path and its query parameters, so a test does not depend on the parameter order. */
const target = (link: HTMLElement) => {
  const url = new URL(link.getAttribute("href") ?? "", "http://flow.test");
  return { path: url.pathname, query: Object.fromEntries(url.searchParams) };
};
const WINDOW = { from: "2026-07-02", to: "2026-09-29" };

describe("Home page — the overview of every team", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
    localStorage.setItem(ROLES_KEY, "[]");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });

  test("asks for ONE request, the overview, however many charts it draws — and never the remembered team", async () => {
    localStorage.setItem("flow.viewSettings.reports.teamId", "1");
    serve(mockFetch);
    renderHome();
    await ready();
    await waitFor(() => expect(screen.getAllByTestId("bar-chart").length).toBeGreaterThan(10));
    expect(requested(mockFetch)).toEqual(["/api/v1/reports/home"]);
  });

  test("the page outline: seven sections under the title, a chart per heading, the quality groups holding their charts", async () => {
    serve(mockFetch);
    renderHome();
    await ready();
    expect(headingOutline()).toEqual([
      [2, "Flow"],
      [3, "Last sprint"],
      [3, "How big the work is"],
      [4, "Median estimate (MD)"],
      [4, "Median cycle time (working days)"],
      [4, "Median logged cost (MD)"],
      [3, "How well estimates hold"],
      [4, "Alpha"],
      [4, "Beta"],
      [4, "Gamma"],
      [3, "Epics in progress"],
      [4, "Epics in progress"],
      [4, "Epic age, p50 (working days)"],
      [4, "Epic age, p85 (working days)"],
      [4, "Epic age, p95 (working days)"],
      [3, "Tasks in progress"],
      [4, "Tasks in progress"],
      [4, "Task age, p50 (working days)"],
      [4, "Task age, p85 (working days)"],
      [4, "Task age, p95 (working days)"],
      [3, "How much is delivered"],
      [4, "Alpha"],
      [4, "Beta"],
      [4, "Gamma"],
      [4, "Cycle time, p50 (working days)"],
      [4, "Cycle time, p85 (working days)"],
      [3, "What is missing from the data"],
      [4, "Done tasks"],
      [5, "Tasks with no estimate"],
      [5, "Tasks with no assignee"],
      [5, "Tasks estimated late"],
      [4, "Epics"],
      [5, "Epics with no estimate"],
      [5, "Epics with no dates"],
      [5, "Epics whose estimate differs from their tasks by over 25%"],
    ]);
  });

  test("freshness: each connection's last sync and derive in the configured zone, and the two windows", async () => {
    serve(mockFetch);
    renderHome();
    await ready();
    const freshness = screen.getByRole("group", { name: "How fresh the data is" });
    expect(within(freshness).getByText("Stub: synced 2026-09-29 12:15, derived 2026-09-29 12:30")).toBeInTheDocument();
    expect(
      within(freshness).getByText("Figures cover 2026-07-02 – 2026-09-29; arrows compare with 2026-04-03 – 2026-07-01 (days in Europe/Warsaw)."),
    ).toBeInTheDocument();
  });

  test("freshness says so when a connection never synced or derived, and when there is no connection at all", async () => {
    serve(mockFetch, () => jsonResponse(200, { ...HOME, freshness: [{ connectionId: 1, name: "Stub", lastSyncAt: null, lastDeriveAt: null }] }));
    const first = renderHome();
    await ready();
    expect(screen.getByText("Stub: synced never, derived never")).toBeInTheDocument();
    first.unmount();
    serve(mockFetch, () => jsonResponse(200, { ...HOME, freshness: [] }));
    renderHome();
    expect(await screen.findByText("No data source is connected.")).toBeInTheDocument();
  });

  test("the PRED tooltip names how many items are within each share, and leaves a hidden line out", async () => {
    serve(mockFetch);
    renderHome();
    await ready();
    const alpha = await within(await within(section("How well estimates hold")).findByRole("group", { name: /^Chart: Alpha, share/ })).findByTestId("line-chart");
    const tooltip = within(within(alpha).getByTestId("tooltip-1")).getByTestId("tooltip-content");
    expect(tooltip).toHaveAttribute("data-label", "±25%");
    expect(tooltip).toHaveAttribute("data-items", "Tasks (6 / 10)=60%");
  });

  test("every section title is the way into its report, and every link carries the window (or the last sprint) — never bare", async () => {
    serve(mockFetch);
    renderHome();
    await ready();
    const link = (region: string, name: string) => within(section(region)).getByRole("link", { name });
    expect(target(link("Last sprint", "Last sprint"))).toEqual({ path: "/reports/commitments", query: { lastSprints: "1" } });
    expect(target(link("Last sprint", "Throughput report"))).toEqual({ path: "/reports/throughput", query: { lastSprints: "1" } });
    expect(target(link("Last sprint", "Sprint consistency report"))).toEqual({ path: "/reports/sprint-consistency", query: { lastSprints: "1" } });
    expect(target(link("How big the work is", "How big the work is"))).toEqual({ path: "/reports/cycle-time", query: WINDOW });
    expect(target(link("How well estimates hold", "How well estimates hold"))).toEqual({ path: "/reports/task-estimation-accuracy", query: WINDOW });
    expect(target(link("How well estimates hold", "Epic accuracy report"))).toEqual({ path: "/reports/epic-estimation-accuracy", query: WINDOW });
    expect(target(link("How well estimates hold", "Beta"))).toEqual({
      path: "/reports/task-estimation-accuracy",
      query: { ...WINDOW, teamId: "2" },
    });
    expect(target(link("Epics in progress", "Epics in progress"))).toEqual({ path: "/reports/aging-wip", query: { ...WINDOW, kind: "epics" } });
    expect(target(link("Tasks in progress", "Tasks in progress"))).toEqual({ path: "/reports/aging-wip", query: WINDOW });
    expect(target(link("How much is delivered", "How much is delivered"))).toEqual({ path: "/reports/throughput", query: WINDOW });
    expect(target(link("How much is delivered", "Alpha"))).toEqual({ path: "/reports/throughput", query: { ...WINDOW, teamId: "1" } });
    expect(target(link("How much is delivered", "Cycle time report"))).toEqual({ path: "/reports/cycle-time", query: WINDOW });
    expect(target(link("What is missing from the data", "What is missing from the data"))).toEqual({ path: "/reports/data-quality", query: WINDOW });
  });

  test("the last sprint: a chart of capacity, initial, final and delivered per team that has one, and a table with the drift", async () => {
    serve(mockFetch);
    renderHome();
    await ready();
    const sprints = section("Last sprint");
    const chart = await within(sprints).findByTestId("bar-chart");
    // Gamma has no closed sprint: it is named in a note, not drawn.
    expect(chart.getAttribute("data-labels")).toBe("Alpha|Beta");
    expect(chart.getAttribute("data-series")).toBe("capacityMd,initialMd,finalMd,deliveredMd");
    expect(within(sprints).getByText("No closed sprint yet: Gamma.")).toBeInTheDocument();
    expect(headersOf(table("Each team's last closed sprint"))).toEqual([
      "Team",
      "Sprint",
      "Closed",
      "Capacity (MD)",
      "Initial (MD)",
      "Final (MD)",
      "Delivered (velocity, MD)",
      "Since completion",
    ]);
    const rows = rowsOf(table("Each team's last closed sprint"));
    expect(rows[0].slice(0, 7)).toEqual(["Alpha", "Alpha 2", "2026-09-10", "22", "20", "24.5", "18"]);
    expect(rows[0][7]).toContain("Drift");
    expect(rows[1]).toEqual(["Beta", "Beta 1", "2026-09-01", "—", "12", "12", "10", ""]);
    // The tooltip names the sprint and its closing day.
    expect(within(chart).getByTestId("tooltip-0").querySelector("[data-testid=tooltip-content]")).toHaveAttribute(
      "data-label",
      "Alpha · Alpha 2 (closed 2026-09-10)",
    );
  });

  test("granularity: one horizontal chart per measure in the team order, and a hidden table with the previous value and the verdict as text", async () => {
    serve(mockFetch);
    renderHome();
    await ready();
    const estimate = await within(section("How big the work is")).findByRole("group", { name: "Chart: Median estimate (MD)" });
    const chart = await within(estimate).findByTestId("bar-chart");
    expect(chart.getAttribute("data-orientation")).toBe("vertical");
    expect(chart.getAttribute("data-labels")).toBe("Alpha|Beta|Gamma");
    // A hidden figure (Gamma, 2 of the 5 needed) draws no bar.
    expect(chart.getAttribute("data-values")).toBe("3.5,2,null");
    expect(headersOf(table("Median estimate (MD)"))).toEqual(["Team", "Median estimate (MD)", "Items", "Previous 90 days", "Against the previous 90 days"]);
    expect(rowsOf(table("Median estimate (MD)"))).toEqual([
      ["Alpha", "3.5", "12", "4", "Better"],
      ["Beta", "2", "8", "2", "No change"],
      ["Gamma", "Below the minimum sample", "2", "—", "No comparison"],
    ]);
    // Cycle time is lower-is-better: Alpha's rose from 4 to 5.5, Worse; Beta's previous window was below the minimum.
    expect(rowsOf(table("Median cycle time (working days)"))).toEqual([
      ["Alpha", "5.5", "11", "4", "Worse"],
      ["Beta", "3", "8", "Below the minimum sample", "No comparison"],
      ["Gamma", "Below the minimum sample", "2", "—", "No comparison"],
    ]);
  });

  test("the bar tooltip carries the value, the sample, the previous window and the verdict", async () => {
    serve(mockFetch);
    renderHome();
    await ready();
    const estimate = await within(section("How big the work is")).findByRole("group", { name: "Chart: Median estimate (MD)" });
    const chart = await within(estimate).findByTestId("bar-chart");
    const alpha = within(within(chart).getByTestId("tooltip-0")).getByTestId("tooltip-content");
    expect(alpha).toHaveAttribute("data-label", "Alpha · 12 tasks · Better");
    expect(alpha).toHaveAttribute("data-items", "Median estimate (MD)=3.5 | Previous 90 days=4");
    const gamma = within(within(chart).getByTestId("tooltip-2")).getByTestId("tooltip-content");
    expect(gamma).toHaveAttribute("data-label", "Gamma · 2 tasks, Below the minimum sample");
    expect(gamma).toHaveAttribute("data-items", "");
  });

  test("accuracy: a PRED curve per team — tasks solid, epics dashed, a hidden point a gap — and a table of tasks then epics lines", async () => {
    serve(mockFetch);
    renderHome();
    await ready();
    const accuracy = section("How well estimates hold");
    const chart = await within(await within(accuracy).findByRole("group", { name: /^Chart: Alpha, share of tasks/ })).findByTestId("line-chart");
    expect(chart.getAttribute("data-labels")).toBe("±10%|±25%|±50%");
    expect(chart.getAttribute("data-series")).toBe("tasks,epics:dashed");
    expect(chart.getAttribute("data-connect-nulls")).toBe("false");
    expect(chart.getAttribute("data-tasks")).toBe("0.3,0.6,0.8");
    // Alpha's epics are under the minimum sample: no point, a gap.
    expect(chart.getAttribute("data-epics")).toBe("null,null,null");
    expect(within(accuracy).getAllByText(/Within ±25%/, { selector: "p" })[0]).toHaveTextContent("Within ±25%: tasks 60% ↓ Worse, epics —");
    expect(headersOf(table("Alpha, accuracy of estimates"))).toEqual([
      "Measure",
      "Share within",
      "Items within",
      "Sample",
      "Previous 90 days",
      "Against the previous 90 days",
    ]);
    expect(rowsOf(table("Alpha, accuracy of estimates"))).toEqual([
      ["Tasks ±10%", "30%", "3", "10", "20%", "Better"],
      ["Tasks ±25%", "60%", "6", "10", "70%", "Worse"],
      ["Tasks ±50%", "80%", "8", "10", "80%", "No change"],
      ["Epics ±10%", "Below the minimum sample", "1", "3", "—", "No comparison"],
      ["Epics ±25%", "Below the minimum sample", "2", "3", "—", "No comparison"],
      ["Epics ±50%", "Below the minimum sample", "3", "3", "—", "No comparison"],
    ]);
  });

  test("work in progress: the count, then the p50, p85 and p95 age as four charts — hidden under the minimum sample, with no trend", async () => {
    serve(mockFetch);
    renderHome();
    await ready();
    const tasks = section("Tasks in progress");
    expect(headersOf(within(tasks).getByRole("table", { name: "Tasks in progress, as a table" }))).toEqual(["Team", "Tasks in progress"]);
    expect(rowsOf(within(tasks).getByRole("table", { name: "Tasks in progress, as a table" }))).toEqual([["Alpha", "6"], ["Beta", "3"], ["Gamma", "1"]]);
    const p85 = within(tasks).getByRole("table", { name: "Task age, p85 (working days), as a table" });
    expect(headersOf(p85)).toEqual(["Team", "Task age, p85 (working days)", "Tasks"]);
    expect(rowsOf(p85)).toEqual([["Alpha", "9", "6"], ["Beta", "5", "3"], ["Gamma", "Below the minimum sample", "1"]]);
    const chart = await within(await within(tasks).findByRole("group", { name: "Chart: Task age, p85 (working days)" })).findByTestId("bar-chart");
    expect(chart.getAttribute("data-values")).toBe("9,5,null");
    // Epics: Alpha's two are under the minimum sample, Beta has none (a count of zero is a fact, an age is "—").
    const epics = section("Epics in progress");
    expect(rowsOf(within(epics).getByRole("table", { name: "Epic age, p50 (working days), as a table" }))).toEqual([
      ["Alpha", "Below the minimum sample", "2"],
      ["Beta", "—", "0"],
      ["Gamma", "—", "0"],
    ]);
  });

  test("throughput: a weekly bar chart per team with the totals beside the previous window, and the cycle p50 and p85 of every team", async () => {
    serve(mockFetch);
    renderHome();
    await ready();
    const delivery = section("How much is delivered");
    const alpha = await within(await within(delivery).findByRole("group", { name: "Chart: Alpha, man-days delivered per week" })).findByTestId("bar-chart");
    expect(alpha.getAttribute("data-labels")).toBe("06-29|07-06|07-13|07-20|07-27|08-03|08-10|08-17|08-24|08-31|09-07|09-14|09-21|09-28");
    expect(alpha.getAttribute("data-values")).toBe("0,2.5,5,7.5,0,12.5,15,17.5,0,22.5,25,27.5,0,32.5");
    // The week's tooltip names it and counts its items.
    expect(within(within(alpha).getByTestId("tooltip-1")).getByTestId("tooltip-content")).toHaveAttribute("data-label", "Week of 2026-07-06 · 1 task");
    expect(within(delivery).getAllByText(/In the window:/)[0]).toHaveTextContent("In the window: 40.5 MD ↑ Better (was 30) · 14 tasks ↑ Better (was 12)");
    const weeks = table("Alpha, delivery per week");
    expect(headersOf(weeks)).toEqual(["Week", "Delivered (MD)", "Items"]);
    expect(rowsOf(weeks).slice(0, 3)).toEqual([["2026-06-29", "0", "0"], ["2026-07-06", "2.5", "1"], ["2026-07-13", "5", "2"]]);
    const p85 = within(delivery).getByRole("table", { name: "Cycle time, p85 (working days), as a table" });
    expect(rowsOf(p85)).toEqual([
      ["Alpha", "9", "11", "9", "No change"],
      ["Beta", "6", "8", "Below the minimum sample", "No comparison"],
      ["Gamma", "Below the minimum sample", "2", "—", "No comparison"],
    ]);
  });

  test("data quality: task findings carry a trend, epic findings do not; the unassigned work is a note linking to the report", async () => {
    serve(mockFetch);
    renderHome();
    await ready();
    const quality = section("What is missing from the data");
    expect(headersOf(within(quality).getByRole("table", { name: "Tasks with no estimate, as a table" }))).toEqual([
      "Team",
      "Tasks with no estimate",
      "Previous 90 days",
      "Against the previous 90 days",
    ]);
    expect(rowsOf(within(quality).getByRole("table", { name: "Tasks with no estimate, as a table" }))).toEqual([
      ["Alpha", "3", "5", "Better"],
      ["Beta", "0", "0", "No change"],
      ["Gamma", "0", "0", "No change"],
    ]);
    expect(headersOf(within(quality).getByRole("table", { name: "Epics with no dates, as a table" }))).toEqual(["Team", "Epics with no dates"]);
    expect(
      within(quality).getByText("Not in any team above: 2 done tasks, 1 open task and 0 epics.", { exact: false }),
    ).toBeInTheDocument();
    expect(target(within(quality).getByRole("link", { name: "See Data quality" }))).toEqual({ path: "/reports/data-quality", query: WINDOW });
    // The gap finding names the configured tolerance.
    expect(within(quality).getByText("Epics whose estimate differs from their tasks by over 25%", { selector: "h5" })).toBeInTheDocument();
  });

  test("a different gap tolerance reaches the chart title and the caption", async () => {
    serve(mockFetch, () => jsonResponse(200, { ...HOME, epicEstimateGapTolerancePercent: 50 }));
    renderHome();
    const quality = await screen.findByRole("region", { name: "What is missing from the data" });
    expect(within(quality).getByText("Epics whose estimate differs from their tasks by over 50%", { selector: "h5" })).toBeInTheDocument();
    expect(within(quality).getByText(/the estimate gap tolerance is 50%/)).toBeInTheDocument();
  });

  test("no unassigned work: no note", async () => {
    serve(mockFetch, () => jsonResponse(200, { ...HOME, unassigned: { doneTasks: 0, openTasks: 0, epics: 0 } }));
    renderHome();
    await ready();
    expect(screen.queryByText(/Not in any team above/)).not.toBeInTheDocument();
  });

  test("nothing derived yet: the empty state (with the admin's way forward), not seven empty sections", async () => {
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
    serve(mockFetch, () => jsonResponse(200, HOME_NOT_DERIVED));
    renderHome();
    expect(await screen.findByText(/No data has been derived yet\. Connect a data source/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Add a data source" })).toHaveAttribute("href", "/data-sources");
    expect(screen.getByRole("link", { name: "Metrics settings" })).toBeInTheDocument();
    expect(screen.queryByRole("region")).not.toBeInTheDocument();
  });

  test("nothing derived yet, for a plain user: the plain message and no admin links", async () => {
    serve(mockFetch, () => jsonResponse(200, HOME_NOT_DERIVED));
    renderHome();
    expect(await screen.findByText(/The overview appears once a data source is connected and synced/)).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Add a data source" })).not.toBeInTheDocument();
  });

  test("derived but without a team: a note instead of empty sections", async () => {
    serve(mockFetch, () => jsonResponse(200, { ...HOME, teams: [] }));
    renderHome();
    expect(await screen.findByText(/No team exists yet/)).toBeInTheDocument();
    expect(screen.queryByRole("region")).not.toBeInTheDocument();
  });

  test("a failed request is one inline alert and no sections", async () => {
    serve(mockFetch, () => jsonResponse(500, { title: "Boom", status: 500 }));
    renderHome();
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.queryByRole("region")).not.toBeInTheDocument();
  });

  test("a failed background refetch keeps the overview on screen and shows the alert beside it", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    serve(mockFetch);
    renderWithProviders(
      <Routes>
        <Route path="/" element={<Home />} />
      </Routes>,
      { queryClient },
    );
    await ready();
    serve(mockFetch, () => jsonResponse(500, { title: "Boom", status: 500 }));
    await queryClient.refetchQueries({ queryKey: ["home"] });
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    // Nothing the reader was looking at went away.
    expect(screen.getAllByRole("region")).toHaveLength(7);
    expect(screen.getByRole("group", { name: "How fresh the data is" })).toBeInTheDocument();
  });

  test("while loading: one polite live region, a busy placeholder and no section yet", async () => {
    mockFetch.mockImplementation(() => new Promise<Response>(() => undefined));
    renderHome();
    expect(screen.getByRole("status")).toHaveTextContent("Loading…");
    expect(screen.getByRole("group", { name: "Loading the overview" })).toHaveAttribute("aria-busy", "true");
    expect(screen.queryByRole("region")).not.toBeInTheDocument();
  });

  test("once loaded the live region is empty and there are seven sections", async () => {
    serve(mockFetch);
    renderHome();
    await ready();
    expect(screen.getByRole("status")).toBeEmptyDOMElement();
    expect(screen.getAllByRole("region")).toHaveLength(7);
  });
});
