import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import ReportCycleTime from "./ReportCycleTime";
import { jsonResponse } from "../test/http";
import { CYCLE_TIME, CYCLE_TIME_ALL_HIDDEN, CYCLE_TIME_EMPTY, CYCLE_TIME_MONTHS, FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout): histograms and the trend are probes.
vi.mock("@mantine/charts", () => ({
  BarChart: (props: { data: { count: number }[]; xAxisLabel?: string }) => (
    <div data-testid="bar-chart" data-counts={props.data.map((r) => r.count).join(",")} data-x-label={props.xAxisLabel} />
  ),
  LineChart: (props: { data: { label: string; p50: number | null }[]; connectNulls: boolean }) => (
    <div
      data-testid="line-chart"
      data-labels={props.data.map((r) => r.label).join("|")}
      data-p50={props.data.map((r) => String(r.p50)).join(",")}
      data-connect-nulls={String(props.connectNulls)}
    />
  ),
  ChartTooltip: () => null,
}));

type FetchMock = ReturnType<typeof vi.fn>;
const URL_PREFIX = "/api/v1/reports/cycle-time?";

function serve(mockFetch: FetchMock, report?: (url: string) => Response) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) {
      return Promise.resolve(
        report?.(url) ?? jsonResponse(200, new URL(url, "http://x").searchParams.get("bucket") === "MONTH" ? CYCLE_TIME_MONTHS : CYCLE_TIME),
      );
    }
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function renderPage(route = "/reports/cycle-time") {
  return renderWithProviders(
    <Routes>
      <Route path="/reports/cycle-time" element={<ReportCycleTime />} />
    </Routes>,
    { route },
  );
}

const calls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((u) => u.startsWith(URL_PREFIX));

