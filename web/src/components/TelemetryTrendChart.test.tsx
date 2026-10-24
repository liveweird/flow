import { describe, expect, test, vi } from "vitest";
import type { ReactElement } from "react";
import { renderWithProviders, screen } from "../test/render";
import TelemetryTrendChart from "./TelemetryTrendChart";

interface Props {
  data: unknown;
  series: unknown;
  withLegend: boolean;
  withDots: boolean;
  connectNulls: boolean;
  valueFormatter: (value: number) => string;
  xAxisProps: { tickFormatter: (value: string) => string };
  tooltipProps: { content: (props: { label: string; payload?: unknown[] }) => ReactElement<{ label: string }> };
}

vi.mock("@mantine/charts", () => ({
  ChartTooltip: () => null,
  LineChart: (props: Props) => (
    <div
      data-testid="line-chart"
      data-rows={JSON.stringify(props.data)}
      data-series={JSON.stringify(props.series)}
      data-legend={String(props.withLegend)}
      data-dots={String(props.withDots)}
      data-nulls={String(props.connectNulls)}
      data-formatted={props.valueFormatter(1536)}
      data-tick={props.xAxisProps.tickFormatter("2026-10-24 11:55")}
      data-tooltip={props.tooltipProps.content({ label: "2026-10-24 11:55" }).props.label}
    />
  ),
}));

const rows = [{ label: "2026-10-24 11:55", s0: 1, s1: 2 }];

describe("TelemetryTrendChart", () => {
  test("two lines wear the categorical palette, get a legend, one axis unit and gaps for missing buckets", () => {
    renderWithProviders(<TelemetryTrendChart rows={rows} lines={[{ label: "p50" }, { label: "p95" }]} unit="BYTES" range="24h" ariaLabel="Trend: Requests" />);
    expect(screen.getByRole("group", { name: "Trend: Requests" })).toBeInTheDocument();
    const chart = screen.getByTestId("line-chart");
    expect(JSON.parse(chart.getAttribute("data-series")!)).toEqual([
      { name: "s0", label: "p50", color: "flow.6" },
      { name: "s1", label: "p95", color: "violet.5" },
    ]);
    expect(chart.getAttribute("data-legend")).toBe("true");
    expect(chart.getAttribute("data-dots")).toBe("false");
    expect(chart.getAttribute("data-nulls")).toBe("false");
    expect(chart.getAttribute("data-formatted")).toBe("1.5 KB");
    expect(JSON.parse(chart.getAttribute("data-rows")!)).toEqual(rows);
    // The tooltip's stamp says it is UTC.
    expect(chart.getAttribute("data-tooltip")).toBe("2026-10-24 11:55 UTC");
  });

  test("a 24 h axis adds the date once the window crosses midnight", () => {
    const crossing = [{ label: "2026-10-23 23:00", s0: 1 }, { label: "2026-10-24 11:55", s0: 2 }];
    renderWithProviders(<TelemetryTrendChart rows={crossing} lines={[{ label: "CPU" }]} unit="PERCENT" range="24h" ariaLabel="x" />);
    expect(screen.getByTestId("line-chart").getAttribute("data-tick")).toBe("10-24 11:55");
  });

  test("a single line has no legend, and the x ticks read as clock times for a day and as dates for longer ranges", () => {
    const { rerender } = renderWithProviders(<TelemetryTrendChart rows={rows} lines={[{ label: "CPU" }]} unit="PERCENT" range="24h" ariaLabel="x" />);
    expect(screen.getByTestId("line-chart").getAttribute("data-legend")).toBe("false");
    expect(screen.getByTestId("line-chart").getAttribute("data-tick")).toBe("11:55");
    rerender(<TelemetryTrendChart rows={rows} lines={[{ label: "CPU" }]} unit="PERCENT" range="90d" ariaLabel="x" />);
    expect(screen.getByTestId("line-chart").getAttribute("data-tick")).toBe("10-24");
  });
});
