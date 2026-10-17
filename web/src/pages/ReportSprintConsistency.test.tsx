import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import ReportSprintConsistency from "./ReportSprintConsistency";
import { jsonResponse } from "../test/http";
import { CONSISTENCY_EMPTY, CONSISTENCY_SLICED, CONSISTENCY_TEAM, FILTERS } from "../test/reportFixtures";
import { openReportFilters } from "../test/reportFilters";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout), so each chart is a probe carrying its props.
vi.mock("@mantine/charts", () => ({
  BarChart: (props: { data: { label: string }[]; series: { name: string }[]; type?: string }) => (
    <div
      data-testid="bar-chart"
      data-labels={props.data.map((r) => r.label).join("|")}
      data-series={props.series.map((s) => s.name).join(",")}
      data-type={props.type}
    />
  ),
}));

type FetchMock = ReturnType<typeof vi.fn>;

function serve(mockFetch: FetchMock, report?: () => Response) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith("/api/v1/reports/sprint-consistency?")) {
      const params = new URL(url, "http://x").searchParams;
      const sliced = params.has("activityType") || params.has("workCategory");
      return Promise.resolve(report?.() ?? jsonResponse(200, sliced ? CONSISTENCY_SLICED : CONSISTENCY_TEAM));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function renderPage(route = "/reports/sprint-consistency") {
  return renderWithProviders(
    <Routes>
      <Route path="/reports/sprint-consistency" element={<ReportSprintConsistency />} />
    </Routes>,
    { route },
  );
}

const calls = (mockFetch: FetchMock) =>
  mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith("/api/v1/reports/sprint-consistency"));

