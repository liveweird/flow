import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import ReportVelocity from "./ReportVelocity";
import { jsonResponse } from "../test/http";
import { FILTERS, VELOCITY_EMPTY, VELOCITY_TEAM, VELOCITY_USER, VELOCITY_USERS } from "../test/reportFixtures";
import { openReportFilters } from "../test/reportFilters";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout), so the chart is a probe carrying its props.
vi.mock("@mantine/charts", () => {
  const probe = (testId: string) => (props: { data: { label: string }[]; series: { name: string }[] }) => (
    <div data-testid={testId} data-labels={props.data.map((r) => r.label).join("|")} data-series={props.series.map((s) => s.name).join(",")} />
  );
  return { CompositeChart: probe("composite-chart"), BarChart: probe("bar-chart"), ChartTooltip: () => null };
});

type FetchMock = ReturnType<typeof vi.fn>;

interface Serve {
  filters?: () => Response;
  velocity?: (url: string) => Response;
}

function serve(mockFetch: FetchMock, overrides: Serve = {}) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(overrides.filters?.() ?? jsonResponse(200, FILTERS));
    if (url.startsWith("/api/v1/reports/velocity?")) {
      const params = new URL(url, "http://x").searchParams;
      const accounts = params.getAll("accountId").length;
      const report = accounts > 1 ? VELOCITY_USERS : accounts === 1 ? VELOCITY_USER : VELOCITY_TEAM;
      return Promise.resolve(overrides.velocity?.(url) ?? jsonResponse(200, report));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function renderPage(route = "/reports/velocity") {
  return renderWithProviders(
    <Routes>
      <Route path="/reports/velocity" element={<ReportVelocity />} />
    </Routes>,
    { route },
  );
}

const velocityCalls = (mockFetch: FetchMock) =>
  mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith("/api/v1/reports/velocity"));

