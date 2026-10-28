import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import ReportPersonTrend from "./ReportPersonTrend";
import { PERSON_TREND, PERSON_TREND_EMPTY, PERSON_TREND_TEAM } from "../test/personTrendFixtures";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { todayIsoDate } from "../utils/isoDate";
import { presetRange } from "../utils/reportFilter";

// recharts renders nothing under happy-dom: each chart is a probe carrying the props the page decides (the rows, the series).
vi.mock("@mantine/charts", () => ({
  LineChart: (props: { series: { name: string; label?: string }[]; data: { label: string }[] }) => (
    <div data-testid="line-chart" data-series={props.series.map((series) => series.label ?? series.name).join("|")} data-rows={JSON.stringify(props.data)} />
  ),
  AreaChart: (props: { series: { label?: string }[]; type: string }) => (
    <div data-testid="area-chart" data-type={props.type} data-series={props.series.map((series) => series.label).join("|")} />
  ),
  ChartTooltip: () => null,
}));

type FetchMock = ReturnType<typeof vi.fn>;
const REPORT = "/api/v1/reports/person-trend?";
const PEOPLE = [
  { accountId: "acc-ann", displayName: "Ann Author" },
  { accountId: "acc-bob", displayName: "Bob Builder" },
];
const YEAR = presetRange("last365", todayIsoDate(FILTERS.timeZone));
const YEAR_QUERY = `from=${YEAR.from}&to=${YEAR.to}`;
/** The canonical URL order: the period, then the person. */
const ANN = `/reports/person-trend?${YEAR_QUERY}&accountId=acc-ann`;

function serve(mockFetch: FetchMock, report: unknown = PERSON_TREND, status = 200) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(REPORT)) return Promise.resolve(jsonResponse(status, report));
    if (url.startsWith("/api/v1/jira-users?")) return Promise.resolve(jsonResponse(200, { items: PEOPLE, page: 1, pageSize: 20, total: PEOPLE.length }));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function Where() {
  return <div data-testid="where">{useLocation().pathname + useLocation().search}</div>;
}

function renderPage(route = "/reports/person-trend") {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/reports/person-trend" element={<ReportPersonTrend />} />
      </Routes>
      <Where />
    </>,
    { route },
  );
}

const where = () => screen.getByTestId("where").textContent;
const reportCalls = (mockFetch: FetchMock) => mockFetch.mock.calls.map((c) => c[0] as string).filter((url) => url.startsWith(REPORT));
const card = (title: string) => screen.getByRole("heading", { level: 3, name: title }).closest(".mantine-Paper-root") as HTMLElement;
const lineCharts = () => screen.getAllByTestId("line-chart");
const cells = (row: HTMLElement) => Array.from(row.querySelectorAll("th, td")).map((cell) => cell.textContent);

