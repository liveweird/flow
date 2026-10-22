import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import ReportCosts from "./ReportCosts";
import { jsonResponse } from "../test/http";
import { headingOutline } from "../test/headings";
import {
  COSTS_CATEGORIES_TOO_LONG,
  COSTS_CATEGORIES_UNCONFIGURED,
  COSTS_CATEGORIES_USER,
  COSTS_WITH_CATEGORIES,
} from "../test/costsCategoriesFixtures";
import { COSTS_TEAM, FILTERS } from "../test/reportFixtures";
import { renderWithProviders, screen, within } from "../test/render";

// recharts renders nothing under happy-dom: every chart is a probe carrying the series it plots.
vi.mock("@mantine/charts", () => {
  const probe = (kind: string) => (props: { series: { name: string }[] }) => <div data-testid={`chart-${kind}`} data-series={props.series.map((s) => s.name).join(",")} />;
  return { BarChart: probe("bar"), LineChart: probe("line"), CompositeChart: probe("composite"), DonutChart: () => null, ChartTooltip: () => null };
});

type FetchMock = ReturnType<typeof vi.fn>;
const REPORT = "/api/v1/reports/costs?";

function serve(mockFetch: FetchMock, report: unknown) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/reports/filters") return Promise.resolve(jsonResponse(200, FILTERS));
    if (url.startsWith(REPORT)) return Promise.resolve(jsonResponse(200, report));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

const PAGE = "/reports/costs?teamId=1&lastSprints=6";
const TARGETS_TABLE = "Work-category targets and actual mix";
const TREND_TABLE = "Cumulative share of each work category by week";

describe("ReportCosts page — the work-category widgets", () => {
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

  test("a team with targets: the targets card then the trend card, under the donuts and above the sprint widgets, both h3", async () => {
    serve(mockFetch, COSTS_WITH_CATEGORIES);
    renderWithProviders(<ReportCosts />, { route: PAGE });
    expect(await screen.findByRole("table", { name: TARGETS_TABLE })).toBeInTheDocument();
    expect(await screen.findByRole("table", { name: TREND_TABLE })).toBeInTheDocument();
    const outline = headingOutline().filter(([level]) => level === 3).map(([, text]) => text);
    expect(outline).toEqual([
      "Man-days logged by the team",
      "Man-days by activity type",
      "Man-days by work category",
      "Work-category targets against the actual mix",
      "Work-category share, cumulative by week",
      "Actual cost per sprint",
    ]);
    expect(await screen.findByTestId("chart-bar")).toHaveAttribute("data-series", "delta");
    expect(await screen.findByTestId("chart-line")).toHaveAttribute("data-series", "c0,c1,c2");
  });

  test("the targets table is the team's: Run 60 → 62.1 % is +2.1 points, and the swatches wear the donut's colours", async () => {
    serve(mockFetch, COSTS_WITH_CATEGORIES);
    renderWithProviders(<ReportCosts />, { route: PAGE });
    const row = (await screen.findByRole("table", { name: TARGETS_TABLE })).querySelector("tbody tr")!;
    expect(within(row as HTMLElement).getAllByRole("cell").map((cell) => cell.textContent)).toEqual(["Run", "60", "9", "62.1%", "+2.1"]);
    expect(row.querySelector("td div[aria-hidden]")?.getAttribute("style")).toContain("--mantine-color-flow-6");
  });

  test("a team with no targets yet: the note and a link to its targets page, the trend still drawn", async () => {
    serve(mockFetch, COSTS_CATEGORIES_UNCONFIGURED);
    renderWithProviders(<ReportCosts />, { route: PAGE });
    expect(await screen.findByText("Alpha has no work-category targets yet.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open the work-category targets of Alpha" })).toHaveAttribute("href", "/teams/1/work-category-targets");
    expect(screen.queryByRole("table", { name: TARGETS_TABLE })).not.toBeInTheDocument();
    expect(await screen.findByRole("table", { name: TREND_TABLE })).toBeInTheDocument();
  });

  test("one author picked: no targets card, one dimmed line says why, and the trend is that person's own", async () => {
    serve(mockFetch, COSTS_CATEGORIES_USER);
    renderWithProviders(<ReportCosts />, { route: `${PAGE}&accountId=acc-ann` });
    expect(await screen.findByTestId("costs-targets-unavailable")).toHaveTextContent("belong to the team: clear the author filter");
    expect(screen.queryByRole("heading", { name: "Work-category targets against the actual mix" })).not.toBeInTheDocument();
    expect(await screen.findByRole("table", { name: TREND_TABLE })).toBeInTheDocument();
  });

  test("a slice narrows the worklogs, so the server sends no targets: the line says to clear the filters", async () => {
    serve(mockFetch, { ...COSTS_TEAM, categoryTargets: null, categoryShareTrend: COSTS_WITH_CATEGORIES.categoryShareTrend });
    renderWithProviders(<ReportCosts />, { route: `${PAGE}&domain=Payments` });
    expect(await screen.findByTestId("costs-targets-unavailable")).toHaveTextContent("clear the domain, activity type and work category filters");
  });

  test("a period beyond 160 weeks: the targets are there, the trend is replaced by one line asking for a shorter period", async () => {
    serve(mockFetch, COSTS_CATEGORIES_TOO_LONG);
    renderWithProviders(<ReportCosts />, { route: PAGE });
    expect(await screen.findByRole("table", { name: TARGETS_TABLE })).toBeInTheDocument();
    expect(screen.getByTestId("costs-trend-unavailable")).toHaveTextContent("Pick a shorter period.");
    expect(screen.queryByRole("table", { name: TREND_TABLE })).not.toBeInTheDocument();
  });
});
