import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportCosts from "./ReportCosts";
import { jsonResponse } from "../test/http";
import { COSTS_EMPTY, COSTS_NO_SHARE, COSTS_SPRINTS, COSTS_TASK_VIEW, COSTS_TEAM, COSTS_THIRDS, COSTS_USER, FILTERS } from "../test/reportFixtures";
import { openReportFilters } from "../test/reportFilters";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts draws nothing under happy-dom and these tests are about the page's totals: the breakdown donuts (`ReportCostsBreakdown.test.tsx`) are a stub.
vi.mock("@mantine/charts", () => ({ DonutChart: () => null }));

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/costs?";

function serve(mockFetch: FetchMock, response: unknown = COSTS_TEAM, status = 200) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) return Promise.resolve(jsonResponse(status, response));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

/** Serves the level and the domain view the URL asks for, so a click gets its own answer without a race. */
function serveByScope(mockFetch: FetchMock) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) {
      const params = new URLSearchParams(url.slice(URL_PREFIX.length));
      let response = params.get("domainView") === "TASK" ? COSTS_TASK_VIEW : COSTS_TEAM;
      if (params.has("accountId")) response = COSTS_USER;
      return Promise.resolve(jsonResponse(200, response));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function LocationProbe() {
  return <output data-testid="search">{useLocation().search}</output>;
}

function renderPage(route = "/reports/costs") {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/reports/costs" element={<ReportCosts />} />
      </Routes>
      <LocationProbe />
    </>,
    { route },
  );
}

const search = () => new URLSearchParams(screen.getByTestId("search").textContent ?? "");
const calls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(URL_PREFIX));
const tile = (name: string) => screen.getByRole("group", { name });
const TOTAL_TILE = "Man-days logged";

