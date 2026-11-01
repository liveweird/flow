import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportTaskCode from "./ReportTaskCode";
import { jsonResponse } from "../test/http";
import { headingOutline } from "../test/headings";
import { openReportFilters } from "../test/reportFilters";
import { FILTERS } from "../test/reportFixtures";
import { TASK_CODE, TASK_CODE_CLEAN, TASK_CODE_NO_MERGES, TASK_CODE_NO_SOURCES, TASK_CODE_QUIET, TASK_CODE_SMALL } from "../test/taskCodeFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout): the histograms are probes carrying the props the page decides.
vi.mock("@mantine/charts", () => ({
  BarChart: (props: { data: Record<string, unknown>[]; series: { name: string }[] }) => (
    <div data-testid="bar-chart" data-values={props.data.map((row) => props.series.map((s) => String(row[s.name] ?? "")).join("/")).join(",")} />
  ),
  ChartTooltip: () => null,
}));

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/task-code?";
// The page is read per team or per domain: a bare link opens on the first team by name (Alpha, id 1), so every request carries it.
const TEAM = `${URL_PREFIX}teamId=1`;

function serve(mockFetch: FetchMock, report: (url: string) => Response = () => jsonResponse(200, TASK_CODE), filters: unknown = FILTERS) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, filters));
    if (url.startsWith(URL_PREFIX)) return Promise.resolve(report(url));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <div data-testid="where">{useLocation().search}</div>;
}