describe("ReportCycleTime page", () => {
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

  test("working days first, elapsed days beside it, each with its own accounting that reconciles", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "Cycle time" })).toBeInTheDocument();
    const working = await screen.findByRole("heading", { name: "Working days" });
    const elapsed = screen.getByRole("heading", { name: "Elapsed days" });
    expect(working.compareDocumentPosition(elapsed) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByText("Primary view — days on the configured working calendar.")).toBeInTheDocument();

    await waitFor(() => expect(screen.getAllByTestId("bar-chart")).toHaveLength(2));
    const histograms = screen.getAllByTestId("bar-chart").map((c) => [c.getAttribute("data-counts"), c.getAttribute("data-x-label")]);
    expect(histograms).toEqual([["3,6,5,3,1", "working days"], ["2,5,6,3,2", "elapsed days"]]);
    const strips = screen.getAllByRole("group", { name: "Percentiles" });
    expect(within(strips[0]).getByText("3.5")).toBeInTheDocument();
    expect(within(strips[1]).getByText("5")).toBeInTheDocument();

    // Two accountings, each n + reasons = population.
    expect(screen.getAllByText("18 + 2 = 20")).toHaveLength(2);
    expect(screen.getAllByText("Of 20 finished in this period:")).toHaveLength(2);
    expect(screen.getAllByText(/Never started \(created straight into done\), so no cycle/)).toHaveLength(2);
    expect(calls(mockFetch)).toEqual([URL_PREFIX]);
  });

  test("the trend: hidden buckets are gaps (null, never zero), the line is not bridged, and a table lists every bucket", async () => {
    serve(mockFetch);
    renderPage();
    const chart = await screen.findByTestId("line-chart");
    expect(chart.getAttribute("data-labels")).toBe("2026-09-07|2026-09-14|2026-09-21|2026-09-28");
    expect(chart.getAttribute("data-p50")).toBe("3,null,2.5,null");
    expect(chart.getAttribute("data-connect-nulls")).toBe("false");

    const table = screen.getByRole("table", { name: "Trend, as a table" });
    // A table that can outgrow its box scrolls in a focusable, labelled region (axe: scrollable-region-focusable).
    expect(table.closest('[role="region"]')).toHaveAttribute("tabindex", "0");
    expect(within(table).getByRole("columnheader", { name: "Week starting" })).toBeInTheDocument();
    const rows = within(table).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
    expect(rows).toEqual([
      ["2026-09-07", "8", "3", "7.5"],
      ["2026-09-14", "3", "—", "—"],
      ["2026-09-21", "7", "2.5", "6"],
      ["2026-09-28", "0", "—", "—"],
    ]);
    expect(screen.getByText(/A period with too few items has no line point — a gap, never a zero/)).toBeInTheDocument();
  });

  test("a trend with no plottable bucket says so instead of drawing an empty frame; the table stays", async () => {
    serve(mockFetch, () => jsonResponse(200, CYCLE_TIME_ALL_HIDDEN));
    renderPage();
    expect(await screen.findByText("No period has enough finished items to show a median and p90.")).toBeInTheDocument();
    expect(screen.queryByTestId("line-chart")).not.toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Trend, as a table" })).toBeInTheDocument();
  });

  test("the bucket control writes the URL, refetches and relabels the trend", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("line-chart");
    expect(screen.getByRole("radio", { name: "Week" })).toBeChecked();
    await user.click(screen.getByText("Month"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}bucket=MONTH`));
    await waitFor(() => expect(screen.getByTestId("line-chart").getAttribute("data-labels")).toBe("2026-08|2026-09"));
    expect(within(screen.getByRole("table", { name: "Trend, as a table" })).getByRole("columnheader", { name: "Month" })).toBeInTheDocument();
  });

  test("groups: a team below the minimum shows counts and a dash; teams link in", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("line-chart");
    const groups = screen.getByRole("heading", { name: "By team" }).closest("div") as HTMLElement;
    const rows = within(groups).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
    expect(rows).toEqual([
      ["Alpha", "10", "9", "3.2", "9", "5"],
      ["Beta", "7", "7", "3.8", "7", "5.5"],
      ["Unassigned", "3", "2", "—", "2", "—"],
    ]);
    expect(within(groups).getAllByRole("link")).toHaveLength(2);
    expect(screen.getByText("Groups below the minimum sample show counts only.")).toBeInTheDocument();
  });

  test("offers the domain view, domain, activity type, work category and the bucket", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("line-chart");
    expect(screen.getByRole("radio", { name: "Delivered in" })).toBeChecked();
    for (const name of ["Domain", "Activity type", "Work category"]) {
      expect(screen.getByRole("combobox", { name })).toBeInTheDocument();
    }
    expect(screen.getByRole("radio", { name: "Week" })).toBeInTheDocument();
  });

  test("Cycle time is a tab of the Delivery group", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("line-chart");
    expect(screen.getByRole("tab", { name: "Cycle time" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual(["Velocity", "Throughput", "Sprint consistency", "Cycle time"]);
  });

  test("a period with no finished tasks is the empty state — no trend card either", async () => {
    serve(mockFetch, () => jsonResponse(200, CYCLE_TIME_EMPTY));
    renderPage();
    expect(await screen.findByText("No data in this period")).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: "Trend, as a table" })).not.toBeInTheDocument();
  });

  test("a failure is an inline alert", async () => {
    serve(mockFetch, () => jsonResponse(500, { title: "boom", status: 500 }));
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
  });

  test("changing the bucket keeps the previous trend labels on screen, busy, until the new report lands", async () => {
    let release: ((response: Response) => void) | undefined;
    const deferred = new Promise<Response>((resolve) => {
      release = resolve;
    });
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
      if (url.includes("bucket=MONTH")) return deferred;
      return Promise.resolve(jsonResponse(200, CYCLE_TIME));
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("line-chart");
    await user.click(screen.getByText("Month"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}bucket=MONTH`));
    // Old data, old (weekly) labels — never relabelled with the new resolution while in flight.
    expect(screen.getByTestId("line-chart").getAttribute("data-labels")).toBe("2026-09-07|2026-09-14|2026-09-21|2026-09-28");
    expect(screen.getByTestId("line-chart").closest("[aria-busy]")).toHaveAttribute("aria-busy", "true");
    release?.(jsonResponse(200, CYCLE_TIME_MONTHS));
    await waitFor(() => expect(screen.getByTestId("line-chart").getAttribute("data-labels")).toBe("2026-08|2026-09"));
  });
});
