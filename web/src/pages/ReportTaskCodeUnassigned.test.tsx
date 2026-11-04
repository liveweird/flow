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

describe("ReportTaskCode page - the Unassigned choice (teamId=0)", () => {
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

  test("the team control closes its list with Unassigned: picking it reads teamId=0 (not the default team), drops a sprint period, and keeps the shared remembered team", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    localStorage.setItem(SHARED_TEAM_KEY, "2");
    renderPage("/reports/task-code?teamId=1&lastSprints=3&domain=FLO");
    await tasksCard();
    await openReportFilters(user);
    await user.click(screen.getByRole("combobox", { name: "Team" }));
    expect((await screen.findAllByRole("option")).map((option) => option.textContent)).toEqual(["Alpha", "Beta", "Gamma", "Unassigned"]);
    await user.click(screen.getByRole("option", { name: "Unassigned" }));

    // The domain narrows the credit-less tasks too (the server ANDs them); the sprint period cannot be read there and falls back to the default dates.
    await waitFor(() => expect(where()).toHaveTextContent(/^\?teamId=0&domain=FLO$/));
    await waitFor(() => expect(urls(mockFetch, REPORT_PREFIX).at(-1)).toBe(`${REPORT_PREFIX}teamId=0&domain=FLO`));
    expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("Unassigned");
    expect(screen.queryByRole("combobox", { name: "Member" })).not.toBeInTheDocument();
    expect(localStorage.getItem(SHARED_TEAM_KEY)).toBe("2");
    // The Period control offers dates only beside Unassigned.
    await user.click(screen.getByRole("combobox", { name: "Period" }));
    expect(screen.queryByRole("option", { name: "Last 3 sprints" })).not.toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "One sprint" })).not.toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Last 90 days" })).toBeInTheDocument();
  });

  test("Unassigned survives a reload: the address as written, and a bare visit that restores the page's own memory", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    const first = renderPage();
    await tasksCard();
    await openReportFilters(user);
    await user.click(screen.getByRole("combobox", { name: "Team" }));
    await user.click(await screen.findByRole("option", { name: "Unassigned" }));
    await waitFor(() => expect(where()).toHaveTextContent(/^\?teamId=0$/));
    await tasksCard();
    first.unmount();

    mockFetch.mockClear();
    serve(mockFetch);
    const reloaded = renderPage("/reports/task-code?teamId=0");
    await tasksCard();
    expect(where()).toHaveTextContent(/^\?teamId=0$/);
    expect(urls(mockFetch, REPORT_PREFIX)).toEqual([`${REPORT_PREFIX}teamId=0`]);
    reloaded.unmount();

    // A bare visit (a nav click) restores the page's own filter - Unassigned, not the first team.
    mockFetch.mockClear();
    serve(mockFetch);
    renderPage();
    await tasksCard();
    await waitFor(() => expect(where()).toHaveTextContent(/^\?teamId=0$/));
    expect(urls(mockFetch, REPORT_PREFIX)).toEqual([`${REPORT_PREFIX}teamId=0`]);
  });

  test("a link naming members or a sprint-relative period under teamId=0 is read without them - off the request and off the URL", async () => {
    serve(mockFetch);
    const { unmount } = renderPage("/reports/task-code?teamId=0&accountId=acc-ann&lastSprints=3");
    await tasksCard();
    await waitFor(() => expect(where()).toHaveTextContent(/^\?teamId=0$/));
    expect(urls(mockFetch, REPORT_PREFIX)).toEqual([`${REPORT_PREFIX}teamId=0`]);
    unmount();

    mockFetch.mockClear();
    serve(mockFetch);
    renderPage("/reports/task-code?teamId=0&sprintId=11");
    await tasksCard();
    await waitFor(() => expect(where()).toHaveTextContent(/^\?teamId=0$/));
    expect(urls(mockFetch, REPORT_PREFIX)).toEqual([`${REPORT_PREFIX}teamId=0`]);
  });

  test("the drill keeps teamId=0 (and the domain beside it)", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/task-code?teamId=0&domain=FLO");
    await user.click(await screen.findByRole("button", { name: showItems(TASKS_CARD, "Without a merged merge request") }));
    const drawer = await screen.findByRole("dialog", { name: "Tasks without a merged merge request" });
    expect(await within(drawer).findByText("9 items")).toBeInTheDocument();
    expect(urls(mockFetch, ITEMS_PREFIX).map(parse)).toEqual([{ teamId: "0", domain: "FLO", view: "TASKS_WITHOUT_MR" }]);
    expect(where().textContent).toContain("drill=taskcode%3ATASKS_WITHOUT_MR");
  });
});