describe("ReportVelocity page", () => {
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

  test("loads the filters first, then the team's report; renders the chart, the sprint table with capacity and load, and the member groups", async () => {
    serve(mockFetch);
    renderPage();

    expect(await screen.findByRole("heading", { level: 2, name: "Velocity" })).toBeInTheDocument();
    const chart = await screen.findByTestId("composite-chart");
    // Completion order; one team, so the label is the sprint's name.
    expect(chart.getAttribute("data-labels")).toBe("Alpha 1|Alpha 2");
    expect(chart.getAttribute("data-series")).toBe("initialMd,finalMd,capacityMd");

    const sprints = screen.getAllByRole("table")[0];
    expect(within(sprints).getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
      "Sprint",
      "Team",
      "Completed",
      "Initial (MD)",
      "Initial (items)",
      "Final (MD)",
      "Final (items)",
      "Capacity (MD)",
      "Load",
      "Since completion",
    ]);
    const alpha2 = within(sprints).getByRole("row", { name: /^Alpha 2 / });
    expect(within(alpha2).getByText("2026-09-10")).toBeInTheDocument();
    expect(within(alpha2).getByText("24.5")).toBeInTheDocument();
    // Capacity 25 is the computed default (badge in words), load = 20 / 25.
    expect(within(alpha2).getByText("25")).toBeInTheDocument();
    expect(within(alpha2).getByText("default")).toBeInTheDocument();
    expect(within(alpha2).getByText("80%")).toBeInTheDocument();
    // Drift only on the drifted sprint, naming the frozen figures.
    expect(within(alpha2).getByText("Drift")).toBeInTheDocument();
    expect(within(alpha2).getByText("Frozen at completion: initial 20 MD, final 22 MD")).toBeInTheDocument();
    expect(screen.getAllByText("Drift")).toHaveLength(1);
    // A configured capacity (12) carries no badge: 15 / 12 = 125%.
    const alpha1 = within(sprints).getByRole("row", { name: /^Alpha 1 / });
    expect(within(alpha1).getByText("12")).toBeInTheDocument();
    expect(within(alpha1).getByText("125%")).toBeInTheDocument();
    expect(within(alpha1).queryByText("default")).not.toBeInTheDocument();
    expect(screen.getAllByText("default")).toHaveLength(1);
    expect(screen.getByText(/Load is the initial scope divided by the capacity/)).toBeInTheDocument();

    expect(screen.getByText("Derived 2026-05-28 · configuration revision 4")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Show Ann Author" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "By member" })).toBeInTheDocument();

    // A bare link is never read for the unit: the page starts on the first team by name and asks for that once.
    expect(velocityCalls(mockFetch)).toEqual(["/api/v1/reports/velocity?teamId=1"]);
  });

  test("a sprint without a capacity shows dashes and no marker, and no load", async () => {
    const sprint = { ...VELOCITY_TEAM.sprints[0], capacityMd: null, capacitySource: null } as const;
    serve(mockFetch, { velocity: () => jsonResponse(200, { ...VELOCITY_TEAM, sprints: [sprint] }) });
    renderPage();
    await screen.findByTestId("composite-chart");
    expect(screen.getByTestId("composite-chart").getAttribute("data-series")).toBe("initialMd,finalMd");
    const row = within(screen.getAllByRole("table")[0]).getByRole("row", { name: /^Alpha 2 / });
    expect(within(row).getAllByText("—")).toHaveLength(2);
    expect(screen.queryByText("default")).not.toBeInTheDocument();
    expect(screen.queryByText(/Load is the initial scope/)).not.toBeInTheDocument();
  });

  test("the team control has no 'All teams' option, no clear button, and its picked option cannot be clicked off", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("composite-chart");
    await openReportFilters(user);

    const team = screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement;
    expect(team.value).toBe("Alpha");
    expect(team).not.toHaveAttribute("placeholder");
    expect(screen.queryByRole("button", { name: "Clear Team" })).not.toBeInTheDocument();
    await user.click(team);
    expect((await screen.findAllByRole("option")).map((o) => o.textContent)).toEqual(["Alpha", "Beta", "Gamma"]);
    expect(screen.queryByRole("option", { name: "All teams" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: "Alpha" }));
    expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("Alpha");
    expect(velocityCalls(mockFetch)).toEqual(["/api/v1/reports/velocity?teamId=1"]);
  });

  test("switching team asks for that team", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("composite-chart");
    await openReportFilters(user);
    await user.click(screen.getByRole("combobox", { name: "Team" }));
    await user.click(await screen.findByRole("option", { name: "Beta" }));
    await waitFor(() => expect(velocityCalls(mockFetch)).toContain("/api/v1/reports/velocity?teamId=2"));
  });

  test("a bare link starts on the remembered team, else the first team by name when the remembered one is gone", async () => {
    localStorage.setItem("flow.viewSettings.reports.teamId", "2");
    serve(mockFetch);
    const first = renderPage();
    await screen.findByTestId("composite-chart");
    await waitFor(() => expect(velocityCalls(mockFetch)).toEqual(["/api/v1/reports/velocity?teamId=2"]));
    first.unmount();

    mockFetch.mockReset();
    localStorage.setItem("flow.viewSettings.reports.teamId", "99");
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("composite-chart");
    await waitFor(() => expect(velocityCalls(mockFetch)).toEqual(["/api/v1/reports/velocity?teamId=1"]));
  });

  test("a link with only a period gets a team too, once, and keeps the period", async () => {
    serve(mockFetch);
    renderPage("/reports/velocity?lastSprints=3");
    await screen.findByTestId("composite-chart");
    expect(velocityCalls(mockFetch)).toEqual(["/api/v1/reports/velocity?lastSprints=3&teamId=1"]);
    await openReportFilters();
    expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("Alpha");
  });

  test("a sprint link without a team lands on the team that lists the sprint, even with another remembered", async () => {
    localStorage.setItem("flow.viewSettings.reports.teamId", "1");
    serve(mockFetch);
    renderPage("/reports/velocity?sprintId=21");
    await screen.findByTestId("composite-chart");
    expect(velocityCalls(mockFetch)).toEqual(["/api/v1/reports/velocity?sprintId=21&teamId=2"]);
    await openReportFilters();
    expect((screen.getByRole("combobox", { name: "Sprint" }) as HTMLInputElement).value).toBe("Beta 1");
    expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("Beta");
  });

  test("with no team at all the page says so and requests nothing", async () => {
    serve(mockFetch, { filters: () => jsonResponse(200, { ...FILTERS, teams: [] }) });
    renderPage();
    expect(await screen.findByText(/there is no team yet/)).toBeInTheDocument();
    expect(velocityCalls(mockFetch)).toEqual([]);
    expect(screen.queryByTestId("composite-chart")).not.toBeInTheDocument();
  });

  test("the member control picks several people: repeated accountId in the request, then stacked columns per person", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/velocity?teamId=1");
    await screen.findByTestId("composite-chart");
    await openReportFilters(user);

    await user.click(screen.getByRole("combobox", { name: "Member" }));
    await user.click(await screen.findByRole("option", { name: "Ann Author" }));
    await waitFor(() => expect(velocityCalls(mockFetch)).toContain("/api/v1/reports/velocity?teamId=1&accountId=acc-ann"));
    await user.click(await screen.findByRole("option", { name: "Bob Builder" }));
    await waitFor(() =>
      expect(velocityCalls(mockFetch)).toContain("/api/v1/reports/velocity?teamId=1&accountId=acc-ann&accountId=acc-bob"),
    );

    // User mode: two stacks, one segment per person, committed first.
    const chart = await screen.findByTestId("bar-chart");
    expect(chart.getAttribute("data-series")).toBe("c0,c1,f0,f1");
    expect(chart.getAttribute("data-labels")).toBe("Alpha 2");
    expect(screen.queryByTestId("composite-chart")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Initial and final scope per sprint, by person" })).toBeInTheDocument();
    expect(screen.getByRole("list", { name: "Chart legend" })).toHaveTextContent("Ann Author");

    // Capacity and load are team figures: gone. Every chart number is in a table.
    const sprints = screen.getAllByRole("table")[0];
    expect(within(sprints).queryByRole("columnheader", { name: "Capacity (MD)" })).not.toBeInTheDocument();
    expect(within(sprints).queryByRole("columnheader", { name: "Load" })).not.toBeInTheDocument();
    expect(screen.queryByText("default")).not.toBeInTheDocument();
    expect(within(sprints).getByRole("row", { name: /^Alpha 2 / })).toHaveTextContent("24.5");

    const byMember = screen.getByRole("table", { name: "Totals per person" });
    expect(within(byMember).getByRole("row", { name: /Ann Author/ })).toHaveTextContent("14.5");
    expect(within(byMember).getByRole("row", { name: /Bob Builder/ })).toHaveTextContent("10");
    const shares = screen.getByRole("table", { name: "Each person's initial and final scope per sprint" });
    expect(within(shares).getByRole("row", { name: /Alpha 2 Ann Author/ })).toHaveTextContent("14.5");
    expect(within(shares).getByRole("row", { name: /Alpha 2 Bob Builder/ })).toHaveTextContent("10");

    // The folded toggle counts the member dimension once, however many people are picked (the required team counts for nothing).
    expect(screen.getByRole("button", { name: /^Filters/ })).toHaveTextContent("1");
  });

  test("a link with repeated accountId selects those people and requests them in that order", async () => {
    serve(mockFetch);
    renderPage("/reports/velocity?teamId=1&accountId=acc-bob&accountId=acc-ann&accountId=acc-bob");
    await screen.findByTestId("bar-chart");
    expect(velocityCalls(mockFetch)).toEqual(["/api/v1/reports/velocity?teamId=1&accountId=acc-bob&accountId=acc-ann"]);
    await openReportFilters();
    const pills = screen.getByRole("group", { name: "Report filters" });
    expect(within(pills).getByText("Bob Builder")).toBeInTheDocument();
    expect(within(pills).getByText("Ann Author")).toBeInTheDocument();
  });

  test("one person is the same view with one segment per stack and no per-person share table", async () => {
    serve(mockFetch);
    renderPage("/reports/velocity?teamId=1&accountId=acc-ann");
    const chart = await screen.findByTestId("bar-chart");
    expect(chart.getAttribute("data-series")).toBe("c0,f0");
    const row = within(screen.getAllByRole("table")[0]).getByRole("row", { name: /^Alpha 2 / });
    expect(within(row).getByText("14.5")).toBeInTheDocument();
    expect(within(row).getByText("Drift")).toBeInTheDocument();
    expect(within(row).getByText("Frozen at completion: initial 12 MD, final 13 MD")).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Totals per person" })).toBeInTheDocument();
    // "By member" is the team level's drill table: nothing is below a person.
    expect(screen.queryByRole("heading", { name: "By member" })).not.toBeInTheDocument();
    expect(screen.queryByRole("table", { name: "Each person's initial and final scope per sprint" })).not.toBeInTheDocument();
    expect(velocityCalls(mockFetch)).toEqual(["/api/v1/reports/velocity?teamId=1&accountId=acc-ann"]);
  });

  test("changing the team drops the picked people", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/velocity?teamId=1&accountId=acc-ann&accountId=acc-bob");
    await screen.findByTestId("bar-chart");
    await openReportFilters(user);
    await user.click(screen.getByRole("combobox", { name: "Team" }));
    await user.click(await screen.findByRole("option", { name: "Beta" }));
    await waitFor(() => expect(velocityCalls(mockFetch)).toContain("/api/v1/reports/velocity?teamId=2"));
  });

  test("drilling into a member from the team table narrows to that person", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/reports/velocity?teamId=1");
    await user.click(await screen.findByRole("link", { name: "Show Ann Author" }));
    await waitFor(() => expect(velocityCalls(mockFetch)).toContain("/api/v1/reports/velocity?teamId=1&accountId=acc-ann"));
    expect(await screen.findByTestId("bar-chart")).toBeInTheDocument();
  });

  test("the URL's filter drives the request", async () => {
    serve(mockFetch);
    renderPage("/reports/velocity?lastSprints=3&teamId=2");
    await screen.findByTestId("composite-chart");
    expect(velocityCalls(mockFetch)).toEqual(["/api/v1/reports/velocity?lastSprints=3&teamId=2"]);
  });

  test("offers no report-specific controls — no bucket, domain view, domain, activity type or category", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("composite-chart");
    await openReportFilters();
    expect(screen.queryByRole("radio", { name: "Week" })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Delivered in" })).not.toBeInTheDocument();
    for (const name of ["Domain", "Activity type", "Work category"]) {
      expect(screen.queryByRole("combobox", { name })).not.toBeInTheDocument();
    }
  });

  test("an empty period shows the empty state and no groups", async () => {
    serve(mockFetch, { velocity: () => jsonResponse(200, VELOCITY_EMPTY) });
    renderPage();
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
    expect(screen.queryByTestId("composite-chart")).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "By member" })).not.toBeInTheDocument();
  });

  test("a report failure is an inline alert, the filter bar stays usable", async () => {
    serve(mockFetch, { velocity: () => jsonResponse(500, { title: "boom", status: 500 }) });
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
    await openReportFilters();
    expect(screen.getByRole("combobox", { name: "Period" })).toBeInTheDocument();
  });

  test("a filters failure is an inline alert and no report is requested", async () => {
    serve(mockFetch, { filters: () => jsonResponse(500, { title: "boom", status: 500 }) });
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
    expect(velocityCalls(mockFetch)).toEqual([]);
    expect(screen.queryByRole("button", { name: /^Filters/ })).not.toBeInTheDocument();
  });

  test("a bare link with a remembered team issues exactly one report fetch, already scoped to it", async () => {
    localStorage.setItem("flow.viewSettings.reports.teamId", "1");
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("composite-chart");
    // Give any stray refetch (URL rewrite changing the key) a chance to show up.
    await waitFor(() => expect(screen.getByRole("link", { name: "Show Ann Author" })).toBeInTheDocument());
    expect(velocityCalls(mockFetch)).toEqual(["/api/v1/reports/velocity?teamId=1"]);
  });

  test("changing the period keeps the previous rows on screen, dimmed and busy, until the new report lands", async () => {
    let release: ((response: Response) => void) | undefined;
    const deferred = new Promise<Response>((resolve) => {
      release = resolve;
    });
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
      if (url.includes("lastSprints=6")) return deferred;
      return Promise.resolve(jsonResponse(200, VELOCITY_TEAM));
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("composite-chart");
    const busy = () => screen.getByTestId("composite-chart").closest("[aria-busy]");
    expect(busy()).toHaveAttribute("aria-busy", "false");

    await openReportFilters();
    await user.click(screen.getByRole("combobox", { name: "Period" }));
    await user.click(await screen.findByRole("option", { name: "Last 6 sprints" }));

    // The new request is in flight; the old report is still what the page shows.
    await waitFor(() => expect(velocityCalls(mockFetch)).toContain("/api/v1/reports/velocity?lastSprints=6&teamId=1"));
    expect(busy()).toHaveAttribute("aria-busy", "true");
    expect(screen.getAllByText("Alpha 2").length).toBeGreaterThan(0);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();

    release?.(jsonResponse(200, VELOCITY_EMPTY));
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
    expect(screen.queryByTestId("composite-chart")).not.toBeInTheDocument();
  });
});
