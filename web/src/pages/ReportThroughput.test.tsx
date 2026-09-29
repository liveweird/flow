import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import ReportThroughput from "./ReportThroughput";
import { jsonResponse } from "../test/http";
import { FILTERS, THROUGHPUT_MONTHS, THROUGHPUT_NOTHING, THROUGHPUT_UNIT } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout), so the chart is a probe carrying its props.
vi.mock("@mantine/charts", () => ({
  BarChart: (props: { data: { label: string }[]; series: { name: string }[] }) => (
    <div data-testid="bar-chart" data-labels={props.data.map((r) => r.label).join("|")} data-series={props.series.map((s) => s.name).join(",")} />
  ),
  ChartTooltip: () => null,
}));

type FetchMock = ReturnType<typeof vi.fn>;

function serve(mockFetch: FetchMock, velocity?: (url: string) => Response) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith("/api/v1/reports/throughput?")) {
      return Promise.resolve(
        velocity?.(url) ??
          jsonResponse(200, new URL(url, "http://x").searchParams.get("bucket") === "MONTH" ? THROUGHPUT_MONTHS : THROUGHPUT_UNIT),
      );
    }
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function renderPage(route = "/reports/throughput") {
  return renderWithProviders(
    <Routes>
      <Route path="/reports/throughput" element={<ReportThroughput />} />
    </Routes>,
    { route },
  );
}

const calls = (mockFetch: FetchMock) =>
  mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith("/api/v1/reports/throughput"));

