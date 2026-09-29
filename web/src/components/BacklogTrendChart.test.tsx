import { describe, expect, test, vi } from "vitest";
import BacklogTrendChart from "./BacklogTrendChart";
import { renderWithProviders, screen } from "../test/render";

type TooltipContent = (props: { label: string; payload?: unknown[] }) => { props: { label: string } };

vi.mock("@mantine/charts", () => ({
  AreaChart: (props: {
    data: unknown;
    series: unknown;
    withLegend?: boolean;
    withDots: boolean;
    tooltipProps: { content: TooltipContent };
  }) => (
    <div
      data-testid="area-chart"
      data-rows={JSON.stringify(props.data)}
      data-series={JSON.stringify(props.series)}
      data-legend={String(props.withLegend ?? false)}
      data-dots={String(props.withDots)}
      data-tooltip={props.tooltipProps.content({ label: "2026-09-29", payload: [{ payload: { items: 12 } }] }).props.label}
      data-tooltip-bare={props.tooltipProps.content({ label: "2026-09-30" }).props.label}
    />
  ),
  ChartTooltip: () => null,
}));

describe("BacklogTrendChart", () => {
  test("one blue series in man-days, no legend, and a tooltip that adds the item count", () => {
    const rows = [{ day: "2026-09-29", md: 25, items: 12 }];
    renderWithProviders(<BacklogTrendChart rows={rows} />);
    expect(screen.getByRole("group", { name: "Chart: estimated backlog in man-days by day" })).toBeInTheDocument();
    const chart = screen.getByTestId("area-chart");
    expect(JSON.parse(chart.getAttribute("data-series")!)).toEqual([{ name: "md", label: "Backlog", color: "flow.6" }]);
    expect(chart.getAttribute("data-legend")).toBe("false");
    expect(chart.getAttribute("data-dots")).toBe("false");
    expect(JSON.parse(chart.getAttribute("data-rows")!)).toEqual(rows);
    expect(chart.getAttribute("data-tooltip")).toBe("2026-09-29 · 12 items");
    expect(chart.getAttribute("data-tooltip-bare")).toBe("2026-09-30");
  });
});
