import { describe, expect, test, vi } from "vitest";
import CostsTargetsSection from "./CostsTargetsSection";
import { TARGETS_CONFIGURED, TARGETS_NOTHING_LOGGED, TARGETS_UNCONFIGURED } from "../test/costsCategoriesFixtures";
import { renderWithProviders, screen, within } from "../test/render";

// recharts draws nothing under happy-dom: the diverging bar is a probe carrying the props the page hands it.
vi.mock("@mantine/charts", () => ({
  BarChart: (props: {
    data: unknown;
    series: unknown;
    dataKey: string;
    orientation: string;
    referenceLines: unknown;
    withLegend?: boolean;
    xAxisProps: unknown;
  }) => (
    <div
      data-testid="bar-chart"
      data-rows={JSON.stringify(props.data)}
      data-series={JSON.stringify(props.series)}
      data-key={props.dataKey}
      data-orientation={props.orientation}
      data-reference-lines={JSON.stringify(props.referenceLines)}
      data-legend={String(props.withLegend ?? false)}
      data-x-axis={JSON.stringify(props.xAxisProps)}
    />
  ),
}));

const COLORS = { Run: "flow.6", Build: "violet.5", Retired: "indigo.5", UNCATEGORIZED: "cyan.7", Spike: "pink.6" };
const TABLE = "Work-category targets and actual mix";

function renderSection(targets = TARGETS_CONFIGURED) {
  return renderWithProviders(<CostsTargetsSection targets={targets} teamId={7} teamName="Alpha" colors={COLORS} refreshing={false} />);
}

const cells = (table: HTMLElement) =>
  within(table)
    .getAllByRole("row")
    .slice(1)
    .map((row) => within(row).getAllByRole("cell").map((cell) => cell.textContent ?? ""));

describe("CostsTargetsSection", () => {
  test("a configured team: the table in the server's order with target, man-days, actual share and signed delta, “(uncategorized)” for the none-row", () => {
    renderSection();
    expect(screen.getByRole("heading", { level: 3, name: "Work-category targets against the actual mix" })).toBeInTheDocument();
    const table = screen.getByRole("table", { name: TABLE });
    expect(table.closest('[role="region"]')).toHaveAttribute("tabindex", "0");
    expect(within(table).getAllByRole("columnheader").map((header) => header.textContent)).toEqual([
      "Category",
      "Target %",
      "Actual MD",
      "Actual %",
      "Δ (pp)",
    ]);
    expect(cells(table)).toEqual([
      ["Run", "60", "9", "62.1%", "+2.1"],
      ["Build", "30", "4.5", "31%", "+1.0"],
      ["RetiredNot mapped any more", "10", "0", "0%", "−10.0"],
      ["(uncategorized)", "0", "1", "6.9%", "+6.9"],
    ]);
  });

  test("only a category the metrics configuration no longer maps wears the badge", () => {
    renderSection();
    expect(screen.getAllByText("Not mapped any more")).toHaveLength(1);
    const row = screen.getByText("Retired").closest("tr")!;
    expect(within(row).getByText("Not mapped any more")).toBeInTheDocument();
  });

  test("the chart: one neutral series, horizontal, a centred zero line and a symmetric axis, with no legend; rows without a delta are left out", async () => {
    renderSection();
    expect(await screen.findByRole("group", { name: "Chart: actual share minus target, per work category" })).toBeInTheDocument();
    const chart = screen.getByTestId("bar-chart");
    expect(chart.getAttribute("data-orientation")).toBe("vertical");
    expect(chart.getAttribute("data-key")).toBe("label");
    expect(chart.getAttribute("data-legend")).toBe("false");
    expect(JSON.parse(chart.getAttribute("data-series")!)).toEqual([{ name: "delta", label: "Actual minus target", color: "gray.6" }]);
    expect(JSON.parse(chart.getAttribute("data-rows")!)).toEqual([
      { label: "Run", delta: 2.1 },
      { label: "Build", delta: 1 },
      { label: "Retired", delta: -10 },
      { label: "(uncategorized)", delta: 6.9 },
    ]);
    expect(JSON.parse(chart.getAttribute("data-reference-lines")!)).toHaveLength(1);
    expect(JSON.parse(chart.getAttribute("data-reference-lines")!)[0]).toMatchObject({ x: 0 });
    expect(JSON.parse(chart.getAttribute("data-x-axis")!)).toMatchObject({ domain: [-10, 10] });
  });

  test("nothing logged: a null share and a null delta read as a dash, and no bar is drawn", () => {
    renderSection(TARGETS_NOTHING_LOGGED);
    expect(cells(screen.getByRole("table", { name: TABLE }))).toEqual([
      ["Run", "70", "0", "—", "—"],
      ["SpikeNot mapped any more", "0", "0", "—", "—"],
    ]);
    // The chart chunk is lazy: nothing is requested at all when no row has a delta.
    expect(screen.queryByRole("group", { name: /^Chart:/ })).not.toBeInTheDocument();
    expect(screen.queryByTestId("bar-chart")).not.toBeInTheDocument();
  });

  test("not configured: a short note and a link to the team's targets page, no table and no chart", () => {
    renderSection(TARGETS_UNCONFIGURED);
    expect(screen.getByText("Alpha has no work-category targets yet.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open the work-category targets of Alpha" })).toHaveAttribute("href", "/teams/7/work-category-targets");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByTestId("bar-chart")).not.toBeInTheDocument();
  });
});
