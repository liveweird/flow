import { describe, expect, test, vi } from "vitest";
import DistributionHistogram from "./DistributionHistogram";
import type { DistributionPerson } from "../utils/distributionPeople";
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

  describe("people mode", () => {
    const people: DistributionPerson[] = [
      { accountId: "acc-bob", label: "Bob", position: 1 },
      { accountId: "acc-ann", label: "Ann", position: 2 },
    ];
    const stacked = [
      { label: "0 – 2", count: 4, shares: [3, 1] },
      { label: "2 – 4", count: 2, shares: [0, 2] },
      { label: "≥ 4", count: 3, open: true, shares: [1, 2] },
    ];

    test("one stacked, outlined series per person in the categorical palette, numbered in the legend and the tooltip, the tail bucket split too", () => {
      renderWithProviders(<DistributionHistogram rows={stacked} name="Ratio" xAxisLabel="x" people={people} />);
      expect(
        screen.getByRole("group", { name: "Histogram: number of items per range, stacked by person — Ratio" }),
      ).toBeInTheDocument();
      const chart = screen.getByTestId("bar-chart");
      expect(chart.getAttribute("data-type")).toBe("stacked");
      expect(JSON.parse(chart.getAttribute("data-series")!)).toEqual([
        { name: "p0", label: "1. Bob", color: "flow.6" },
        { name: "p1", label: "2. Ann", color: "violet.5" },
      ]);
      expect(JSON.parse(chart.getAttribute("data-rows")!)).toEqual([
        { label: "0 – 2", p0: 3, p1: 1 },
        { label: "2 – 4", p0: 0, p1: 2 },
        { label: "≥ 4", p0: 1, p1: 2 },
      ]);
      // No gray "long tail" series and no chart legend of its own: the page's numbered list names the people.
      expect(chart.getAttribute("data-legend")).toBe("false");
      expect(screen.getByRole("list", { name: "Chart legend" })).toHaveTextContent("1. Bob2. Ann");
    });

    test("one person has the stack but no legend", () => {
      renderWithProviders(<DistributionHistogram rows={stacked} name="Ratio" xAxisLabel="x" people={people.slice(0, 1)} />);
      expect(JSON.parse(screen.getByTestId("bar-chart").getAttribute("data-series")!)).toHaveLength(1);
      expect(screen.queryByRole("list", { name: "Chart legend" })).not.toBeInTheDocument();
    });
  });

  test("many ranges tilt the x labels", () => {
    renderWithProviders(<DistributionHistogram rows={rows(8)} name="x" xAxisLabel="x" />);
    expect(screen.getByTestId("bar-chart").getAttribute("data-tilted")).toBe("true");
  });
});