describe("ReportSprintConsistency page", () => {
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

  test("renders the three compositions, the full-figure table with drift, and the team groups", async () => {
    serve(mockFetch);
    renderPage();

    expect(await screen.findByRole("heading", { level: 2, name: "Sprint consistency" })).toBeInTheDocument();
    // The three charts are separate lazy chunks: wait for all of them, not the first to land.
    const charts = await waitFor(() => {
      const found = screen.getAllByTestId("bar-chart");
      expect(found).toHaveLength(3);
      return found;
    });
    expect(charts.map((c) => c.getAttribute("data-series"))).toEqual([
      "committedMd,deliveredMd",
      "carriedOverMd,deliveredMd,droppedMd",
      "addedMd,removedMd",
    ]);
    // Only the partition is stacked; sprints in completion order, the team named as two teams share it.
    expect(charts.map((c) => c.getAttribute("data-type"))).toEqual(["default", "stacked", "default"]);
    expect(charts[0].getAttribute("data-labels")).toBe("Alpha 2");
    expect(screen.getByText("Final scope = delivered + carried over + dropped, so each bar's height is the sprint's final scope.")).toBeInTheDocument();

    // The ten-column table scrolls sideways on a normal screen: its scroller is a focusable, named region (axe: scrollable-region-focusable).
    expect(screen.getByRole("region", { name: "Sprints and their figures" })).toHaveAttribute("tabindex", "0");

    const table = screen.getByRole("heading", { name: "All figures" }).closest("div[class*=Paper]") as HTMLElement;
    const row = within(table).getByRole("row", { name: /Alpha 2/ });
    for (const cell of ["2026-09-10", "20 (8)", "4.5 (2)", "1 (1)", "24.5 (10)", "18 (7)", "4 (2)", "2.5 (1)"]) {
      expect(within(row).getByText(cell)).toBeInTheDocument();
    }
    expect(within(row).getByText("Drift")).toBeInTheDocument();
    expect(within(row).getByText("Frozen at completion: committed 20 · final 22 · delivered 16.5 MD")).toBeInTheDocument();
    expect(screen.getAllByText("Drift")).toHaveLength(1);

    // The page is always one team: a bare link starts on the first team by name, and the members below it are names, not drill links.
    expect(calls(mockFetch)).toEqual(["/api/v1/reports/sprint-consistency?teamId=1"]);
    const members = screen.getByRole("table", { name: "By member" });
    expect(within(members).getByText("Ann Author")).toBeInTheDocument();
    expect(within(members).getByText("Bob Builder")).toBeInTheDocument();
    expect(within(members).queryByRole("link")).not.toBeInTheDocument();
    expect(screen.queryByText(/Sliced by activity type or work category/)).not.toBeInTheDocument();
  });

  test("the team control has no 'All teams' option and no clear button", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByTestId("bar-chart");
    await openReportFilters(user);

    const team = screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement;
    expect(team.value).toBe("Alpha");
    expect(screen.queryByRole("button", { name: "Clear Team" })).not.toBeInTheDocument();
    await user.click(team);
    expect((await screen.findAllByRole("option")).map((o) => o.textContent)).toEqual(["Alpha", "Beta", "Gamma"]);
    expect(screen.queryByRole("option", { name: "All teams" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: "Beta" }));
    await waitFor(() => expect(calls(mockFetch)).toContain("/api/v1/reports/sprint-consistency?teamId=2"));
  });

  test("offers no member control and a pasted accountId is dropped from the URL", async () => {
    serve(mockFetch);
    renderPage("/reports/sprint-consistency?teamId=1&accountId=acc-ann");
    await screen.findAllByTestId("bar-chart");
    await openReportFilters();
    expect(screen.queryByRole("combobox", { name: "Member" })).not.toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Team" })).toBeInTheDocument();
    // Never asked for with the member: the page reads the whole team.
    expect(calls(mockFetch).every((url) => !url.includes("accountId"))).toBe(true);
    expect(calls(mockFetch)).toContain("/api/v1/reports/sprint-consistency?teamId=1");
  });

  test("offers activity type and work category, not bucket, domain view or domain", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findAllByTestId("bar-chart");
    await openReportFilters();
    expect(screen.queryByRole("radio", { name: "Week" })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Delivered in" })).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Domain" })).not.toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Activity type" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Work category" })).toBeInTheDocument();
  });

  test("slicing by activity type or work category asks for it, says the figures are live, and clearing it brings the snapshot back", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByTestId("bar-chart");
    await openReportFilters(user);
    expect(screen.getAllByText("Drift")).toHaveLength(1);

    await user.click(screen.getByRole("combobox", { name: "Activity type" }));
    await user.click(await screen.findByRole("option", { name: "Bug" }));
    await waitFor(() => expect(calls(mockFetch)).toContain("/api/v1/reports/sprint-consistency?teamId=1&activityType=Bug"));
    expect(await screen.findByText(/Sliced by activity type or work category/)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText("Drift")).not.toBeInTheDocument());
    expect(screen.queryByText(/Frozen at completion/)).not.toBeInTheDocument();

    await user.click(screen.getByRole("combobox", { name: "Work category" }));
    await user.click(await screen.findByRole("option", { name: "Maintenance" }));
    await waitFor(() =>
      expect(calls(mockFetch)).toContain("/api/v1/reports/sprint-consistency?teamId=1&activityType=Bug&workCategory=Maintenance"),
    );

    // Clicking the picked option again clears it (allowDeselect).
    await user.click(screen.getByRole("combobox", { name: "Activity type" }));
    await user.click(await screen.findByRole("option", { name: "Bug" }));
    await user.click(screen.getByRole("combobox", { name: "Work category" }));
    await user.click(await screen.findByRole("option", { name: "Maintenance" }));
    await waitFor(() => expect(screen.queryByText(/Sliced by activity type or work category/)).not.toBeInTheDocument());
    expect(await screen.findByText("Drift")).toBeInTheDocument();
    expect(calls(mockFetch).at(-1)).toBe("/api/v1/reports/sprint-consistency?teamId=1");
  });

  test("an empty period is ONE empty state, not one per chart", async () => {
    serve(mockFetch, () => jsonResponse(200, CONSISTENCY_EMPTY));
    renderPage();
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
    expect(screen.getAllByText("No data in this period")).toHaveLength(1);
    expect(screen.queryByTestId("bar-chart")).not.toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  test("a report failure is one inline alert; the filter bar stays usable", async () => {
    serve(mockFetch, () => jsonResponse(500, { title: "boom", status: 500 }));
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
    expect(screen.getAllByRole("alert")).toHaveLength(1);
    await openReportFilters();
    expect(screen.getByRole("combobox", { name: "Period" })).toBeInTheDocument();
  });

  test("the first load shows a spinner and no charts", async () => {
    mockFetch.mockImplementation((url: string) =>
      url === "/api/v1/reports/filters" ? Promise.resolve(jsonResponse(200, FILTERS)) : new Promise<Response>(() => undefined),
    );
    renderPage();
    expect(await screen.findByRole("status")).toBeInTheDocument();
    expect(screen.queryByTestId("bar-chart")).not.toBeInTheDocument();
  });

  test("a period change keeps the previous charts on screen and busy until the new report lands", async () => {
    let release: ((response: Response) => void) | undefined;
    const deferred = new Promise<Response>((resolve) => {
      release = resolve;
    });
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
      if (url.includes("lastSprints=6")) return deferred;
      return Promise.resolve(jsonResponse(200, CONSISTENCY_TEAM));
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByTestId("bar-chart");
    await openReportFilters();
    await user.click(screen.getByRole("combobox", { name: "Period" }));
    await user.click(await screen.findByRole("option", { name: "Last 6 sprints" }));

    await waitFor(() => expect(calls(mockFetch)).toContain("/api/v1/reports/sprint-consistency?lastSprints=6&teamId=1"));
    expect(screen.getAllByTestId("bar-chart")[0].closest("[aria-busy]")).toHaveAttribute("aria-busy", "true");

    release?.(jsonResponse(200, CONSISTENCY_EMPTY));
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
  });
});
