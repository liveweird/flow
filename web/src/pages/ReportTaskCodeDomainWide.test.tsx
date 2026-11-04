import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportTaskCode from "./ReportTaskCode";
import { jsonResponse } from "../test/http";
import { showItems } from "../test/drill";
import { openReportFilters } from "../test/reportFilters";
import { FILTERS } from "../test/reportFixtures";
import { TASK_CODE, taskCodeItems } from "../test/taskCodeFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout): the histograms are inert probes.
vi.mock("@mantine/charts", () => ({
  BarChart: () => <div data-testid="bar-chart" />,
  ChartTooltip: () => null,
}));

type FetchMock = ReturnType<typeof vi.fn>;
const REPORT_PREFIX = "/api/v1/reports/task-code?";
const ITEMS_PREFIX = "/api/v1/reports/task-code/items?";
const SHARED_TEAM_KEY = "flow.viewSettings.reports.teamId";
const TASKS_CARD = "Done tasks and their merge requests";

function serve(mockFetch: FetchMock) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(ITEMS_PREFIX)) return Promise.resolve(jsonResponse(200, taskCodeItems(9)));
    if (url.startsWith(REPORT_PREFIX)) return Promise.resolve(jsonResponse(200, TASK_CODE));
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

const urls = (mockFetch: FetchMock, prefix: string) => mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith(prefix));
const parse = (url: string) => Object.fromEntries(new URL(url, "http://flow.test").searchParams.entries());
const where = () => screen.getByTestId("where");
const tasksCard = () => screen.findByRole("heading", { level: 3, name: TASKS_CARD });
/** The report requests that name neither a team nor a domain: a read of the whole unit, which must never be sent. */
const wholeUnit = (mockFetch: FetchMock) => urls(mockFetch, REPORT_PREFIX).filter((url) => !/[?&](teamId|domain)=/.test(url));

async function clearTeam(user: ReturnType<typeof userEvent.setup>) {
  await openReportFilters(user);
  await user.click(screen.getByLabelText("Clear Team"));
}