function renderPage(route = "/reports/task-code") {
  return renderWithProviders(
    <Routes>
      <Route
        path="/reports/task-code"
        element={
          <>
            <ReportTaskCode />
            <Where />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

const calls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(URL_PREFIX));
const tile = (name: string) => screen.getByRole("group", { name });

describe("ReportTaskCode page", () => {
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

  test("a heading outline of one page title and three cards, one request for the first team, and the Code group's two tabs", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 3, name: "Code merged after the task was done" })).toBeInTheDocument();
    expect(headingOutline().filter(([level]) => level <= 3)).toEqual([
      [2, "Tasks and code"],
      [3, "Done tasks and their merge requests"],
      [3, "Code lead time"],
      [3, "Code merged after the task was done"],
    ]);
    expect(calls(mockFetch)).toEqual([TEAM]);
    const tabs = within(screen.getByRole("tablist", { name: "Reports in this group" })).getAllByRole("tab");
    expect(tabs.map((tab) => tab.textContent)).toEqual(["Code activity", "Tasks and code"]);
    expect(tabs.map((tab) => tab.getAttribute("aria-selected"))).toEqual(["false", "true"]);
    // The team the bare visit settled on follows the tab link (the URL is rewritten to name it).
    await waitFor(() => expect(tabs[0].getAttribute("href")).toBe("/reports/code-activity?teamId=1"));
  });

  test("the tasks: the done count, those with and without a merged merge request, and the without share as plain context", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("heading", { level: 3, name: "Done tasks and their merge requests" });
    expect(within(tile("Done tasks")).getByText("40")).toBeInTheDocument();
    expect(within(tile("With a merged merge request")).getByText("24")).toBeInTheDocument();
    const without = tile("Without a merged merge request");
    expect(within(without).getByText("16")).toBeInTheDocument();
    expect(without).toHaveTextContent("40% of the done tasks.");
    // Neutral: no direction is claimed for these counts, and nothing in the card is a button (no drill yet) or wears a verdict colour.
    expect(screen.getByText(/No direction is better here: a task may need no code\./)).toBeInTheDocument();
    for (const name of ["Done tasks", "With a merged merge request", "Without a merged merge request"]) {
      expect(within(tile(name)).queryAllByRole("button")).toHaveLength(0);
    }
    expect(screen.queryByRole("button", { name: /show the items/i })).not.toBeInTheDocument();
  });

  test("the code lead time: a distribution in working days with its numbers as a table, lower is better in words", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 5, name: "Task start to last merge, working days" })).toBeInTheDocument();
    expect(screen.getByText(/the same start as the cycle time\. Only started tasks that have a merged merge request are counted\. Lower is better\./)).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByTestId("bar-chart").map((chart) => chart.getAttribute("data-values"))).toEqual(["6,9,5,3,1", "3,2,1"]));
    expect(screen.getByRole("table", { name: "Histogram, as a table — Task start to last merge, working days" })).toBeInTheDocument();
    expect(screen.getAllByRole("group", { name: "Percentiles" })).toHaveLength(2);
  });

  test("merged after done: the count with its share of the tasks that have a merged merge request, the rule in words, and the lag distribution", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("heading", { level: 3, name: "Code merged after the task was done" });
    const merged = tile("Merged after done");
    expect(within(merged).getByText("6")).toBeInTheDocument();
    expect(merged).toHaveTextContent("25% of the tasks with a merged merge request. Lower is better.");
    expect(screen.getByText(/Lower is better: code should be merged before the task is closed\./)).toBeInTheDocument();
    expect(await screen.findByRole("heading", { level: 5, name: "Done to last merge, working days" })).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Histogram, as a table — Done to last merge, working days" })).toBeInTheDocument();
  });

  test("the note says which done tasks the link cannot use, with the non-zero counts only; a clean picture raises none", async () => {
    serve(mockFetch);
    const { unmount } = renderPage();
    const note = (await screen.findByText("What the link between tasks and merge requests leaves out")).closest('[role="note"]') as HTMLElement;
    expect(within(note).getByText("Done tasks that never started, so they have no code lead time: 2.")).toBeInTheDocument();
    expect(within(note).getByText(/all unmerged \(open, closed or locked\); they count as without a merged merge request: 3\./)).toBeInTheDocument();
    expect(within(note).getByText(/names more than one issue \(the key was reused, or two Jira sources hold it\); that key links no merge request: 1\./)).toBeInTheDocument();
    unmount();

    serve(mockFetch, () => jsonResponse(200, { ...TASK_CODE, quality: { neverStarted: 0, onlyUnmergedMrs: 5, tasksWithAmbiguousKey: 0 } }));
    const second = renderPage();
    const only = (await screen.findByText("What the link between tasks and merge requests leaves out")).closest('[role="note"]') as HTMLElement;
    expect(within(only).getAllByRole("listitem")).toHaveLength(1);
    second.unmount();

    serve(mockFetch, () => jsonResponse(200, TASK_CODE_CLEAN));
    renderPage();
    await screen.findByRole("heading", { level: 3, name: "Done tasks and their merge requests" });
    expect(screen.queryByText("What the link between tasks and merge requests leaves out")).not.toBeInTheDocument();
  });

  test("the sources say when they were synced, and that the derived date belongs to the Jira tasks", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("heading", { level: 3, name: "Done tasks and their merge requests" });
    expect(screen.getByText(/Stub GitLab: last synced/)).toBeInTheDocument();
    expect(screen.getByText(/The derived date and configuration revision above belong to the Jira data \(the done tasks\)/)).toBeInTheDocument();
  });

  test("no active GitLab source: ONE empty state that says the code layer is absent, and none of the figures (every task is not a finding)", async () => {
    serve(mockFetch, () => jsonResponse(200, TASK_CODE_NO_SOURCES));
    renderPage();
    expect(await screen.findByText("No GitLab data source is active.")).toBeInTheDocument();
    expect(screen.getByText(/Without the code layer no task can be linked to a merge request, so there is nothing to compare/)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { level: 3 })).not.toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Without a merged merge request" })).not.toBeInTheDocument();
    expect(screen.queryByText(/100%/)).not.toBeInTheDocument();
  });

  test("few tasks with a merged merge request: both distributions say they are withheld, the counts and the share stay", async () => {
    serve(mockFetch, () => jsonResponse(200, TASK_CODE_SMALL));
    renderPage();
    await screen.findByRole("heading", { level: 3, name: "Code lead time" });
    expect(screen.getAllByText(/at least 5 are needed to show percentiles and a histogram/)).toHaveLength(2);
    expect(screen.queryByTestId("bar-chart")).not.toBeInTheDocument();
    expect(within(tile("With a merged merge request")).getByText("3")).toBeInTheDocument();
    expect(tile("Without a merged merge request")).toHaveTextContent("66.7% of the done tasks.");
    expect(tile("Merged after done")).toHaveTextContent("33.3% of the tasks with a merged merge request.");
  });

  test("a source is active but no task was done in the period: every card says there is no data and no share is printed", async () => {
    serve(mockFetch, () => jsonResponse(200, TASK_CODE_QUIET));
    renderPage();
    await screen.findByRole("heading", { level: 3, name: "Code merged after the task was done" });
    expect(screen.getAllByText("No data in this period")).toHaveLength(3);
    expect(screen.queryByTestId("bar-chart")).not.toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Done tasks" })).not.toBeInTheDocument();
    expect(screen.getByText(/Stub GitLab: last synced/)).toBeInTheDocument();
  });

  test("done tasks but none with a merged merge request: the merged-after-done tile has no share to print and says so", async () => {
    serve(mockFetch, () => jsonResponse(200, TASK_CODE_NO_MERGES));
    renderPage();
    await screen.findByRole("heading", { level: 3, name: "Code merged after the task was done" });
    expect(tile("Merged after done")).toHaveTextContent("No done task has a merged merge request. Lower is better.");
    expect(tile("Without a merged merge request")).toHaveTextContent("100% of the done tasks.");
  });

  test("a domain link with no team is read for the domain, and the bar offers the Cycle time slices, the period with sprints and no member", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/task-code?domain=FLO");
    await screen.findByRole("heading", { level: 3, name: "Done tasks and their merge requests" });
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}domain=FLO`]);
    await openReportFilters(user);
    expect(screen.getByRole("combobox", { name: "Domain" })).toHaveValue("Flow");
    for (const name of ["Team", "Domain", "Activity type", "Work category", "Period"]) expect(screen.getByRole("combobox", { name })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Delivered in" })).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Member" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("combobox", { name: "Period" }));
    expect(screen.getByRole("option", { name: "Last 3 sprints" })).toBeInTheDocument();
  });

  test("a pasted link with members or another report's keys is read without them - off the request and off the URL", async () => {
    serve(mockFetch);
    renderPage("/reports/task-code?teamId=1&accountId=acc-ann&accountId=acc-bob&bucket=MONTH&itemKind=TASK&by=STATUS");
    await screen.findByRole("heading", { level: 3, name: "Done tasks and their merge requests" });
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent(/^\?teamId=1$/));
    expect(calls(mockFetch)).toEqual([TEAM]);
  });

  test("the long tail: the default 5 is in neither the URL nor the request; a pick writes both", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    const control = await screen.findByRole("combobox", { name: "Long tail" });
    expect(control).toHaveValue("5%");
    expect(calls(mockFetch)).toEqual([TEAM]);
    await user.click(control);
    await user.click(screen.getByRole("option", { name: "10%" }));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${TEAM}&tailPercent=10`));
    expect(screen.getByTestId("where")).toHaveTextContent("?teamId=1&tailPercent=10");
  });

  test("a failed load is one red alert, not a card of zeros", async () => {
    serve(mockFetch, () => jsonResponse(500, { title: "boom", status: 500 }));
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
    expect(screen.queryByRole("heading", { level: 3 })).not.toBeInTheDocument();
  });

  test("the code-row budget's 400 is told in the page's own words, not the server's English", async () => {
    serve(mockFetch, () => jsonResponse(400, { title: "Bad Request", status: 400, detail: "This selection reads too many merge requests: narrow the period or pick fewer people" }));
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("This selection is too large to read. Narrow the period or pick fewer people.");
    expect(screen.queryByText(/reads too many merge requests/)).not.toBeInTheDocument();
  });

  test("with no team and no domain there is nothing to read, and nothing is requested", async () => {
    serve(mockFetch, undefined, { ...FILTERS, teams: [], domains: [] });
    renderPage();
    expect(await screen.findByText("Tasks and code are read for one team or one domain, and there is neither yet. An administrator adds teams on the Teams page.")).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual([]);
  });

  test("with a domain but no team yet the bar stays up, asks for a pick and requests nothing", async () => {
    serve(mockFetch, undefined, { ...FILTERS, teams: [] });
    renderPage();
    expect(await screen.findByText("Pick a team or a domain to read tasks and code for.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Filters/ })).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual([]);
  });
});