describe("ReportPersonTrend page", () => {
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

  describe("before a person is picked", () => {
    test("a bare visit is the picker and an empty state, on a year written into the URL, and nothing is requested", async () => {
      serve(mockFetch);
      renderPage();
      expect(await screen.findByText("Pick a person to see how their figures changed.")).toBeInTheDocument();
      expect(screen.getByRole("combobox", { name: "Person" })).toBeEnabled();
      expect(headingOutline()).toEqual([[2, "Person trend"]]);
      await waitFor(() => expect(where()).toBe(`/reports/person-trend?${YEAR_QUERY}`));
      expect(reportCalls(mockFetch)).toEqual([]);
    });

    test("the year is the page's own default: the filter badge does not count it as a narrowing", async () => {
      serve(mockFetch);
      renderPage();
      await screen.findByText("Pick a person to see how their figures changed.");
      await waitFor(() => expect(where()).toBe(`/reports/person-trend?${YEAR_QUERY}`));
      expect(screen.getByRole("button", { name: /Filters/ })).not.toHaveTextContent(/\d/);
    });

    test("picking a person writes their accountId to the URL and requests the report once", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage();
      await screen.findByText("Pick a person to see how their figures changed.");
      await user.click(screen.getByRole("combobox", { name: "Person" }));
      await user.click(await screen.findByRole("option", { name: "Ann Author" }));
      await waitFor(() => expect(where()).toBe(ANN));
      await waitFor(() => expect(reportCalls(mockFetch)).toEqual([`${REPORT}${YEAR_QUERY}&accountId=acc-ann`]));
      expect(await screen.findByRole("heading", { level: 3, name: "Cycle time, working days" })).toBeInTheDocument();
    });
  });

  describe("the report", () => {
    test("a link's team, extra people, sprint period and another report's params are dropped and the URL says so; ONE person is requested", async () => {
      serve(mockFetch);
      renderPage(`/reports/person-trend?teamId=1&accountId=acc-ann&accountId=acc-bob&lastSprints=3&tailPercent=10&bucket=MONTH&domain=FLO`);
      await screen.findByRole("heading", { level: 3, name: "Cycle time, working days" });
      await waitFor(() => expect(where()).toBe(`/reports/person-trend?${YEAR_QUERY}&accountId=acc-ann&domain=FLO`));
      expect(reportCalls(mockFetch)).toEqual([`${REPORT}${YEAR_QUERY}&accountId=acc-ann&domain=FLO`]);
    });

    test("one chart per figure in the server's order — the cycle percentiles on ONE — then the work mix", async () => {
      serve(mockFetch);
      renderPage(ANN);
      await screen.findByRole("heading", { level: 3, name: "Cycle time, working days" });
      expect(headingOutline().filter(([level]) => level === 3).map(([, text]) => text)).toEqual([
        "Cycle time, working days",
        "Flow efficiency — median",
        "Delivered (items)",
        "Delivered (MD)",
        "Estimate accuracy at start — within ±25%",
        "Average WIP per working day",
        "Median age of those items (working days)",
        "Finished tasks that were blocked",
        "Finished tasks with time logged",
        "Worklogs logged over a day late",
        "Finished tasks without an estimate",
        "Finished tasks that were reopened",
        "Task size at start (MD) — median",
        "Work mix",
      ]);
      expect(headingOutline().filter(([level]) => level === 4).map(([, text]) => text)).toEqual(["Work mix by activity type", "Work mix by work category"]);
      await waitFor(() => expect(lineCharts()).toHaveLength(13));
      expect(lineCharts()[0]).toHaveAttribute("data-series", "Median (p50)|p90");
      expect(lineCharts()[1]).toHaveAttribute("data-series", "Flow efficiency — median");
      expect(await screen.findAllByTestId("area-chart")).toHaveLength(2);
      expect(screen.getAllByTestId("area-chart")[0]).toHaveAttribute("data-type", "stacked");
      expect(screen.getAllByTestId("area-chart")[0]).toHaveAttribute("data-series", "Story|Bug");
      // The UNCATEGORIZED work category reads like the bars' "(uncategorized)".
      expect(screen.getAllByTestId("area-chart")[1]).toHaveAttribute("data-series", "Feature|(uncategorized)");
    });

    test("a window with nothing derived is a gap in the chart whatever the server's figure, a partial one keeps its value", async () => {
      serve(mockFetch);
      renderPage(ANN);
      await waitFor(() => expect(lineCharts()).toHaveLength(13));
      // Delivered (items) is the 3rd chart; its NONE window carries the server's 0.
      const rows = JSON.parse(lineCharts()[2].getAttribute("data-rows")!) as Array<{ label: string; value: number | null }>;
      expect(rows.map((row) => row.value)).toEqual([null, 4, 6, 7, 9]);
      const cycle = JSON.parse(lineCharts()[0].getAttribute("data-rows")!) as Array<{ p50: number | null; p90: number | null; partial: boolean }>;
      expect(cycle.map((row) => [row.p50, row.p90, row.partial])).toEqual([
        [null, null, false],
        [6.5, 11, true],
        [5.5, 10, false],
        [5, 9, false],
        [4, null, false],
      ]);
    });

    test("a person with a team gets the team median beside every line (the cycle chart's median too), the hint says what it is, and the numbers table has the Team and team-median columns", async () => {
      serve(mockFetch, PERSON_TREND_TEAM);
      const user = userEvent.setup();
      renderPage(ANN);
      await waitFor(() => expect(lineCharts()).toHaveLength(13));
      expect(screen.getByText(/The dotted gray line is the team median: the median of the person's team-mates who were active in that window/)).toBeInTheDocument();
      expect(lineCharts()[0]).toHaveAttribute("data-series", "Median (p50)|p90|Team median (p50)");
      expect(lineCharts()[2]).toHaveAttribute("data-series", "Delivered (items)|Team median");
      const rows = JSON.parse(lineCharts()[2].getAttribute("data-rows")!) as Array<{ reference: number | null }>;
      expect(rows.map((row) => row.reference)).toEqual([null, 5, 7, null, 10]);
      const items = card("Delivered (items)");
      await user.click(within(items).getByRole("button", { name: "Show the numbers" }));
      const table = within(items).getByRole("table", { name: "Delivered (items): figures by window" });
      expect(within(table).getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["Window ending", "Team", "Delivered (items)", "Team median (peers)", "n", "Window"]);
    });

    test("a team that never had enough active peers draws no line and the hint stays away (the tooltip and the table still say why)", async () => {
      const noMedian = {
        ...PERSON_TREND_TEAM,
        rows: PERSON_TREND_TEAM.rows.map((row) => ({ ...row, points: row.points.map((point) => ({ ...point, reference: { peers: 1 } })) })),
      };
      serve(mockFetch, noMedian);
      renderPage(ANN);
      await waitFor(() => expect(lineCharts()).toHaveLength(13));
      expect(screen.queryByText(/The dotted gray line is the team median/)).not.toBeInTheDocument();
      expect(lineCharts()[2]).toHaveAttribute("data-series", "Delivered (items)");
    });

    test("a person with no team at any window has no reference line, no hint and no extra columns", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage(ANN);
      await waitFor(() => expect(lineCharts()).toHaveLength(13));
      expect(screen.queryByText(/team median/i)).not.toBeInTheDocument();
      expect(lineCharts()[2]).toHaveAttribute("data-series", "Delivered (items)");
      const items = card("Delivered (items)");
      await user.click(within(items).getByRole("button", { name: "Show the numbers" }));
      expect(within(within(items).getByRole("table")).getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["Window ending", "Delivered (items)", "n", "Window"]);
    });

    test("each chart carries its direction and the cue: an arrow, the verdict as a word, the two window ends and the change", async () => {
      serve(mockFetch);
      renderPage(ANN);
      await screen.findByRole("heading", { level: 3, name: "Cycle time, working days" });
      const cycle = card("Cycle time, working days");
      expect(within(cycle).getByText("Lower is better. The mark compares the first and last full window in the range.")).toBeInTheDocument();
      expect(within(cycle).getByTestId("cue-cycleWorkingDaysP50")).toHaveTextContent(
        "Median (p50): ↓ Better · first window ending 2026-07-28, last ending 2026-08-25 · change 1.5 days",
      );
      expect(within(cycle).getByTestId("cue-cycleWorkingDaysP90")).toHaveTextContent(
        "p90: ↓ Better · first window ending 2026-07-28, last ending 2026-08-11 · change 1 day",
      );
      expect(within(card("Finished tasks that were reopened")).getByTestId("cue-reopenedShare")).toHaveTextContent(
        "↑ Worse · first window ending 2026-07-28, last ending 2026-08-25 · change 7 pp",
      );
      expect(within(card("Flow efficiency — median")).getByTestId("cue-flowEfficiencyP50")).toHaveTextContent("→ No change");
      // A change far beyond the tolerance with no verdict is NOT "No change": a side is under the minimum sample.
      const blocked = within(card("Finished tasks that were blocked")).getByTestId("cue-blockedItemsShare");
      expect(blocked).toHaveTextContent("↓ Too few items to call · first window ending 2026-07-28, last ending 2026-08-25 · change 5 pp");
      expect(blocked).not.toHaveTextContent("No change");
      expect(within(card("Worklogs logged over a day late")).getByTestId("cue-lateLoggingShare")).toHaveTextContent("→ No change");
    });

    test("a row with fewer than two full windows with a figure has no mark, and says why", async () => {
      serve(mockFetch);
      renderPage(ANN);
      await screen.findByRole("heading", { level: 3, name: "Cycle time, working days" });
      expect(within(card("Task size at start (MD) — median")).getByTestId("cue-taskSizeP50")).toHaveTextContent(
        "No mark: fewer than two full windows with a figure in this range.",
      );
    });

    test("the numbers behind a chart sit behind a button: a row per window with the figure, the sample and the window's coverage in words", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage(ANN);
      await screen.findByRole("heading", { level: 3, name: "Cycle time, working days" });
      const cycle = card("Cycle time, working days");
      expect(within(cycle).queryByRole("table")).not.toBeInTheDocument();
      await user.click(within(cycle).getByRole("button", { name: "Show the numbers" }));
      const table = within(cycle).getByRole("table", { name: "Cycle time, working days: figures by window" });
      expect(within(table).getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
        "Window ending",
        "Cycle time, working days — median",
        "Cycle time, working days — p90",
        "n",
        "Window",
      ]);
      expect(within(table).getAllByRole("row").slice(1).map(cells)).toEqual([
        ["2026-06-30", "—", "—", "0", "no data derived"],
        ["2026-07-14", "6.5", "11", "8", "partial"],
        ["2026-07-28", "5.5", "10", "9", "complete"],
        ["2026-08-11", "5", "9", "9", "complete"],
        // The withheld p90 says so in its own cell.
        ["2026-08-25", "4", "— (below the minimum of 5)", "10", "complete"],
      ]);
      await user.click(within(cycle).getByRole("button", { name: "Hide the numbers" }));
      expect(within(cycle).queryByRole("table")).not.toBeInTheDocument();
    });

    test("the work mix table has the share and man-days of every key, a dash where nothing was delivered", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderPage(ANN);
      await screen.findByRole("heading", { level: 3, name: "Work mix" });
      const mix = card("Work mix");
      await user.click(within(mix).getAllByRole("button", { name: "Show the numbers" })[0]);
      const rows = within(within(mix).getByRole("table", { name: "Work mix by activity type: figures by window" })).getAllByRole("row").map(cells);
      expect(rows[0]).toEqual(["Window ending", "Story", "Bug", "Window"]);
      expect(rows[1]).toEqual(["2026-06-30", "—", "—", "no data derived"]);
      expect(rows[4]).toEqual(["2026-08-11", "70% · 7 MD", "30% · 3 MD", "complete"]);
    });

    test("the report's note, its derivation line and how the windows read are printed", async () => {
      serve(mockFetch, PERSON_TREND_EMPTY);
      renderPage(ANN);
      expect(await screen.findByText("Nothing has been derived for the selected connections yet.")).toBeInTheDocument();
      expect(screen.getByText(/Derived .* · configuration revision 4/)).toBeInTheDocument();
      expect(screen.getByText("Each point is the 90 days ending on it, one point every 14 days.")).toBeInTheDocument();
    });

    test("with no figure in any window every chart says so instead of an empty frame, no cue is made up and the mix says nothing was delivered", async () => {
      serve(mockFetch, PERSON_TREND_EMPTY);
      renderPage(ANN);
      await screen.findByRole("heading", { level: 3, name: "Work mix" });
      expect(screen.getAllByText("No window has a figure to draw here.")).toHaveLength(13);
      expect(screen.queryByTestId("line-chart")).not.toBeInTheDocument();
      // Nothing to click, so no hint about clicking.
      expect(screen.queryByText(/Click a dot/)).not.toBeInTheDocument();
      expect(screen.getAllByText("No mark: fewer than two full windows with a figure in this range.")).toHaveLength(14);
      expect(screen.getByText("Nothing was delivered in any window.")).toBeInTheDocument();
      expect(screen.queryByTestId("area-chart")).not.toBeInTheDocument();
    });

    test("a failed request is said once, not in every card", async () => {
      serve(mockFetch, { title: "Internal Server Error", status: 500 }, 500);
      renderPage(ANN);
      expect(await screen.findByRole("alert")).toBeInTheDocument();
      expect(screen.queryByRole("heading", { level: 3 })).not.toBeInTheDocument();
    });
  });

  describe("remembered and printed", () => {
    test("a bare visit restores the person and the year the screen was last left on", async () => {
      serve(mockFetch);
      localStorage.setItem("flow.viewSettings.reports.person-trend.filter", JSON.stringify("accountId=acc-ann&from=2020-01-01&to=2020-12-31&preset=last365"));
      renderPage();
      await waitFor(() => expect(where()).toBe(ANN));
      expect(await screen.findByRole("heading", { level: 3, name: "Cycle time, working days" })).toBeInTheDocument();
    });

    test("the print header names the person and the period (the picker and bar are not printed)", async () => {
      serve(mockFetch);
      renderPage(ANN);
      await screen.findByRole("heading", { level: 3, name: "Cycle time, working days" });
      const header = document.querySelector('[data-print="only"]') as HTMLElement;
      expect(header).toHaveTextContent("Person: Ann Author");
      expect(header).toHaveTextContent(`${YEAR.from} → ${YEAR.to}`);
      expect(screen.getByRole("group", { name: "Person to follow" })).toHaveAttribute("data-print", "hide");
    });
  });
});
