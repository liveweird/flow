import { describe, expect, test, vi } from "vitest";
import DistributionHistogram from "./DistributionHistogram";
import { renderWithProviders, screen } from "../test/render";

vi.mock("@mantine/charts", () => ({
  BarChart: (props: { data: unknown; series: unknown; withLegend?: boolean; type?: string; xAxisProps?: unknown; xAxisLabel?: string }) => (
    <div
      data-testid="bar-chart"
      data-type={props.type}
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

  test("an open long-tail bucket is its own gray series, stacked, with a legend; the other series is absent there, never 0", () => {
    const tail = [
      { label: "0 – 2", count: 4 },
      { label: "2 – 4", count: 2 },
      { label: "≥ 4", count: 3, open: true },
    ];
    renderWithProviders(<DistributionHistogram rows={tail} name="Working days" xAxisLabel="working days" />);
    const chart = screen.getByTestId("bar-chart");
    expect(JSON.parse(chart.getAttribute("data-series")!)).toEqual([
      { name: "count", label: "Items", color: "flow.6" },
      { name: "tail", label: "Long tail (top values)", color: "gray.6" },
    ]);
    expect(chart.getAttribute("data-legend")).toBe("true");
    expect(chart.getAttribute("data-type")).toBe("stacked");
    // JSON drops an undefined, which is exactly what a series a bar does not belong to must be (the tooltip lists only its own).
    expect(JSON.parse(chart.getAttribute("data-rows")!)).toEqual([
      { label: "0 – 2", count: 4 },
      { label: "2 – 4", count: 2 },
      { label: "≥ 4", tail: 3 },
    ]);
  });

  test("many ranges tilt the x labels", () => {
    renderWithProviders(<DistributionHistogram rows={rows(8)} name="x" xAxisLabel="x" />);
    expect(screen.getByTestId("bar-chart").getAttribute("data-tilted")).toBe("true");
  });
});
