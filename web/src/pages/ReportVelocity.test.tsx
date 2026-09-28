import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import ReportVelocity from "./ReportVelocity";
import { jsonResponse } from "../test/http";
import { FILTERS, VELOCITY_EMPTY, VELOCITY_TEAM, VELOCITY_UNIT } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout), so the chart is a probe carrying its props.
vi.mock("@mantine/charts", () => ({
  BarChart: (props: { data: { label: string }[]; series: { name: string }[] }) => (
    <div data-testid="bar-chart" data-labels={props.data.map((r) => r.label).join("|")} data-series={props.series.map((s) => s.name).join(",")} />
  ),
}));

type FetchMock = ReturnType<typeof vi.fn>;

interface Serve {
  filters?: () => Response;
  velocity?: (url: string) => Response;
}

function serve(mockFetch: FetchMock, overrides: Serve = {}) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(overrides.filters?.() ?? jsonResponse(200, FILTERS));
    if (url.startsWith("/api/v1/reports/velocity?")) {
      return Promise.resolve(
        overrides.velocity?.(url) ??
          jsonResponse(200, new URL(url, "http://x").searchParams.has("teamId") ? VELOCITY_TEAM : VELOCITY_UNIT),
      );
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

  test("loads the filters first, then the report; renders the chart, the sprint table and the team groups", async () => {
    serve(mockFetch);
    renderPage();

    expect(await screen.findByRole("heading", { level: 2, name: "Velocity" })).toBeInTheDocument();
    const chart = await screen.findByTestId("bar-chart");
    // Completion order (Beta 1 finished first); both teams share the chart, so the label names the team.
    expect(chart.getAttribute("data-labels")).toBe("Beta · Beta 1|Alpha · Alpha 2");
    expect(chart.getAttribute("data-series")).toBe("initialMd,finalMd");

    const sprints = screen.getAllByRole("table")[0];
    const row = within(sprints).getByRole("row", { name: /Alpha 2/ });
    expect(within(row).getByText("2026-09-10")).toBeInTheDocument();
    expect(within(row).getByText("24.5")).toBeInTheDocument();
    // Drift only on the drifted sprint, naming the frozen figures.
    expect(within(row).getByText("Drift")).toBeInTheDocument();
    expect(within(row).getByText("Frozen at completion: initial 20 MD, final 22 MD")).toBeInTheDocument();
    expect(screen.getAllByText("Drift")).toHaveLength(1);

    expect(screen.getByText("Derived 2026-05-28 · configuration revision 4")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Show Alpha" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Show Beta" })).toBeInTheDocument();

    // The page query ran exactly once, after the filters, with an empty (server-default) filter.
    expect(velocityCalls(mockFetch)).toEqual(["/api/v1/reports/velocity?"]);
  });

  test("drilling into a team narrows the URL, refetches, and shows the members", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("link", { name: "Show Alpha" }));

    expect(await screen.findByRole("link", { name: "Show Ann Author" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "By member" })).toBeInTheDocument();
    expect(screen.getByText("Unassigned")).toBeInTheDocument();
    expect(velocityCalls(mockFetch)).toContain("/api/v1/reports/velocity?teamId=1");
    expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("Alpha");
  });

  test("the URL's filter drives the request", async () => {
    serve(mockFetch);
    renderPage("/reports/velocity?lastSprints=3&teamId=2");
    await screen.findByTestId("bar-chart");
    expect(velocityCalls(mockFetch)).toEqual(["/api/v1/reports/velocity?lastSprints=3&teamId=2"]);
  });

  test("an empty period shows the empty state and no groups", async () => {
    serve(mockFetch, { velocity: () => jsonResponse(200, VELOCITY_EMPTY) });
    renderPage();
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
    expect(screen.queryByTestId("bar-chart")).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "By team" })).not.toBeInTheDocument();
  });

  test("a report failure is an inline alert, the filter bar stays usable", async () => {
    serve(mockFetch, { velocity: () => jsonResponse(500, { title: "boom", status: 500 }) });
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
    expect(screen.getByRole("combobox", { name: "Period" })).toBeInTheDocument();
  });

  test("a filters failure is an inline alert and no report is requested", async () => {
    serve(mockFetch, { filters: () => jsonResponse(500, { title: "boom", status: 500 }) });
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
    expect(velocityCalls(mockFetch)).toEqual([]);
    expect(screen.queryByRole("combobox", { name: "Period" })).not.toBeInTheDocument();
  });

  test("a bare link with a remembered team issues exactly one report fetch, already scoped to it", async () => {
    localStorage.setItem("flow.viewSettings.reports.teamId", "1");
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("bar-chart");
    // Give any stray refetch (URL rewrite changing the key) a chance to show up.
    await waitFor(() => expect(screen.getByRole("link", { name: "Show Ann Author" })).toBeInTheDocument());
    expect(velocityCalls(mockFetch)).toEqual(["/api/v1/reports/velocity?teamId=1"]);
  });

  test("a unit-level sprint link stays unit-level, even with a remembered team, and shows its sprint", async () => {
    localStorage.setItem("flow.viewSettings.reports.teamId", "1");
    serve(mockFetch);
    renderPage("/reports/velocity?sprintId=21");
    await screen.findByTestId("bar-chart");
    expect(velocityCalls(mockFetch)).toEqual(["/api/v1/reports/velocity?sprintId=21"]);
    expect((screen.getByRole("combobox", { name: "Sprint" }) as HTMLInputElement).value).toBe("Beta 1");
    expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("");
  });

  test("changing the period keeps the previous rows on screen, dimmed and busy, until the new report lands", async () => {
    let release: ((response: Response) => void) | undefined;
    const deferred = new Promise<Response>((resolve) => {
      release = resolve;
    });
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
      if (url.includes("lastSprints=6")) return deferred;
      return Promise.resolve(jsonResponse(200, VELOCITY_UNIT));
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("bar-chart");
    const busy = () => screen.getByTestId("bar-chart").closest("[aria-busy]");
    expect(busy()).toHaveAttribute("aria-busy", "false");

    await user.click(screen.getByRole("combobox", { name: "Period" }));
    await user.click(await screen.findByRole("option", { name: "Last 6 sprints" }));

    // The new request is in flight; the old report is still what the page shows.
    await waitFor(() => expect(velocityCalls(mockFetch)).toContain("/api/v1/reports/velocity?lastSprints=6"));
    expect(busy()).toHaveAttribute("aria-busy", "true");
    expect(screen.getByText("Alpha 2")).toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();

    release?.(jsonResponse(200, VELOCITY_EMPTY));
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
    expect(screen.queryByTestId("bar-chart")).not.toBeInTheDocument();
  });
});
