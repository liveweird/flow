import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportCycleTime from "./ReportCycleTime";
import { jsonResponse } from "../test/http";
import {
  CYCLE_TIME,
  CYCLE_TIME_ALL_HIDDEN,
  CYCLE_TIME_EMPTY,
  CYCLE_TIME_MONTHS,
  CYCLE_TIME_NO_EPICS,
  CYCLE_TIME_TAIL,
  CYCLE_TIME_TEAM,
  CYCLE_TIME_USER,
  CYCLE_TIME_USERS,
  FILTERS,
} from "../test/reportFixtures";
import { openReportFilters } from "../test/reportFilters";
import { renderWithProviders, screen, waitFor, within } from "../test/render";

// recharts renders nothing under happy-dom (no layout): histograms and the trend are probes.
vi.mock("@mantine/charts", () => ({
  BarChart: (props: {
    data: ({ label: string; count?: number; tail?: number } & Record<string, unknown>)[];
    series: { name: string; label: string }[];
    type?: string;
    xAxisLabel?: string;
  }) => (
    <div
      data-testid="bar-chart"
      data-counts={props.data.map((r) => r.count ?? `tail ${r.tail}`).join(",")}
      data-x-label={props.xAxisLabel}
      data-type={props.type ?? ""}
      data-series={props.series.map((s) => s.name).join(",")}
      data-series-labels={props.series.map((s) => s.label).join("|")}
      data-stacks={JSON.stringify(props.data.map((r) => props.series.map((s) => r[s.name])))}
    />
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
// The page is read per team: a bare link opens on the first team by name (Alpha, id 1), so every request carries it.
const TEAM = `${URL_PREFIX}teamId=1`;

/** The fixture for the request: several `accountId`s = the stacked split, one = that person, none = the team (or month) read. */
function reportFor(url: string) {
  const params = new URL(url, "http://x").searchParams;
  const accounts = params.getAll("accountId").length;
  if (accounts > 1) return CYCLE_TIME_USERS;
  if (accounts === 1) return CYCLE_TIME_USER;
  return params.get("bucket") === "MONTH" ? CYCLE_TIME_MONTHS : CYCLE_TIME;
}

function serve(mockFetch: FetchMock, report?: (url: string) => Response, filters?: () => Response) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(filters?.() ?? jsonResponse(200, FILTERS));
    if (url.startsWith(URL_PREFIX)) return Promise.resolve(report?.(url) ?? jsonResponse(200, reportFor(url)));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <div data-testid="where">{useLocation().search}</div>;
}

function renderPage(route = "/reports/cycle-time") {
  return renderWithProviders(
    <Routes>
      <Route
        path="/reports/cycle-time"
        element={
          <>
            <ReportCycleTime />
            <Where />
          </>
        }
      />
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

    // The two task views come first; the epics block below carries two more histograms of its own.
    await waitFor(() => expect(screen.getAllByTestId("bar-chart")).toHaveLength(4));
    const histograms = screen.getAllByTestId("bar-chart").map((c) => [c.getAttribute("data-counts"), c.getAttribute("data-x-label")]);
    expect(histograms.slice(0, 2)).toEqual([["3,6,5,3,1", "working days"], ["2,5,6,3,2", "elapsed days"]]);
    const strips = screen.getAllByRole("group", { name: "Percentiles" });
    expect(within(strips[0]).getByText("3.5")).toBeInTheDocument();
    expect(within(strips[1]).getByText("5")).toBeInTheDocument();
    // Cycle time keeps the full strip: the upper percentiles are what a long-tail read needs (only the accuracy pages hide them).
    for (const strip of strips) {
      expect(within(strip).getByText("p90")).toBeInTheDocument();
      expect(within(strip).getByText("p95")).toBeInTheDocument();
    }

    // Two accountings, each n + reasons = population.
    expect(screen.getAllByText("18 + 2 = 20")).toHaveLength(2);
    expect(screen.getAllByText("Of 20 finished in this period:")).toHaveLength(2);
    expect(screen.getAllByText(/Never started \(created straight into done\), so no cycle/)).toHaveLength(2);
    expect(calls(mockFetch)).toEqual([TEAM]);
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
    await openReportFilters();
    expect(screen.getByRole("radio", { name: "Week" })).toBeChecked();
    await user.click(screen.getByText("Month"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${TEAM}&bucket=MONTH`));
    await waitFor(() => expect(screen.getByTestId("line-chart").getAttribute("data-labels")).toBe("2026-08|2026-09"));
    expect(within(screen.getByRole("table", { name: "Trend, as a table" })).getByRole("columnheader", { name: "Month" })).toBeInTheDocument();
  });

  test("the long tail: the default 5 is in neither the URL nor the request; a pick writes both, and 5 again removes it", async () => {
    serve(mockFetch, (url) =>
      jsonResponse(200, new URL(url, "http://x").searchParams.get("tailPercent") === "10" ? CYCLE_TIME : CYCLE_TIME_TAIL),
    );
    const user = userEvent.setup();
    renderPage();
    // The default answer ends in the open bucket: the chart's gray series, the table's "≥ 6" row marked in words.
    await waitFor(() => expect(screen.getAllByTestId("bar-chart").length).toBeGreaterThan(0));
    expect(screen.getAllByTestId("bar-chart")[0].getAttribute("data-counts")).toBe("3,6,5,2,tail 2");
    const table = screen.getByRole("table", { name: "Histogram, as a table — Working days" });
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows[rows.length - 1].firstElementChild).toHaveTextContent("≥ 8 (long tail)");
    const control = screen.getByRole("combobox", { name: "Long tail" });
    expect(control).toHaveValue("5%");
    expect(calls(mockFetch)).toEqual([TEAM]);
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent(/^\?teamId=1$/));

    await user.click(control);
    await user.click(screen.getByRole("option", { name: "10%" }));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${TEAM}&tailPercent=10`));
    expect(screen.getByTestId("where")).toHaveTextContent("?teamId=1&tailPercent=10");
    await waitFor(() => expect(screen.getAllByTestId("bar-chart")[0].getAttribute("data-counts")).toBe("3,6,5,3,1"));
    expect(screen.getByRole("combobox", { name: "Long tail" })).toHaveValue("10%");

    await user.click(screen.getByRole("combobox", { name: "Long tail" }));
    await user.click(screen.getByRole("option", { name: "5%" }));
    await waitFor(() => expect(screen.getByTestId("where")).toHaveTextContent(/^\?teamId=1$/));
    expect(calls(mockFetch)[calls(mockFetch).length - 1]).toBe(TEAM);
  });

  test("the long tail is a deep link: tailPercent=0 is sent as written, an out-of-range value is dropped", async () => {
    serve(mockFetch);
    const { unmount } = renderPage("/reports/cycle-time?tailPercent=0");
    expect(await screen.findByRole("combobox", { name: "Long tail" })).toHaveValue("0%");
    expect(calls(mockFetch)).toEqual([`${TEAM}&tailPercent=0`]);
    unmount();
    mockFetch.mockClear();
    renderPage("/reports/cycle-time?tailPercent=99");
    expect(await screen.findByRole("combobox", { name: "Long tail" })).toHaveValue("5%");
    expect(calls(mockFetch)).toEqual([TEAM]);
  });

  test("the long tail is no filter: opening the filter bar shows no badge for it", async () => {
    serve(mockFetch);
    renderPage("/reports/cycle-time?tailPercent=20");
    expect(await screen.findByRole("button", { name: /^Filters$/ })).toBeInTheDocument();
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
    // One caption under each groups table (the tasks' and the epics').
    expect(screen.getAllByText("Groups below the minimum sample show counts only.")).toHaveLength(2);
  });

  test("epics: their own two views with their own accounting, then the owner teams that link in", async () => {
    serve(mockFetch);
    renderPage();
    const card = (await screen.findByRole("heading", { level: 3, name: "Epics" })).closest("div[class*=Paper]") as HTMLElement;
    expect(within(card).getByText(/Epics finished in the period, by the team that owns their domain/)).toBeInTheDocument();
    // Working days first, elapsed days beside it, each a distribution of its own epics (never the tasks').
    const working = within(card).getByRole("heading", { name: "Epics: working days" });
    const elapsed = within(card).getByRole("heading", { name: "Epics: elapsed days" });
    expect(working.compareDocumentPosition(elapsed) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    // The chart chunk is lazy: await both histograms before reading them (a synchronous getAll flaked under --sequence.shuffle).
    await waitFor(() => expect(within(card).getAllByTestId("bar-chart")).toHaveLength(2));
    const charts = within(card).getAllByTestId("bar-chart").map((c) => [c.getAttribute("data-counts"), c.getAttribute("data-x-label")]);
    expect(charts).toEqual([["1,2,1,1,0", "working days"], ["1,1,2,1,0", "elapsed days"]]);
    const strips = within(card).getAllByRole("group", { name: "Percentiles" });
    expect(within(strips[0]).getByText("9")).toBeInTheDocument();
    expect(within(strips[1]).getByText("13")).toBeInTheDocument();
    // Each accounting reconciles: 5 measured + 1 never started = 6 epics.
    expect(within(card).getAllByText("5 + 1 = 6")).toHaveLength(2);
    expect(within(card).getAllByText("Of 6 finished in this period:")).toHaveLength(2);
    expect(within(card).getAllByText(/Epics never started \(finished without a start\), so no cycle/)).toHaveLength(2);

    const table = screen.getByRole("table", { name: "Epics by owner team" });
    expect(within(table).getByRole("columnheader", { name: "Owner team" })).toBeInTheDocument();
    const rows = within(table).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
    expect(rows).toEqual([
      ["Alpha", "3", "3", "8", "3", "12"],
      ["No owner team", "3", "2", "—", "2", "—"],
    ]);
    // Alpha narrows the report to its team; the epics without an owner are shown, never linked.
    expect(within(table).getAllByRole("link")).toHaveLength(1);
    expect(within(table).getByRole("link", { name: "Show Alpha" })).toHaveAttribute("href", expect.stringContaining("teamId=1"));
  });

  test("epics: a team's own epics with no owner table; no finished epic is said in its own words", async () => {
    serve(mockFetch, () => jsonResponse(200, CYCLE_TIME_TEAM));
    const { unmount } = renderPage("/reports/cycle-time?teamId=1");
    expect(await screen.findByRole("heading", { name: "Epics: working days" })).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: "Epics by owner team" })).not.toBeInTheDocument();
    unmount();

    serve(mockFetch, () => jsonResponse(200, CYCLE_TIME_NO_EPICS));
    renderPage();
    expect(await screen.findByText("No epics finished in this period.")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Epics: working days" })).not.toBeInTheDocument();
    expect(screen.queryByRole("table", { name: "Epics by owner team" })).not.toBeInTheDocument();
    // The tasks are untouched: their own distributions still render.
    expect(screen.getByRole("heading", { name: "Working days" })).toBeInTheDocument();
  });

  test("epics at USER level: one note, never a block of zeros", async () => {
    serve(mockFetch, () => jsonResponse(200, CYCLE_TIME_USER));
    renderPage("/reports/cycle-time?teamId=1&accountId=acc-ann");
    expect(await screen.findByRole("heading", { level: 3, name: "Epics" })).toBeInTheDocument();
    expect(screen.getByRole("note")).toHaveTextContent("Epics aren't attributed to individual people");
    expect(screen.queryByRole("heading", { name: "Epics: working days" })).not.toBeInTheDocument();
    expect(screen.queryByText("No epics finished in this period.")).not.toBeInTheDocument();
  });

  test("offers the domain view, domain, activity type, work category and the bucket", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByTestId("line-chart");
    await openReportFilters();
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
    expect(screen.getAllByRole("tab").map((tab) => tab.textContent)).toEqual(["Commitments", "Throughput", "Sprint consistency", "Cycle time"]);
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
    await openReportFilters();
    await user.click(screen.getByText("Month"));
    await waitFor(() => expect(calls(mockFetch)).toContain(`${TEAM}&bucket=MONTH`));
    // Old data, old (weekly) labels — never relabelled with the new resolution while in flight.
    expect(screen.getByTestId("line-chart").getAttribute("data-labels")).toBe("2026-09-07|2026-09-14|2026-09-21|2026-09-28");
    expect(screen.getByTestId("line-chart").closest("[aria-busy]")).toHaveAttribute("aria-busy", "true");
    release?.(jsonResponse(200, CYCLE_TIME_MONTHS));
    await waitFor(() => expect(screen.getByTestId("line-chart").getAttribute("data-labels")).toBe("2026-08|2026-09"));
  });

  describe("a team is required", () => {
    test("the team control has no 'All teams' option, no clear button, and its picked option cannot be clicked off", async () => {
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
      await user.click(screen.getByRole("option", { name: "Alpha" }));
      expect((screen.getByRole("combobox", { name: "Team" }) as HTMLInputElement).value).toBe("Alpha");
      expect(calls(mockFetch)).toEqual([TEAM]);
    });

    test("with no team at all the page says so and requests nothing", async () => {
      serve(mockFetch, undefined, () => jsonResponse(200, { ...FILTERS, teams: [] }));
      renderPage();
      expect(await screen.findByText(/there is no team yet/)).toBeInTheDocument();
      expect(calls(mockFetch)).toEqual([]);
      expect(screen.queryByTestId("bar-chart")).not.toBeInTheDocument();
    });
  });

  describe("several people", () => {
    test("the member control picks several people: repeated accountId in the request, then stacked segments per person", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/reports/cycle-time?teamId=1");
      await screen.findAllByTestId("bar-chart");
      await openReportFilters(user);

      await user.click(screen.getByRole("combobox", { name: "Member" }));
      await user.click(await screen.findByRole("option", { name: "Ann Author" }));
      await waitFor(() => expect(calls(mockFetch)).toContain(`${TEAM}&accountId=acc-ann`));
      await user.click(await screen.findByRole("option", { name: "Bob Builder" }));
      await waitFor(() => expect(calls(mockFetch)).toContain(`${TEAM}&accountId=acc-ann&accountId=acc-bob`));

      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(2));
      const charts = screen.getAllByTestId("bar-chart");
      expect(charts.map((c) => c.getAttribute("data-type"))).toEqual(["stacked", "stacked"]);
      expect(charts.map((c) => c.getAttribute("data-series"))).toEqual(["p0,p1", "p0,p1"]);
      expect(charts[0].getAttribute("data-series-labels")).toBe("1. Ann Author|2. Bob Builder");
      // One segment per person in every bucket: Ann then Bob (working days, then elapsed days).
      expect(JSON.parse(charts[0].getAttribute("data-stacks") ?? "[]")).toEqual([[2, 1], [4, 2], [3, 2], [2, 1], [1, 0]]);
      expect(JSON.parse(charts[1].getAttribute("data-stacks") ?? "[]")).toEqual([[1, 1], [4, 1], [4, 2], [2, 1], [1, 1]]);
      const legends = screen.getAllByRole("list", { name: "Chart legend" });
      expect(legends).toHaveLength(2);
      expect(legends[0]).toHaveTextContent("1. Ann Author2. Bob Builder");
      // The folded toggle counts the member dimension once, however many people are picked (the required team counts for nothing).
      expect(screen.getByRole("button", { name: /^Filters/ })).toHaveTextContent("1");
    });

    test("every chart number is in a table: a column per person beside the total, and the totals per person", async () => {
      serve(mockFetch);
      renderPage("/reports/cycle-time?teamId=1&accountId=acc-ann&accountId=acc-bob");
      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(2));

      const histogram = screen.getByRole("table", { name: "Histogram, as a table — Working days" });
      expect(within(histogram).getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
        "Range",
        "1. Ann Author",
        "2. Bob Builder",
        "Items",
        "Item list",
      ]);
      expect(within(histogram).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent))[0]).toEqual([
        "0 – 2",
        "2",
        "1",
        "3",
        "", // the Items button's cell
      ]);

      const totals = screen.getByRole("table", { name: "Totals per person" });
      const rows = within(totals).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
      expect(rows).toEqual([
        ["1. Ann Author", "13", "12", "3.4", "12", "5.2"],
        ["2. Bob Builder", "7", "6", "3.6", "6", "5.4"],
      ]);
      expect(screen.queryByRole("heading", { name: "By member" })).not.toBeInTheDocument();
    });

    test("one person is the same view with a single stack, a '1.' column and their totals row", async () => {
      serve(mockFetch);
      renderPage("/reports/cycle-time?teamId=1&accountId=acc-ann");
      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(2));
      expect(screen.getAllByTestId("bar-chart").map((c) => c.getAttribute("data-series"))).toEqual(["p0", "p0"]);
      expect(screen.queryByRole("list", { name: "Chart legend" })).not.toBeInTheDocument();
      const histogram = screen.getByRole("table", { name: "Histogram, as a table — Working days" });
      expect(within(histogram).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Range", "1. Ann Author", "Items", "Item list"]);
      expect(within(screen.getByRole("table", { name: "Totals per person" })).getAllByRole("row")).toHaveLength(2);
      expect(calls(mockFetch)).toEqual([`${TEAM}&accountId=acc-ann`]);
    });

    test("the trend and the epics stay the whole selection: one line over everyone picked, the epics note at USER level", async () => {
      serve(mockFetch);
      renderPage("/reports/cycle-time?teamId=1&accountId=acc-ann&accountId=acc-bob");
      const chart = await screen.findByTestId("line-chart");
      expect(chart.getAttribute("data-labels")).toBe("2026-09-07|2026-09-14|2026-09-21|2026-09-28");
      expect(chart.getAttribute("data-p50")).toBe("3,null,2.5,null");
      const rows = within(screen.getByRole("table", { name: "Trend, as a table" })).getAllByRole("row").slice(1);
      expect(rows.map((r) => within(r).getAllByRole("cell")[1].textContent)).toEqual(["8", "3", "7", "0"]);
      expect(screen.getByRole("note")).toHaveTextContent("Epics aren't attributed to individual people");
    });

    test("a link with repeated accountId selects those people and requests them in that order", async () => {
      serve(mockFetch);
      renderPage("/reports/cycle-time?teamId=1&accountId=acc-bob&accountId=acc-ann&accountId=acc-bob");
      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(2));
      expect(calls(mockFetch)).toEqual([`${TEAM}&accountId=acc-bob&accountId=acc-ann`]);
      await openReportFilters();
      const pills = screen.getByRole("group", { name: "Report filters" });
      expect(within(pills).getByText("Bob Builder")).toBeInTheDocument();
      expect(within(pills).getByText("Ann Author")).toBeInTheDocument();
    });

    test("a hidden distribution shows no stack; the other still does, and the totals keep the bare names when no chart numbers them", async () => {
      serve(mockFetch, () =>
        jsonResponse(200, {
          ...CYCLE_TIME_USERS,
          workingDays: { n: 3, hidden: true, histogram: [] },
          elapsedDays: { ...CYCLE_TIME_USERS.elapsedDays, byUser: undefined },
        }),
      );
      renderPage("/reports/cycle-time?teamId=1&accountId=acc-ann&accountId=acc-bob");
      await screen.findAllByRole("note");
      const totals = screen.getByRole("table", { name: "Totals per person" });
      expect(within(totals).getAllByRole("row")[1]).toHaveTextContent(/^Ann Author/);
    });

    test("changing the team drops the picked people", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage("/reports/cycle-time?teamId=1&accountId=acc-ann&accountId=acc-bob");
      await waitFor(() => expect(screen.getAllByRole("group", { name: /stacked by person/ })).toHaveLength(2));
      await openReportFilters(user);
      await user.click(screen.getByRole("combobox", { name: "Team" }));
      await user.click(await screen.findByRole("option", { name: "Beta" }));
      await waitFor(() => expect(calls(mockFetch)).toContain(`${URL_PREFIX}teamId=2`));
      expect(screen.getByTestId("where").textContent).not.toContain("accountId");
    });
  });
});
