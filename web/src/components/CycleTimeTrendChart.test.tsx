import { describe, expect, test, vi } from "vitest";
import CycleTimeTrendChart from "./CycleTimeTrendChart";
import { renderWithProviders, screen } from "../test/render";

type TooltipContent = (props: { label: string; payload: unknown[] }) => { props: { label: string } };

vi.mock("@mantine/charts", () => ({
  LineChart: (props: {
    data: unknown;
    series: unknown;
    connectNulls: boolean;
    withDots: boolean;
    withLegend: boolean;
    xAxisProps?: unknown;
    tooltipProps: { content: TooltipContent };
  }) => (
    <div
      data-testid="line-chart"
      data-rows={JSON.stringify(props.data)}
      data-series={JSON.stringify(props.series)}
      data-connect-nulls={String(props.connectNulls)}
      data-dots={String(props.withDots)}
      data-legend={String(props.withLegend)}
      data-tilted={String(props.xAxisProps !== undefined)}
      data-tooltip-visible={props.tooltipProps.content({ label: "a", payload: [{ payload: { n: 6 } }] }).props.label}
      data-tooltip-hidden={props.tooltipProps.content({ label: "b", payload: [] }).props.label}
      data-tooltip-empty-bucket={props.tooltipProps.content({ label: "e", payload: [] }).props.label}
      data-tooltip-unknown={props.tooltipProps.content({ label: "x", payload: [] }).props.label}
    />
  ),
  ChartTooltip: () => null,
}));

const row = (label: string, p50: number | null = 1, p90: number | null = 2) => ({ label, p50, p90, n: 6 });

describe("CycleTimeTrendChart", () => {
  test("median solid blue, p90 dashed gray — both ≥ 3:1 — with a legend, dots, and lines that BREAK at gaps", () => {
    renderWithProviders(<CycleTimeTrendChart rows={[row("a"), row("b", null, null), row("c")]} />);
    expect(screen.getByRole("group", { name: "Chart: median and p90 cycle time in working days by period" })).toBeInTheDocument();
    const chart = screen.getByTestId("line-chart");
    expect(JSON.parse(chart.getAttribute("data-series")!)).toEqual([
      { name: "p50", label: "Median (p50)", color: "flow.6" },
      { name: "p90", label: "p90", color: "gray.6", strokeDasharray: "6 4" },
    ]);
    // Gaps: the hidden bucket's values are null (never 0) and the line is not bridged over it.
    expect(chart.getAttribute("data-connect-nulls")).toBe("false");
    expect(JSON.parse(chart.getAttribute("data-rows")!)[1]).toMatchObject({ label: "b", p50: null, p90: null });
    expect(chart.getAttribute("data-dots")).toBe("true");
    expect(chart.getAttribute("data-legend")).toBe("true");
  });

  test("the tooltip names the bucket's item count — and, for a hidden bucket, why it has no value", () => {
    renderWithProviders(
      <CycleTimeTrendChart rows={[row("a"), { label: "b", p50: null, p90: null, n: 3 }, { label: "e", p50: null, p90: null, n: 0 }]} />,
    );
    const chart = screen.getByTestId("line-chart");
    expect(chart.getAttribute("data-tooltip-visible")).toBe("a · 6 items");
    // The hidden bucket arrives with an EMPTY payload; the row is found by its label.
    expect(chart.getAttribute("data-tooltip-hidden")).toBe("b · 3 items, below the minimum sample");
    expect(chart.getAttribute("data-tooltip-empty-bucket")).toBe("e · 0 items");
    expect(chart.getAttribute("data-tooltip-unknown")).toBe("x");
  });

  test("many buckets tilt the x labels", () => {
    renderWithProviders(<CycleTimeTrendChart rows={Array.from({ length: 9 }, (_, i) => row(`b${i}`))} />);
    expect(screen.getByTestId("line-chart").getAttribute("data-tilted")).toBe("true");
  });
});
