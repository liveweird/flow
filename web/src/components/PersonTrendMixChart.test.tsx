import { describe, expect, test, vi } from "vitest";
import PersonTrendMixChart from "./PersonTrendMixChart";
import { renderWithProviders, screen } from "../test/render";

type TooltipContent = (props: { label: string; payload: unknown[] }) => { props: { label: string } };

// recharts renders nothing under happy-dom: the chart is a probe carrying the props the component decides.
vi.mock("@mantine/charts", () => ({
  AreaChart: (props: {
    data: unknown;
    series: unknown;
    type: string;
    connectNulls: boolean;
    withDots: boolean;
    withLegend: boolean;
    yAxisProps: { domain: number[] };
    tooltipProps: { content: TooltipContent };
  }) => (
    <div
      data-testid="area-chart"
      data-rows={JSON.stringify(props.data)}
      data-series={JSON.stringify(props.series)}
      data-type={props.type}
      data-connect-nulls={String(props.connectNulls)}
      data-legend={String(props.withLegend)}
      data-domain={JSON.stringify(props.yAxisProps.domain)}
      data-tooltip-partial={props.tooltipProps.content({ label: "2026-07-14", payload: [] }).props.label}
      data-tooltip-full={props.tooltipProps.content({ label: "2026-07-28", payload: [] }).props.label}
    />
  ),
  ChartTooltip: () => null,
}));

const rows = [
  { label: "2026-07-14", s0: 0.5, s1: 0.5 },
  { label: "2026-07-28", s0: null, s1: null },
];

describe("PersonTrendMixChart", () => {
  function chart(names: string[] = ["Story", "Bug"]) {
    return renderWithProviders(<PersonTrendMixChart rows={rows} names={names} label="Work mix by activity type" axisLabel="Share" partialAt={[true, false]} />);
  }

  test("a stacked area of shares on a 0..100% axis, a legend, gaps that break the area", () => {
    chart();
    expect(screen.getByRole("group", { name: "Work mix by activity type" })).toBeInTheDocument();
    const el = screen.getByTestId("area-chart");
    expect(el).toHaveAttribute("data-type", "stacked");
    expect(el).toHaveAttribute("data-connect-nulls", "false");
    expect(el).toHaveAttribute("data-legend", "true");
    expect(el).toHaveAttribute("data-domain", "[0,1]");
    expect(JSON.parse(el.getAttribute("data-rows")!)).toEqual(rows);
  });

  test("the bands wear the categorical palette by position and are named by their keys, the first at the bottom", () => {
    chart();
    expect(JSON.parse(screen.getByTestId("area-chart").getAttribute("data-series")!)).toEqual([
      { name: "s0", label: "Story", color: "flow.6" },
      { name: "s1", label: "Bug", color: "violet.5" },
    ]);
  });

  test("more bands than the palette has repeat its colours (the legend, tooltip and table carry identity)", () => {
    chart(Array.from({ length: 8 }, (_, i) => `K${i}`));
    const colors = JSON.parse(screen.getByTestId("area-chart").getAttribute("data-series")!).map((series: { color: string }) => series.color);
    expect(colors[7]).toBe(colors[0]);
  });

  test("the tooltip says a window is partial", () => {
    chart();
    const el = screen.getByTestId("area-chart");
    expect(el).toHaveAttribute("data-tooltip-partial", "2026-07-14 · partial");
    expect(el).toHaveAttribute("data-tooltip-full", "2026-07-28");
  });
});