describe("ReportThroughput page", () => {
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

  test("renders the delivered-per-week chart, its totals, the sprint table with drift, and the team groups", async () => {
    serve(mockFetch);
    renderPage();

    expect(await screen.findByRole("heading", { level: 2, name: "Throughput" })).toBeInTheDocument();
    const chart = await screen.findByTestId("bar-chart");
    expect(chart.getAttribute("data-labels")).toBe("2026-09-07|2026-09-14|2026-09-21");
    expect(chart.getAttribute("data-series")).toBe("deliveredMd");
    expect(screen.getByText("Delivered in the period: 28 MD (11 items)")).toBeInTheDocument();
    expect(screen.getByText("Domain view: delivered in — a task counts in its own domain.")).toBeInTheDocument();

    // The sprint view: its own table, drift only on the drifted sprint, naming the frozen figures.
    const sprintCard = screen.getByRole("heading", { name: "Delivered per sprint" }).closest("div[class*=Paper]") as HTMLElement;
    const row = within(sprintCard).getByRole("row", { name: /Alpha 2/ });
    expect(within(row).getByText("2026-09-10")).toBeInTheDocument();
    expect(within(row).getByText("18")).toBeInTheDocument();
    expect(within(row).getByText("Drift")).toBeInTheDocument();
    expect(within(row).getByText("Frozen at completion: delivered 16 MD, 6 items")).toBeInTheDocument();
    expect(screen.getAllByText("Drift")).toHaveLength(1);

    // Groups: teams link in, the unassigned bucket does not.
    expect(screen.getByRole("link", { name: "Show Alpha" })).toBeInTheDocument();
    expect(screen.getByText("Unassigned")).toBeInTheDocument();
    expect(calls(mockFetch)).toEqual(["/api/v1/reports/throughput?"]);
  });

  test("the bucket control writes the URL, refetches and relabels the buckets", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("bar-chart");
    expect(screen.getByRole("radio", { name: "Week" })).toBeChecked();

    await user.click(screen.getByText("Month"));

    await waitFor(() => expect(calls(mockFetch)).toContain("/api/v1/reports/throughput?bucket=MONTH"));
    await waitFor(() => expect(screen.getByTestId("bar-chart").getAttribute("data-labels")).toBe("2026-08|2026-09"));
    expect(screen.getByRole("radio", { name: "Month" })).toBeChecked();
  });

  test("a bucket in the link is honoured and part of the request", async () => {
    serve(mockFetch);
    renderPage("/reports/throughput?bucket=MONTH&lastSprints=3");
    await waitFor(() => expect(screen.getByTestId("bar-chart").getAttribute("data-labels")).toBe("2026-08|2026-09"));
    expect(calls(mockFetch)).toEqual(["/api/v1/reports/throughput?lastSprints=3&bucket=MONTH"]);
  });

  test("offers the domain view, domain, activity type and work category controls, and they filter the request", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("bar-chart");
    expect(screen.getByRole("radio", { name: "Delivered in" })).toBeChecked();
    for (const name of ["Domain", "Activity type", "Work category"]) {
      expect(screen.getByRole("combobox", { name })).toBeInTheDocument();
    }

    await user.click(screen.getByText("Earned in"));
    await waitFor(() => expect(calls(mockFetch)).toContain("/api/v1/reports/throughput?domainView=EPIC"));
    expect(screen.getByText("Domain view: earned in — a task counts in its epic's domain.")).toBeInTheDocument();

    await user.click(screen.getByRole("combobox", { name: "Activity type" }));
    await user.click(await screen.findByRole("option", { name: "Bug" }));
    await waitFor(() => expect(calls(mockFetch)).toContain("/api/v1/reports/throughput?domainView=EPIC&activityType=Bug"));
  });

  test("a period with nothing delivered shows the empty state for the chart; an empty sprint view is its own state", async () => {
    serve(mockFetch, () => jsonResponse(200, THROUGHPUT_NOTHING));
    renderPage();
    expect(await screen.findAllByText("No data in this period")).toHaveLength(2);
    expect(screen.queryByTestId("bar-chart")).not.toBeInTheDocument();
  });

  test("a sprint-less period still charts the period view", async () => {
    serve(mockFetch, () => jsonResponse(200, { ...THROUGHPUT_UNIT, bySprint: [] }));
    renderPage();
    expect(await screen.findByTestId("bar-chart")).toBeInTheDocument();
    expect(screen.getAllByText("No data in this period")).toHaveLength(1);
  });

  test("a report failure is ONE inline alert (the sprint card is not repeated); the filter bar stays usable", async () => {
    serve(mockFetch, () => jsonResponse(500, { title: "boom", status: 500 }));
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (500)");
    expect(screen.getAllByRole("alert")).toHaveLength(1);
    expect(screen.queryByRole("heading", { name: "Delivered per sprint" })).not.toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Period" })).toBeInTheDocument();
  });

  test("the chart's numbers are also a table — one row per bucket, the bucket's first day, MD and items", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    const table = await screen.findByRole("table", { name: "Delivered per period, as a table" });
    expect(within(table).getByRole("columnheader", { name: "Week starting" })).toBeInTheDocument();
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows.map((r) => r.textContent)).toEqual(["2026-09-0712.55", "2026-09-1400", "2026-09-2115.56"]);

    await user.click(screen.getByText("Month"));
    await waitFor(() => expect(within(screen.getByRole("table", { name: "Delivered per period, as a table" })).getByRole("columnheader", { name: "Month" })).toBeInTheDocument());
    expect(within(screen.getByRole("table", { name: "Delivered per period, as a table" })).getByText("2026-08")).toBeInTheDocument();
  });

  test("says the sprint view ignores the domain, activity type and work category filters", async () => {
    serve(mockFetch);
    renderPage();
    expect(await screen.findByText(/It ignores the domain, activity type and work category filters\./)).toBeInTheDocument();
  });

  test("changing the bucket keeps the previous chart on screen, busy, until the new report lands", async () => {
    let release: ((response: Response) => void) | undefined;
    const deferred = new Promise<Response>((resolve) => {
      release = resolve;
    });
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
      if (url.includes("bucket=MONTH")) return deferred;
      return Promise.resolve(jsonResponse(200, THROUGHPUT_UNIT));
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findByTestId("bar-chart");
    const busy = () => screen.getByTestId("bar-chart").closest("[aria-busy]");
    expect(busy()).toHaveAttribute("aria-busy", "false");

    await user.click(screen.getByText("Month"));
    await waitFor(() => expect(calls(mockFetch)).toContain("/api/v1/reports/throughput?bucket=MONTH"));
    expect(busy()).toHaveAttribute("aria-busy", "true");
    expect(screen.getByTestId("bar-chart").getAttribute("data-labels")).toBe("2026-09-07|2026-09-14|2026-09-21");

    release?.(jsonResponse(200, THROUGHPUT_MONTHS));
    await waitFor(() => expect(screen.getByTestId("bar-chart").getAttribute("data-labels")).toBe("2026-08|2026-09"));
    expect(busy()).toHaveAttribute("aria-busy", "false");
  });
});
