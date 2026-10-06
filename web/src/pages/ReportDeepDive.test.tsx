import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Link, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import type { DeepDiveReport } from "../api/reports";
import { MONTH_CROSSING, deepDiveEpic, deepDiveReport, deepDiveTask } from "../test/deepDiveFixtures";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { FILTERS } from "../test/reportFixtures";
import { act, renderWithProviders, screen, waitFor, within } from "../test/render";
import ReportDeepDive from "./ReportDeepDive";

// recharts draws nothing under happy-dom; the burn-up chart is asserted through its props.
vi.mock("@mantine/charts", () => ({
  LineChart: (props: { data: unknown; series: unknown }) => (
    <div data-testid="line-chart" data-rows={JSON.stringify(props.data)} data-series={JSON.stringify(props.series)} />
  ),
}));

type FetchMock = ReturnType<typeof vi.fn>;
const REPORT_PATH = "/api/v1/reports/deep-dive";
const LAYERS_KEY = "flow.viewSettings.reports.deepDive.layers";
const BUDGET_KEY = "flow.viewSettings.reports.deepDive.burnupBudget";

/** One epic with one task: plan, execution, a done day and cost, over the month-crossing range. */
function sampleReport(partial: Partial<DeepDiveReport> = {}): DeepDiveReport {
  return deepDiveReport({
    ...MONTH_CROSSING,
    mode: "SPRINTS",
    sprints: [{ id: 41, name: "Sprint 4", startDay: 0, endDay: 12 }],
    authors: [{ accountId: "a1", displayName: "Ann Lee" }],
    epics: [deepDiveEpic("FLO-1", { summary: "Onboarding" })],
    tasks: [
      deepDiveTask("FLO-11", {
        summary: "Sign-up form",
        epicKey: "FLO-1",
        planBasisMd: 2,
        planSource: "EARLIEST",
        pv: [{ d: 0, md: 2 }],
        exec: [{ d: 4, td: 1 }],
        cost: [{ d: 5, a: 0, md: 1.5 }],
        done: { d: 6, evMd: 2 },
        totals: { pvMd: 2, execTaskDays: 1, evMd: 2, costMd: 1.5 },
      }),
    ],
    ...partial,
  });
}

function serve(mockFetch: FetchMock, report: unknown = sampleReport(), status = 200) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(`${REPORT_PATH}?`)) return Promise.resolve(jsonResponse(status, report));
    if (url.startsWith(`${REPORT_PATH}/epics?`)) {
      const items = [{ id: 1, connectionId: 1, key: "FLO-1", summary: "Onboarding", domain: "FLO" }];
      return Promise.resolve(jsonResponse(200, { items, page: 1, pageSize: 100, total: 1 }));
    }
    if (url.startsWith(`${REPORT_PATH}/`)) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 100, total: 0 }));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function LocationProbe() {
  const navigate = useNavigate();
  return (
    <>
      <output data-testid="search">{useLocation().search}</output>
      <button type="button" onClick={() => void navigate(-1)}>
        go back
      </button>
    </>
  );
}

function renderPage(route = "/reports/deep-dive") {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/reports/deep-dive" element={<ReportDeepDive />} />
      </Routes>
      <Link to={LINK_A}>go to A</Link>
      <Link to={LINK_B}>go to B</Link>
      <LocationProbe />
    </>,
    { route },
  );
}

const search = () => screen.getByTestId("search").textContent ?? "";
const reportCalls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(`${REPORT_PATH}?`));
const SPRINT_LINK = "/reports/deep-dive?sprintId=42&domain=FLO&sprintId=41";
const LINK_A = SPRINT_LINK;
const LINK_B = "/reports/deep-dive?epicId=FLO-1";

