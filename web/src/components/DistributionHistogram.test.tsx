import { describe, expect, test, vi } from "vitest";
import DistributionHistogram from "./DistributionHistogram";
import { renderWithProviders, screen } from "../test/render";

vi.mock("@mantine/charts", () => ({
  BarChart: (props: { data: unknown; series: unknown; withLegend?: boolean; xAxisProps?: unknown; xAxisLabel?: string }) => (
    <div
      data-testid="bar-chart"
      data-rows={JSON.stringify(props.data)}
      data-series={JSON.stringify(props.series)}
      data-legend={String(props.withLegend ?? false)}
      data-x-label={props.xAxisLabel}
      data-tilted={String(props.xAxisProps !== undefined)}
    />
  ),
}));

const rows = (n: number) => Array.from({ length: n }, (_, i) => ({ label: `r${i}`, count: i }));

describe("DistributionHistogram", () => {
  test("one blue series of item counts, no legend, named after its panel", () => {
    renderWithProviders(<DistributionHistogram rows={rows(3)} name="Against the estimate at start" xAxisLabel="actual ÷ estimate" />);
    expect(
      screen.getByRole("group", { name: "Histogram: number of items per range — Against the estimate at start" }),
    ).toBeInTheDocument();
    const chart = screen.getByTestId("bar-chart");
    expect(JSON.parse(chart.getAttribute("data-series")!)).toEqual([{ name: "count", label: "Items", color: "flow.6" }]);
    expect(chart.getAttribute("data-legend")).toBe("false");
    expect(chart.getAttribute("data-x-label")).toBe("actual ÷ estimate");
    expect(chart.getAttribute("data-tilted")).toBe("false");
  });

  test("many ranges tilt the x labels", () => {
    renderWithProviders(<DistributionHistogram rows={rows(8)} name="x" xAxisLabel="x" />);
    expect(screen.getByTestId("bar-chart").getAttribute("data-tilted")).toBe("true");
  });
});
