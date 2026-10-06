import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import ReportSprintConsistency from "./ReportSprintConsistency";
import { jsonResponse } from "../test/http";
import { CONSISTENCY_EMPTY, CONSISTENCY_UNIT, CONSISTENCY_USER, FILTERS } from "../test/reportFixtures";
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
    if (url.startsWith("/api/v1/reports/sprint-consistency?")) return Promise.resolve(report?.() ?? jsonResponse(200, CONSISTENCY_UNIT));
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
    expect(charts[0].getAttribute("data-labels")).toBe("Beta · Beta 1|Alpha · Alpha 2");
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

    expect(screen.getByRole("link", { name: "Show Alpha" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Show Beta" })).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual(["/api/v1/reports/sprint-consistency?"]);
  });

  test("at user level the sprint row shows the account's frozen figures and the drift badge", async () => {
    serve(mockFetch, () => jsonResponse(200, CONSISTENCY_USER));
    renderPage("/reports/sprint-consistency?teamId=1&accountId=acc-ann");
    await screen.findAllByTestId("bar-chart");

    const table = screen.getByRole("heading", { name: "All figures" }).closest("div[class*=Paper]") as HTMLElement;
    const row = within(table).getByRole("row", { name: /Alpha 2/ });
    expect(within(row).getByText("8 (3)")).toBeInTheDocument();
    expect(within(row).getByText("Drift")).toBeInTheDocument();
    expect(within(row).getByText("Frozen at completion: committed 8 · final 9 · delivered 5 MD")).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual(["/api/v1/reports/sprint-consistency?teamId=1&accountId=acc-ann"]);
  });

  test("offers no report-specific controls — no bucket, domain view, domain, activity type or category", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findAllByTestId("bar-chart");
    expect(screen.queryByRole("radio", { name: "Week" })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio", { name: "Delivered in" })).not.toBeInTheDocument();
    for (const name of ["Domain", "Activity type", "Work category"]) {
      expect(screen.queryByRole("combobox", { name })).not.toBeInTheDocument();
    }
  });

  test("drilling into a team narrows the URL and refetches", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("link", { name: "Show Alpha" }));
    await waitFor(() => expect(calls(mockFetch)).toContain("/api/v1/reports/sprint-consistency?teamId=1"));
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
      return Promise.resolve(jsonResponse(200, CONSISTENCY_UNIT));
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findAllByTestId("bar-chart");
    await user.click(screen.getByRole("combobox", { name: "Period" }));
    await user.click(await screen.findByRole("option", { name: "Last 6 sprints" }));

    await waitFor(() => expect(calls(mockFetch)).toContain("/api/v1/reports/sprint-consistency?lastSprints=6"));
    expect(screen.getAllByTestId("bar-chart")[0].closest("[aria-busy]")).toHaveAttribute("aria-busy", "true");

    release?.(jsonResponse(200, CONSISTENCY_EMPTY));
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
  });
});