describe("ReportTaskCode page - a domain read across the whole unit from the filter bar", () => {
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

  test("a team is clearable only once a domain is set; clearing it reads the domain alone, keeps the URL, the shared team and the domain", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    localStorage.setItem(SHARED_TEAM_KEY, "2");
    renderPage("/reports/task-code?teamId=1");
    await tasksCard();
    await openReportFilters(user);
    // No domain yet: the required team stays held.
    expect(screen.queryByLabelText("Clear Team")).not.toBeInTheDocument();
    await user.click(screen.getByRole("combobox", { name: "Domain" }));
    await user.click(await screen.findByRole("option", { name: "Flow" }));
    await waitFor(() => expect(where()).toHaveTextContent(/^\?teamId=1&domain=FLO$/));

    await user.click(screen.getByLabelText("Clear Team"));
    await waitFor(() => expect(where()).toHaveTextContent(/^\?domain=FLO$/));
    await waitFor(() => expect(urls(mockFetch, REPORT_PREFIX).at(-1)).toBe(`${REPORT_PREFIX}domain=FLO`));
    await tasksCard();
    // The team control says so, with nothing left to clear; the shared remembered team is untouched (clearing the domain returns to it).
    expect(screen.getByRole("combobox", { name: "Team" })).toHaveAttribute("placeholder", "No team: a domain is picked");
    expect(screen.queryByLabelText("Clear Team")).not.toBeInTheDocument();
    expect(localStorage.getItem(SHARED_TEAM_KEY)).toBe("2");
    // The URL holds: no team is swapped back in on a later render.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(where()).toHaveTextContent(/^\?domain=FLO$/);
    expect(wholeUnit(mockFetch)).toEqual([]);
    // The filter badge counts the domain; the print header names it and prints no team line.
    expect(screen.getByRole("button", { name: /^Filters/ })).toHaveTextContent("1");
    const printed = screen.getByText(/^Filters: /);
    expect(printed).toHaveTextContent("Domain: Flow");
    expect(printed).not.toHaveTextContent("Team:");
  });

  test("the domain-wide read holds across a reload (the link) and a bare return visit (the page's own memory), and never swaps the team back in", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    localStorage.setItem(SHARED_TEAM_KEY, "2");
    const first = renderPage("/reports/task-code?teamId=1&domain=FLO");
    await tasksCard();
    await clearTeam(user);
    await waitFor(() => expect(where()).toHaveTextContent(/^\?domain=FLO$/));
    await tasksCard();
    first.unmount();

    mockFetch.mockClear();
    serve(mockFetch);
    const reloaded = renderPage("/reports/task-code?domain=FLO");
    await tasksCard();
    expect(where()).toHaveTextContent(/^\?domain=FLO$/);
    expect(urls(mockFetch, REPORT_PREFIX)).toEqual([`${REPORT_PREFIX}domain=FLO`]);
    reloaded.unmount();

    mockFetch.mockClear();
    serve(mockFetch);
    renderPage();
    await tasksCard();
    await waitFor(() => expect(where()).toHaveTextContent(/^\?domain=FLO$/));
    expect(urls(mockFetch, REPORT_PREFIX)).toEqual([`${REPORT_PREFIX}domain=FLO`]);
    expect(localStorage.getItem(SHARED_TEAM_KEY)).toBe("2");
  });

  test("clearing the domain of a domain-wide read brings the remembered team back, and no request ever names neither", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    localStorage.setItem(SHARED_TEAM_KEY, "2");
    renderPage("/reports/task-code?domain=FLO");
    await tasksCard();
    await openReportFilters(user);
    await user.click(screen.getByLabelText("Clear Domain"));
    await waitFor(() => expect(where()).toHaveTextContent(/^\?teamId=2$/));
    await waitFor(() => expect(urls(mockFetch, REPORT_PREFIX).at(-1)).toBe(`${REPORT_PREFIX}teamId=2`));
    expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("Beta");
    expect(wholeUnit(mockFetch)).toEqual([]);
    expect(localStorage.getItem(SHARED_TEAM_KEY)).toBe("2");
  });

  test("with no remembered team the default team (the first by name) comes back", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/task-code?lastSprints=3&domain=FLO");
    await tasksCard();
    await openReportFilters(user);
    await user.click(screen.getByLabelText("Clear Domain"));
    await waitFor(() => expect(where()).toHaveTextContent(/^\?lastSprints=3&teamId=1$/));
    expect(wholeUnit(mockFetch)).toEqual([]);
  });

  test("a last-N-sprints period survives the clear (the server reads each team's last sprints); one named sprint belongs to its team and falls back to the default dates", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    const first = renderPage("/reports/task-code?lastSprints=3&teamId=1&domain=FLO");
    await tasksCard();
    await clearTeam(user);
    await waitFor(() => expect(urls(mockFetch, REPORT_PREFIX).at(-1)).toBe(`${REPORT_PREFIX}lastSprints=3&domain=FLO`));
    expect(where()).toHaveTextContent(/^\?lastSprints=3&domain=FLO$/);
    first.unmount();

    mockFetch.mockClear();
    serve(mockFetch);
    renderPage("/reports/task-code?teamId=1&domain=FLO&sprintId=11");
    await tasksCard();
    await clearTeam(user);
    await waitFor(() => expect(where()).toHaveTextContent(/^\?domain=FLO$/));
    expect(urls(mockFetch, REPORT_PREFIX).at(-1)).toBe(`${REPORT_PREFIX}domain=FLO`);
    expect(wholeUnit(mockFetch)).toEqual([]);
  });

  test("the drill sends the domain and no team", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/task-code?domain=FLO");
    await user.click(await screen.findByRole("button", { name: showItems(TASKS_CARD, "Without a merged merge request") }));
    const drawer = await screen.findByRole("dialog", { name: "Tasks without a merged merge request" });
    expect(await within(drawer).findByText("9 items")).toBeInTheDocument();
    expect(urls(mockFetch, ITEMS_PREFIX).map(parse)).toEqual([{ domain: "FLO", view: "TASKS_WITHOUT_MR" }]);
  });

  test("the Code activity tab leaves the domain behind (that page has no domain control)", async () => {
    serve(mockFetch);
    renderPage("/reports/task-code?domain=FLO");
    await tasksCard();
    const tab = screen.getByRole("tab", { name: "Code activity" });
    expect(tab.getAttribute("href")).toBe("/reports/code-activity");
  });
});