describe("ReportDeepDive page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
    serve(mockFetch);
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });

  test("without a selection it explains the three ways in and requests no report", async () => {
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "Deep dive" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { level: 3, name: "Pick something to dive into" })).toBeInTheDocument();
    const explainer = screen.getByRole("heading", { name: "Pick something to dive into" }).closest("section") as HTMLElement;
    expect(within(explainer).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      "Sprints of a domain: the tasks of one domain that were in scope at the close of the sprints you pick (up to 52 sprints).",
      "Epics: every task under the epics you pick (up to 50 epics, 500 tasks in all).",
      "Tasks of an epic: exactly the tasks you pick from one epic (up to 500 tasks).",
    ]);
    // The selection panel is there to make one.
    expect(await screen.findByRole("button", { name: "Show" })).toHaveAttribute("aria-disabled", "true");
    expect(reportCalls(mockFetch)).toEqual([]);
  });

  test("an incomplete selection in the link (sprints without a domain) reads as no selection", async () => {
    renderPage("/reports/deep-dive?sprintId=41");
    expect(await screen.findByRole("heading", { name: "Pick something to dive into" })).toBeInTheDocument();
    expect(reportCalls(mockFetch)).toEqual([]);
  });

  test("a selection in the URL is requested in its canonical form and drawn as a matrix", async () => {
    renderPage(SPRINT_LINK);
    expect(await screen.findByRole("grid", { name: "Plan, execution and cost by epic and time" })).toBeInTheDocument();
    expect(reportCalls(mockFetch)).toEqual([`${REPORT_PATH}?domain=FLO&sprintId=41&sprintId=42`]);
    expect(screen.getByText("Showing 2026-08-27 to 2026-09-08.")).toBeInTheDocument();
    // One outline: the title, then the selection, the matrix (its own summary block one level down) and the limits.
    expect(headingOutline()).toEqual([
      [2, "Deep dive"],
      [3, "Selection"],
      [3, "Plan, execution and cost"],
      [4, "Totals per epic and task"],
      [3, "Data limits"],
    ]);
  });

  test("the panel starts from the link and names its sprints from the answered report", async () => {
    renderPage(SPRINT_LINK);
    await screen.findByRole("grid");
    expect(await screen.findByText("Sprint 4")).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Sprints of a domain" })).toBeChecked();
    expect(screen.getByText("2 of 52 selected.")).toBeInTheDocument();
  });

  test("while the report loads there is a loader, never a stale grid", async () => {
    mockFetch.mockImplementation((url: string) =>
      url === "/api/v1/reports/filters" ? Promise.resolve(jsonResponse(200, FILTERS)) : new Promise(() => {}),
    );
    renderPage(SPRINT_LINK);
    expect(await screen.findByRole("status", { name: "Loading…" })).toBeInTheDocument();
    expect(screen.queryByRole("grid")).not.toBeInTheDocument();
  });

  test("nothing derived yet is a note in the viewer's language and no grid", async () => {
    serve(
      mockFetch,
      deepDiveReport({
        range: { from: "2026-09-01", to: "2026-09-01", asOfDay: null },
        meta: { ...sampleReport().meta, derivedAt: null, configRevision: null },
        note: "Not derived yet: the connection has not completed a DERIVE.",
      }),
    );
    renderPage(SPRINT_LINK);
    expect(await screen.findByText(/Nothing in this selection has been derived yet/)).toBeInTheDocument();
    expect(screen.queryByRole("grid")).not.toBeInTheDocument();
    expect(screen.queryByText(/Not derived yet:/)).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Data limits" })).not.toBeInTheDocument();
  });

  test("a clamped range says so, in words, above the matrix", async () => {
    serve(mockFetch, sampleReport({ note: "RANGE_CLAMPED: the implied range spans 2000 days; the last 1100 are shown." }));
    renderPage(SPRINT_LINK);
    const note = await screen.findByText(/only its last 1100 days are drawn/);
    expect(note.closest('[role="note"]')).toHaveClass("mantine-Alert-root");
    expect(screen.getByRole("grid")).toBeInTheDocument();
    expect(screen.queryByText(/RANGE_CLAMPED/)).not.toBeInTheDocument();
  });

  test("any other server note is shown as written", async () => {
    serve(mockFetch, sampleReport({ note: "Something the server wants to say." }));
    renderPage(SPRINT_LINK);
    const note = await screen.findByText("Something the server wants to say.");
    expect(note.closest('[role="note"]')).not.toBeNull();
    expect(screen.getByRole("grid")).toBeInTheDocument();
  });

  test("the data limits list every counter, the fixed rules and how fresh the data is", async () => {
    serve(
      mockFetch,
      sampleReport({
        quality: { neverInSprint: 3, noEstimate: 2, noWorkingDay: 1, epicsWithoutWindow: 4, laterFallback: 5, epicOwnCostMd: 1.25 },
      }),
    );
    renderPage(SPRINT_LINK);
    const limits = (await screen.findByRole("heading", { name: "Data limits" })).closest("section") as HTMLElement;
    const counters = within(limits).getByRole("list", { name: "What this selection could not draw" });
    expect(within(counters).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      "Tasks never in a sprint: 3 They have no plan (PV); their execution and cost are still drawn.",
      "Tasks with no estimate at commitment: 2 They have no plan (PV) either.",
      "Tasks whose sprint has no working day: 1 Their estimate has no working day to sit on, so there is no plan (PV) to draw.",
      "Epics without a planned window: 4 They get no dashed outline.",
      "Tasks planned from a later sprint: 5 Their plan (PV) uses the estimate from the first later sprint that has one, not the earliest sprint's.",
      "Man-days logged on the epics themselves: 1.25 Shown on each epic's own line, never spread over its tasks.",
    ]);
    expect(within(within(limits).getByRole("list", { name: "Rules that always apply" })).getAllByRole("listitem")).toHaveLength(4);
    expect(within(limits).getByText("Data as of 2026-09-08, the day of the last derive.")).toBeInTheDocument();
  });

  test("the layer switches start on, redraw the matrix and are remembered for the viewer", async () => {
    const user = userEvent.setup();
    const { unmount } = renderPage(SPRINT_LINK);
    const grid = await screen.findByRole("grid");
    const layers = () => screen.getByRole("group", { name: "Layers shown" });
    for (const name of ["Plan (PV)", "Execution", "Cost (AC)"]) expect(within(layers()).getByRole("switch", { name })).toBeChecked();
    expect(grid.querySelector('[data-layer="cost"]')).not.toBeNull();

    await user.click(within(layers()).getByRole("switch", { name: "Cost (AC)" }));
    expect(within(layers()).getByRole("switch", { name: "Cost (AC)" })).not.toBeChecked();
    expect(screen.getByRole("grid").querySelector('[data-layer="cost"]')).toBeNull();
    expect(screen.getByRole("grid").querySelector('[data-layer="pv"]')).not.toBeNull();
    expect(JSON.parse(localStorage.getItem(LAYERS_KEY) ?? "null")).toEqual({ pv: true, exec: true, cost: false });
    unmount();

    renderPage(SPRINT_LINK);
    await screen.findByRole("grid");
    expect(within(layers()).getByRole("switch", { name: "Cost (AC)" })).not.toBeChecked();
    expect(within(layers()).getByRole("switch", { name: "Plan (PV)" })).toBeChecked();
  });

  test("a corrupt remembered layer choice falls back to all three on", async () => {
    localStorage.setItem(LAYERS_KEY, JSON.stringify({ pv: "yes" }));
    renderPage(SPRINT_LINK);
    await screen.findByRole("grid");
    for (const name of ["Plan (PV)", "Execution", "Cost (AC)"]) expect(screen.getByRole("switch", { name })).toBeChecked();
  });

  test("Show writes the canonical selection to the URL and requests it once", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("radio", { name: "Epics" }));
    await user.click(screen.getByRole("combobox", { name: "Epics" }));
    await user.click(await screen.findByRole("option", { name: "FLO-1 Onboarding" }));
    // Picking is not asking: nothing is requested and the address is unchanged until Show.
    expect(reportCalls(mockFetch)).toEqual([]);
    expect(search()).toBe("");

    await user.click(screen.getByRole("button", { name: "Show" }));
    expect(search()).toBe("?epicId=FLO-1");
    expect(await screen.findByRole("grid")).toBeInTheDocument();
    expect(reportCalls(mockFetch)).toEqual([`${REPORT_PATH}?epicId=FLO-1`]);
  });

  test("another selection starts a fresh matrix, even one whose answer is already cached", async () => {
    const user = userEvent.setup();
    renderPage(LINK_A);
    await screen.findByRole("grid");
    await user.click(screen.getByRole("link", { name: "go to B" }));
    await waitFor(() => expect(reportCalls(mockFetch)).toHaveLength(2));
    await screen.findByRole("grid");
    await user.click(screen.getAllByRole("button", { name: /^Expand .* into weeks$/ })[0]);
    expect(screen.getAllByRole("button", { name: /^Collapse /i }).length).toBeGreaterThan(0);

    // A's answer is cached, so the grid swaps with no loading in between — still without B's open month.
    await user.click(screen.getByRole("link", { name: "go to A" }));
    expect(await screen.findByRole("grid")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Collapse (?!all)/i })).not.toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /^Expand .* into weeks$/ }).length).toBeGreaterThan(0);
  });

  test("a pasted TASKS link names its tasks and its epic, and the epic list is asked for once, however slow the report", async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
      if (url.startsWith(`${REPORT_PATH}/epics?`)) {
        const items = [{ id: 1, connectionId: 1, key: "FLO-1", summary: "Onboarding", domain: "FLO" }];
        return Promise.resolve(jsonResponse(200, { items, page: 1, pageSize: 100, total: 1 }));
      }
      if (url.startsWith(`${REPORT_PATH}/`)) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 100, total: 0 }));
      return new Promise(() => {}); // the report never answers
    });
    // Fake clock (afterEach restores it): the debounce timers are virtual, so the wait below costs no real time.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderPage("/reports/deep-dive?epicId=FLO-1&issueId=FLO-11");
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Epic" })).toHaveValue("FLO-1 Onboarding"));
    expect(screen.getByText("1 of 500 selected.")).toBeInTheDocument();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    const epicCalls = mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(`${REPORT_PATH}/epics?`));
    expect(epicCalls).toHaveLength(1);
    expect(screen.getByRole("combobox", { name: "Epic" })).toHaveValue("FLO-1 Onboarding");
  });

  test("a pasted TASKS link names its tasks from the answered report", async () => {
    serve(mockFetch, sampleReport({ mode: "TASKS" }));
    renderPage("/reports/deep-dive?epicId=FLO-1&issueId=FLO-11");
    await screen.findByRole("grid");
    const panel = (await screen.findByRole("heading", { name: "Selection" })).closest("section") as HTMLElement;
    expect(await within(panel).findByText("FLO-11 Sign-up form")).toBeInTheDocument();
  });

  test("Back and a followed link reset the panel to the URL", async () => {
    const user = userEvent.setup();
    renderPage(LINK_B);
    await screen.findByRole("grid");
    expect(screen.getByRole("radio", { name: "Epics" })).toBeChecked();
    expect(screen.getByText("1 of 50 selected.")).toBeInTheDocument();

    await user.click(screen.getByRole("link", { name: "go to A" }));
    await waitFor(() => expect(screen.getByRole("radio", { name: "Sprints of a domain" })).toBeChecked());
    expect(screen.getByText("2 of 52 selected.")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "go back" }));
    await waitFor(() => expect(screen.getByRole("radio", { name: "Epics" })).toBeChecked());
    expect(screen.getByText("1 of 50 selected.")).toBeInTheDocument();
  });

  test("Show does not rebuild the panel: the same controls stay on screen", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("radio", { name: "Epics" }));
    await user.click(screen.getByRole("combobox", { name: "Epics" }));
    await user.click(await screen.findByRole("option", { name: "FLO-1 Onboarding" }));
    const picker = screen.getByRole("combobox", { name: "Epics" });
    const modeControl = screen.getByRole("radio", { name: "Epics" });
    await user.click(screen.getByRole("button", { name: "Show" }));
    expect(search()).toBe("?epicId=FLO-1");
    await screen.findByRole("grid");
    // The very same elements: a rebuilt panel would have replaced them.
    expect(screen.getByRole("combobox", { name: "Epics" })).toBe(picker);
    expect(screen.getByRole("radio", { name: "Epics" })).toBe(modeControl);
  });

  test("Show for the selection already on screen adds no history entry", async () => {
    const user = userEvent.setup();
    renderPage(LINK_A);
    await screen.findByRole("grid");
    await user.click(screen.getByRole("link", { name: "go to B" }));
    await screen.findByText("1 of 50 selected.");
    await user.click(screen.getByRole("button", { name: "Show" }));
    await user.click(screen.getByRole("button", { name: "go back" }));
    await waitFor(() => expect(search()).toContain("domain=FLO"));
  });

  test("a failed report is an inline alert, never a toast", async () => {
    serve(mockFetch, { title: "Boom", status: 500 }, 500);
    renderPage(SPRINT_LINK);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/500/);
    expect(screen.queryByRole("grid")).not.toBeInTheDocument();
    // The panel stays, so the selection can be changed.
    expect(screen.getByRole("button", { name: "Show" })).toBeInTheDocument();
  });

  test("a 400 shows the server's own reason for the selection", async () => {
    serve(mockFetch, { title: "Bad Request", status: 400, detail: "The selection resolves to more than 500 tasks." }, 400);
    renderPage(SPRINT_LINK);
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("The selection resolves to more than 500 tasks."));
    expect(screen.getByRole("alert")).toHaveTextContent("This selection cannot be shown");
  });
});

