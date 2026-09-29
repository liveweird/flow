import { describe, expect, test, vi } from "vitest";
import ThroughputChart from "./ThroughputChart";
import { renderWithProviders, screen } from "../test/render";

type TooltipContent = (props: { label: string; payload: unknown[] }) => { props: { label: string } };

vi.mock("@mantine/charts", () => ({
  BarChart: (props: {
    data: unknown;
    series: unknown;
    withLegend?: boolean;
    valueFormatter: (v: number) => string;
    xAxisProps?: unknown;
    tooltipProps: { content: TooltipContent };
  }) => (
    <div
      data-testid="bar-chart"
      data-rows={JSON.stringify(props.data)}
      data-series={JSON.stringify(props.series)}
      data-legend={String(props.withLegend ?? false)}
      data-tilted={String(props.xAxisProps !== undefined)}
      data-tooltip={
        (props.tooltipProps.content({ label: "2026-09-07", payload: [{ payload: { deliveredItems: 5 } }] }) as { props: { label: string } })
          .props.label
      }
      data-tooltip-empty={
        (props.tooltipProps.content({ label: "x", payload: [] }) as { props: { label: string } }).props.label
      }
    />
  ),
  ChartTooltip: () => null,
}));

const row = (label: string) => ({ label, deliveredMd: 1, deliveredItems: 2 });

describe("ThroughputChart", () => {
  test("one teal series, no legend, an accessible name, and the item count in the tooltip", () => {
    renderWithProviders(<ThroughputChart rows={[row("a"), row("b")]} />);
    expect(screen.getByRole("group", { name: "Chart: delivered scope by period" })).toBeInTheDocument();
    const chart = screen.getByTestId("bar-chart");
    expect(JSON.parse(chart.getAttribute("data-series")!)).toEqual([
      { name: "deliveredMd", label: "Delivered", color: "teal.8" },
    ]);
    expect(chart.getAttribute("data-legend")).toBe("false");
    expect(chart.getAttribute("data-tooltip")).toBe("2026-09-07 · 5 items");
    expect(chart.getAttribute("data-tooltip-empty")).toBe("x");
    expect(chart.getAttribute("data-tilted")).toBe("false");
  });

  test("many buckets tilt the x labels", () => {
    renderWithProviders(<ThroughputChart rows={Array.from({ length: 12 }, (_, i) => row(`b${i}`))} />);
    expect(screen.getByTestId("bar-chart").getAttribute("data-tilted")).toBe("true");
  });
});
