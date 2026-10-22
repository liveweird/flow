import { describe, expect, test, vi } from "vitest";
import CostsCategoryTrendSection from "./CostsCategoryTrendSection";
import { TREND_EMPTY_FIRST_WEEK, TREND_THREE_WEEKS } from "../test/costsCategoriesFixtures";
import { renderWithProviders, screen, within } from "../test/render";

vi.mock("@mantine/charts", () => ({
  LineChart: (props: {
    data: unknown;
    series: unknown;
    dataKey: string;
    withLegend: boolean;
    connectNulls: boolean;
    yAxisProps: unknown;
  }) => (
    <div
      data-testid="line-chart"
      data-rows={JSON.stringify(props.data)}
      data-series={JSON.stringify(props.series)}
      data-key={props.dataKey}
      data-legend={String(props.withLegend)}
      data-nulls={String(props.connectNulls)}
      data-y-axis={JSON.stringify(props.yAxisProps)}
    />
  ),
}));

const COLORS = { Run: "flow.6", Build: "violet.5", UNCATEGORIZED: "cyan.7" };
const TABLE = "Cumulative share of each work category by week";

function renderSection(trend: Parameters<typeof CostsCategoryTrendSection>[0]["trend"], unitLevel = false) {
  return renderWithProviders(<CostsCategoryTrendSection trend={trend} colors={COLORS} unitLevel={unitLevel} refreshing={false} />);
}

describe("CostsCategoryTrendSection", () => {
  test("a line per category on one 0-100 % axis in the shared colours, with a legend and gaps left as gaps", async () => {
    renderSection(TREND_THREE_WEEKS);
    expect(screen.getByRole("heading", { level: 3, name: "Work-category share, cumulative by week" })).toBeInTheDocument();
    const group = await screen.findByRole("group", { name: "Chart: cumulative share of each work category by week" });
    const chart = within(group).getByTestId("line-chart");
    expect(JSON.parse(chart.getAttribute("data-series")!)).toEqual([
      { name: "c0", label: "Run", color: "flow.6" },
      { name: "c1", label: "Build", color: "violet.5" },
      { name: "c2", label: "(uncategorized)", color: "cyan.7" },
    ]);
    expect(chart.getAttribute("data-key")).toBe("week");
    expect(chart.getAttribute("data-legend")).toBe("true");
    expect(chart.getAttribute("data-nulls")).toBe("false");
    expect(JSON.parse(chart.getAttribute("data-y-axis")!)).toMatchObject({ domain: [0, 100] });
    expect(JSON.parse(chart.getAttribute("data-rows")!)[0]).toEqual({ week: "2026-03-02", c0: 75, c1: 25, c2: 0 });
  });

  test("a single category needs no legend", async () => {
    const one = { weeks: TREND_THREE_WEEKS.weeks.map((week) => ({ ...week, byCategory: week.byCategory.slice(0, 1) })) };
    renderSection(one);
    expect((await screen.findByTestId("line-chart")).getAttribute("data-legend")).toBe("false");
  });

  test("the weekly table, newest week first: the cumulative man-days and every category's share", async () => {
    renderSection(TREND_THREE_WEEKS);
    await screen.findByTestId("line-chart");
    const table = screen.getByRole("table", { name: TABLE });
    expect(table.closest('[role="region"]')).toHaveAttribute("tabindex", "0");
    expect(within(table).getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Week of (Monday)",
      "Logged so far (MD)",
      "Run",
      "Build",
      "(uncategorized)",
    ]);
    const rows = within(table)
      .getAllByRole("row")
      .slice(1)
      .map((row) => within(row).getAllByRole("cell").map((cell) => cell.textContent));
    expect(rows).toEqual([
      ["2026-03-16", "14.5", "62.1%", "31%", "6.9%"],
      ["2026-03-09", "9", "66.7%", "27.8%", "5.6%"],
      ["2026-03-02", "4", "75%", "25%", "0%"],
    ]);
  });

  test("a week with nothing logged yet has no share: a dash in the table, a null in the chart", async () => {
    renderSection(TREND_EMPTY_FIRST_WEEK);
    const chart = await screen.findByTestId("line-chart");
    expect(JSON.parse(chart.getAttribute("data-rows")!)[0]).toEqual({ week: "2026-03-02", c0: null, c1: null, c2: null });
    const last = within(screen.getByRole("table", { name: TABLE })).getAllByRole("row").at(-1)!;
    expect(within(last).getAllByRole("cell").map((cell) => cell.textContent)).toEqual(["2026-03-02", "0", "—", "—", "—"]);
  });

  test("no weeks, or no category with time: the shared empty card, no chart", () => {
    renderSection({ weeks: [] });
    expect(screen.getByText("No data in this period")).toBeInTheDocument();
    expect(screen.queryByTestId("line-chart")).not.toBeInTheDocument();
  });

  test("no trend from the server: one line says why — a period beyond 160 weeks, or the unit level — and there is no card", () => {
    const { unmount } = renderSection(null);
    expect(screen.getByTestId("costs-trend-unavailable")).toHaveTextContent("Pick a shorter period.");
    expect(screen.queryByRole("heading")).not.toBeInTheDocument();
    unmount();
    renderSection(null, true);
    expect(screen.getByTestId("costs-trend-unavailable")).toHaveTextContent("needs a team");
  });
});