describe("ReportCosts page", () => {
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

  test("the team's totals and both breakdowns; a bare link is read for the default team (a team is required), everything else the server's own defaults", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "Costs" })).toBeInTheDocument();
    expect(await screen.findByRole("heading", { level: 3, name: "Man-days logged by the team" })).toBeInTheDocument();
    await screen.findByRole("group", { name: TOTAL_TILE });
    expect(await screen.findByRole("table", { name: "Man-days by activity type" })).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Man-days by work category" })).toBeInTheDocument();
    // The matrix and the epic table are gone.
    expect(screen.queryByRole("table", { name: /author/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/by epic/i)).not.toBeInTheDocument();
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1`]);
  });

  test("tiles: the man-days, the foreign man-days and the overall share, with what foreign work means", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("group", { name: TOTAL_TILE });
    expect(within(tile(TOTAL_TILE)).getByText("14.5")).toBeInTheDocument();
    expect(within(tile("Foreign work (MD)")).getByText("3.75")).toBeInTheDocument();
    const share = tile("Foreign work share");
    expect(within(share).getByText("25.9%")).toBeInTheDocument();
    expect(within(share).getByText("Foreign man-days as a share of everything logged in the period.")).toBeInTheDocument();
    // What counts as foreign work is one plain line under the tiles.
    expect(screen.getByText(/tasks of another team's sprint, or, when a task has no sprint team, tasks assigned to someone in another team/)).toBeInTheDocument();
  });

  test("the domain view: EPIC by default (says whose domain a domain filter follows), a toggle refetches in the TASK view and back", async () => {
    serveByScope(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("group", { name: TOTAL_TILE });
    await openReportFilters();
    expect(screen.getByRole("radio", { name: "Earned in" })).toBeChecked();
    expect(screen.getByText("A domain filter follows the epic's domain — a task with no epic counts in its own domain.")).toBeInTheDocument();

    await user.click(screen.getByText("Delivered in"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}teamId=1&domainView=TASK`));
    expect(await screen.findByText("A domain filter follows the task's own domain, even when its epic belongs to another.")).toBeInTheDocument();
    expect(search().get("domainView")).toBe("TASK");

    await user.click(screen.getByText("Earned in"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}teamId=1&domainView=EPIC`));
    expect(await screen.findByText("A domain filter follows the epic's domain — a task with no epic counts in its own domain.")).toBeInTheDocument();
  });

  test("the bar offers period, team, domain, activity type and work category (never a connection or a split); slices are request params", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("group", { name: TOTAL_TILE });
    await openReportFilters();
    for (const name of ["Period", "Team", "Domain", "Activity type", "Work category"]) {
      expect(screen.getByRole("combobox", { name })).toBeInTheDocument();
    }
    expect(screen.queryByRole("combobox", { name: "Connection" })).not.toBeInTheDocument();
    expect(screen.queryByText("Split")).not.toBeInTheDocument();
    await user.click(screen.getByRole("combobox", { name: "Domain" }));
    await user.click(await screen.findByRole("option", { name: "Flow" }));
    await user.click(screen.getByRole("combobox", { name: "Activity type" }));
    await user.click(await screen.findByRole("option", { name: "Bug" }));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}teamId=1&domain=FLO&activityType=Bug`));
  });

  test("a pasted link keeps what the report reads and loses what only other reports own (a stale connection, the retired split) — off the request and off the URL", async () => {
    serve(mockFetch);
    renderPage(
      "/reports/costs?teamId=1&domainView=TASK&domain=FLO&activityType=Bug&workCategory=UNCATEGORIZED&connectionId=1&epicId=FLO-33&bucket=WEEK&split=WEEK&by=STATUS&itemKind=EPIC",
    );
    await screen.findByRole("group", { name: TOTAL_TILE });
    await waitFor(() => expect(calls(mockFetch).at(-1)).toBe(`${URL_PREFIX}teamId=1&domainView=TASK&domain=FLO&activityType=Bug&workCategory=UNCATEGORIZED`));
    expect(calls(mockFetch).some((url) => /epicId|bucket|by=|itemKind|split|connectionId/.test(url))).toBe(false);
    await waitFor(() => expect(search().toString()).not.toMatch(/epicId|bucket|by=|itemKind|connectionId/));
  });

  test("a sprint-relative period says the sprints are the team's own; a date period says nothing about sprints", async () => {
    serve(mockFetch, COSTS_SPRINTS);
    const { unmount } = renderPage("/reports/costs?lastSprints=3&teamId=1");
    await screen.findByRole("group", { name: TOTAL_TILE });
    expect(screen.getByText("The period follows sprints: this team's own sprints.")).toBeInTheDocument();
    unmount();

    serve(mockFetch);
    renderPage("/reports/costs?from=2026-09-01&to=2026-09-29");
    await screen.findByRole("group", { name: TOTAL_TILE });
    expect(screen.queryByText(/The period follows sprints/)).not.toBeInTheDocument();
  });

  test("one author picked: the member filter narrows the request and the title says so", async () => {
    serveByScope(mockFetch);
    renderPage("/reports/costs?teamId=1&accountId=acc-ann");
    expect(await screen.findByRole("heading", { level: 3, name: "Man-days logged by this author" })).toBeInTheDocument();
    expect(within(tile(TOTAL_TILE)).getByText("4")).toBeInTheDocument();
    expect(calls(mockFetch).at(-1)).toBe(`${URL_PREFIX}teamId=1&accountId=acc-ann`);
  });

  test("totals are the server's own rounded sums, never re-added from the slices shown, and the footnote says why", async () => {
    serve(mockFetch, COSTS_THIRDS);
    renderPage();
    await screen.findByRole("group", { name: TOTAL_TILE });
    // Three slices of 0.33 and a total of 1 (not 0.99).
    expect(within(tile(TOTAL_TILE)).getByText("1")).toBeInTheDocument();
    expect(screen.getByText(/Every figure is its own exact sum, rounded once to two decimals/)).toHaveTextContent("up to 0.005 for each of them");
  });

  test("worklogs that add up to nothing: the share is a dash with its reason — never 0% — and no donut is drawn", async () => {
    serve(mockFetch, COSTS_NO_SHARE);
    renderPage();
    await screen.findByRole("group", { name: TOTAL_TILE });
    const share = tile("Foreign work share");
    expect(within(share).getByText("—")).toBeInTheDocument();
    expect(within(share).getByText("Nothing was logged, so there is no share.")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  test("nothing logged in the period is the empty state, with no tiles and no tables", async () => {
    serve(mockFetch, COSTS_EMPTY);
    renderPage();
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByRole("group", { name: TOTAL_TILE })).not.toBeInTheDocument();
  });

  test("a rejected slice (an inactive team, an unknown sprint) is the normal failure alert", async () => {
    serve(mockFetch, { title: "unknown team", status: 400 }, 400);
    renderPage("/reports/costs?teamId=99");
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  test("Costs is a group of one: no report tabs", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("group", { name: TOTAL_TILE });
    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
  });
});

describe("ReportCosts page — a team is required", () => {
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

  test("the team control has no 'All teams' option, no clear button, and its picked option cannot be clicked off", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("group", { name: TOTAL_TILE });
    await openReportFilters(user);
    const team = screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement;
    expect(team.value).toBe("Alpha");
    expect(screen.queryByRole("button", { name: "Clear Team" })).not.toBeInTheDocument();
    await user.click(team);
    expect((await screen.findAllByRole("option")).map((o) => o.textContent)).toEqual(["Alpha", "Beta", "Gamma"]);
    expect(screen.queryByRole("option", { name: "All teams" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: "Alpha" }));
    expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("Alpha");
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}teamId=1`]);
  });

  test("a link naming no team (or the unassigned bucket) gets the default team, URL included, and never asks for the whole unit", async () => {
    serve(mockFetch);
    const { unmount } = renderPage("/reports/costs?domain=FLO");
    await screen.findByRole("group", { name: TOTAL_TILE });
    await waitFor(() => expect(search().get("teamId")).toBe("1"));
    expect(search().get("domain")).toBe("FLO");
    unmount();
    renderPage("/reports/costs?teamId=0");
    await waitFor(() => expect(search().get("teamId")).toBe("1"));
    expect(calls(mockFetch).every((url) => url.includes("teamId=1"))).toBe(true);
  });

  test("a remembered filter without a team does not bring the unit-wide view back", async () => {
    localStorage.setItem("flow.viewSettings.reports.costs.filter", JSON.stringify("lastSprints=3&domain=FLO"));
    serve(mockFetch);
    renderPage();
    await screen.findByRole("group", { name: TOTAL_TILE });
    await waitFor(() => expect(search().get("teamId")).toBe("1"));
    expect(calls(mockFetch)).toEqual([`${URL_PREFIX}lastSprints=3&teamId=1&domain=FLO`]);
  });

  test("with no team at all the page says so and requests nothing", async () => {
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(
        url === "/api/v1/reports/filters" ? jsonResponse(200, { ...FILTERS, teams: [] }) : jsonResponse(404, { title: "Not Found", status: 404 }),
      ),
    );
    renderPage();
    expect(await screen.findByText(/Costs are read per team and there is no team yet/)).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual([]);
    expect(screen.queryByRole("button", { name: /^Filters/ })).not.toBeInTheDocument();
  });
});