describe("ReportDeepDive page — the Matrix | Burn-up tabs", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("flow.auth.token", "fake-token");
    serve(mockFetch);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    localStorage.clear();
  });
  const BURNUP_LINK = `${SPRINT_LINK}&view=burnup`;
  const layers = () => screen.queryByRole("group", { name: "Layers shown" });

  test("the matrix is the default tab: nothing in the URL, no burn-up drawn", async () => {
    renderPage(SPRINT_LINK);
    expect(await screen.findByRole("grid")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Matrix" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "Burn-up" })).toHaveAttribute("aria-selected", "false");
    expect(screen.getByRole("tablist", { name: "View" })).toBeInTheDocument();
    expect(screen.queryByTestId("line-chart")).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Burn-up" })).not.toBeInTheDocument();
    expect(search()).toBe("?sprintId=42&domain=FLO&sprintId=41");
  });

  test("choosing Burn-up writes view=burnup next to the selection and draws the chart from the answer already fetched", async () => {
    const user = userEvent.setup();
    renderPage(SPRINT_LINK);
    await screen.findByRole("grid");
    expect(layers()).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Burn-up" }));
    expect(search()).toBe("?sprintId=42&domain=FLO&sprintId=41&view=burnup");
    expect(screen.getByRole("tab", { name: "Burn-up" })).toHaveAttribute("aria-selected", "true");
    const chart = await screen.findByTestId("line-chart");
    expect(JSON.parse(chart.getAttribute("data-series")!).map((s: { name: string }) => s.name)).toEqual(["pv", "ev", "ac"]);
    expect(JSON.parse(chart.getAttribute("data-rows")!).at(-1)).toEqual({ date: "2026-09-08", pv: 2, ev: 2, ac: 1.5 });
    // The layer switches belong to the matrix; the burn-up has its own legend.
    expect(layers()).not.toBeInTheDocument();
    // One answer feeds both views: no second request.
    expect(reportCalls(mockFetch)).toHaveLength(1);

    await user.click(screen.getByRole("tab", { name: "Matrix" }));
    expect(search()).toBe("?sprintId=42&domain=FLO&sprintId=41");
    expect(await screen.findByRole("group", { name: "Layers shown" })).toBeInTheDocument();
    expect(screen.queryByTestId("line-chart")).not.toBeInTheDocument();
    expect(reportCalls(mockFetch)).toHaveLength(1);
  });

  test("a link with view=burnup opens the burn-up, with the one outline", async () => {
    renderPage(BURNUP_LINK);
    expect(await screen.findByTestId("line-chart")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Burn-up" })).toHaveAttribute("aria-selected", "true");
    expect(reportCalls(mockFetch)).toEqual([`${REPORT_PATH}?domain=FLO&sprintId=41&sprintId=42`]);
    expect(headingOutline()).toEqual([
      [2, "Deep dive"],
      [3, "Selection"],
      [3, "Burn-up"],
      [3, "Data limits"],
    ]);
  });

  test("Show for another selection keeps the burn-up open and requests the new answer once", async () => {
    const user = userEvent.setup();
    renderPage("/reports/deep-dive?epicId=FLO-1&view=burnup");
    await screen.findByTestId("line-chart");
    await user.type(await screen.findByRole("textbox", { name: "From (optional)" }), "2026-08-30");
    await user.click(screen.getByRole("button", { name: "Show" }));
    await waitFor(() => expect(reportCalls(mockFetch)).toHaveLength(2));
    const params = new URLSearchParams(search());
    expect(params.get("view")).toBe("burnup");
    expect(params.get("from")).toBe("2026-08-30");
    expect(reportCalls(mockFetch)[1]).toBe(`${REPORT_PATH}?epicId=FLO-1&from=2026-08-30`);
    expect(await screen.findByTestId("line-chart")).toBeInTheDocument();
  });

  test("the budget switch is remembered per viewer and survives a hop to the matrix and back", async () => {
    const user = userEvent.setup();
    const epics = [deepDiveEpic("FLO-1", { summary: "Onboarding", plannedStart: 0, plannedDue: 4, budgetMd: 10 })];
    serve(mockFetch, sampleReport({ epics }));
    const { unmount } = renderPage(BURNUP_LINK);
    const toggle = await screen.findByRole("switch", { name: /Epic budget plan/ });
    expect(toggle).not.toBeChecked();
    await user.click(toggle);
    expect(JSON.parse(localStorage.getItem(BUDGET_KEY) ?? "null")).toBe(true);

    await user.click(screen.getByRole("tab", { name: "Matrix" }));
    await user.click(screen.getByRole("tab", { name: "Burn-up" }));
    expect(await screen.findByRole("switch", { name: /Epic budget plan/ })).toBeChecked();
    const series = JSON.parse((await screen.findByTestId("line-chart")).getAttribute("data-series")!) as Array<{ name: string }>;
    expect(series.map((s) => s.name)).toContain("budget");
    unmount();

    renderPage(BURNUP_LINK);
    expect(await screen.findByRole("switch", { name: /Epic budget plan/ })).toBeChecked();
  });

  test("a corrupt remembered budget choice falls back to off", async () => {
    localStorage.setItem(BUDGET_KEY, JSON.stringify("yes"));
    serve(mockFetch, sampleReport({ epics: [deepDiveEpic("FLO-1", { plannedStart: 0, plannedDue: 4, budgetMd: 10 })] }));
    renderPage(BURNUP_LINK);
    expect(await screen.findByRole("switch", { name: /Epic budget plan/ })).not.toBeChecked();
  });

  test("a followed link without view opens the matrix again", async () => {
    const user = userEvent.setup();
    renderPage("/reports/deep-dive?epicId=FLO-1&view=burnup");
    await screen.findByTestId("line-chart");
    await user.click(screen.getByRole("link", { name: "go to A" }));
    expect(await screen.findByRole("grid")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Matrix" })).toHaveAttribute("aria-selected", "true");
    expect(search()).toBe("?sprintId=42&domain=FLO&sprintId=41");
  });

  test("the tabs are reachable by keyboard: the arrow key moves to the other tab", async () => {
    const user = userEvent.setup();
    renderPage(SPRINT_LINK);
    await screen.findByRole("grid");
    screen.getByRole("tab", { name: "Matrix" }).focus();
    await user.keyboard("{ArrowRight}");
    expect(await screen.findByTestId("line-chart")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Burn-up" })).toHaveFocus();
    expect(search()).toContain("view=burnup");
  });

  test("nothing derived yet shows its one note, with no tabs, even for a burn-up link", async () => {
    serve(
      mockFetch,
      deepDiveReport({
        range: { from: "2026-09-01", to: "2026-09-01", asOfDay: null },
        meta: { ...sampleReport().meta, derivedAt: null, configRevision: null },
        note: "Not derived yet: the connection has not completed a DERIVE.",
      }),
    );
    renderPage(BURNUP_LINK);
    expect(await screen.findByText(/Nothing in this selection has been derived yet/)).toBeInTheDocument();
    expect(screen.queryByRole("tab")).not.toBeInTheDocument();
    expect(screen.queryByTestId("line-chart")).not.toBeInTheDocument();
  });

  test("a selection with no figures says so on the burn-up too", async () => {
    serve(mockFetch, sampleReport({ tasks: [], epics: [] }));
    renderPage(BURNUP_LINK);
    expect(await screen.findByText("There is nothing to plot for this selection.")).toBeInTheDocument();
    expect(screen.queryByTestId("line-chart")).not.toBeInTheDocument();
  });
});
