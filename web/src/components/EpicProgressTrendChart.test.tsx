import { describe, expect, test, vi } from "vitest";
import EpicProgressTrendChart from "./EpicProgressTrendChart";
import EpicProgressTrendTable from "./EpicProgressTrendTable";
import { renderWithProviders, screen, within } from "../test/render";

vi.mock("@mantine/charts", () => ({
  LineChart: (props: {
    data: unknown;
    series: unknown;
    dataKey: string;
    referenceLines: unknown;
    withLegend: boolean;
    connectNulls: boolean;
    yAxisLabel: string;
  }) => (
    <div
      data-testid="line-chart"
      data-rows={JSON.stringify(props.data)}
      data-series={JSON.stringify(props.series)}
      data-key={props.dataKey}
      data-reference-lines={JSON.stringify(props.referenceLines)}
      data-legend={String(props.withLegend)}
      data-nulls={String(props.connectNulls)}
      data-y-label={props.yAxisLabel}
    />
  ),
}));

const points = [
  { day: "2026-09-20", spi: null, cpi: 0.8 },
  { day: "2026-09-27", spi: 1.1, cpi: 0.95 },
];

describe("EpicProgressTrendChart", () => {
  test("SPI (plan blue) and CPI (cost gray, dashed) on ONE index axis with a legend, a reference line at 1.0 and gaps left as gaps", () => {
    renderWithProviders(<EpicProgressTrendChart points={points} />);
    expect(screen.getByRole("group", { name: "Chart: schedule and cost performance index per week" })).toBeInTheDocument();
    const chart = screen.getByTestId("line-chart");
    expect(JSON.parse(chart.getAttribute("data-series")!)).toEqual([
      { name: "spi", label: "SPI (schedule)", color: "flow.6" },
      { name: "cpi", label: "CPI (cost)", color: "gray.6", strokeDasharray: "6 4" },
    ]);
    expect(chart.getAttribute("data-key")).toBe("day");
    expect(chart.getAttribute("data-legend")).toBe("true");
    expect(chart.getAttribute("data-nulls")).toBe("false");
    expect(chart.getAttribute("data-y-label")).toBe("Index");
    expect(JSON.parse(chart.getAttribute("data-rows")!)).toEqual(points);
    const lines = JSON.parse(chart.getAttribute("data-reference-lines")!);
    expect(lines).toHaveLength(1);
    expect(lines[0]).toMatchObject({ y: 1, label: "On target (1.0)", strokeDasharray: "2 4" });
  });
});

describe("EpicProgressTrendTable", () => {
  test("the chart's numbers, newest week first, two decimals, a hidden index as a dash", () => {
    renderWithProviders(<EpicProgressTrendTable points={points} />);
    const table = screen.getByRole("table", { name: "SPI and CPI per week, as a table" });
    expect(table.closest('[role="region"]')).toHaveAttribute("tabindex", "0");
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Week ending", "SPI", "CPI"]);
    const body = within(table)
      .getAllByRole("row")
      .slice(1)
      .map((row) => within(row).getAllByRole("cell").map((cell) => cell.textContent));
    expect(body).toEqual([
      ["2026-09-27", "1.10", "0.95"],
      ["2026-09-20", "—", "0.80"],
    ]);
  });
});
