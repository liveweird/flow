import { describe, expect, test, vi } from "vitest";
import EpicProgressChart from "./EpicProgressChart";
import { renderWithProviders, screen } from "../test/render";

vi.mock("@mantine/charts", () => ({
  LineChart: (props: {
    data: unknown;
    series: unknown;
    dataKey: string;
    withDots: boolean;
    withLegend: boolean;
    connectNulls: boolean;
    yAxisLabel: string;
  }) => (
    <div
      data-testid="line-chart"
      data-rows={JSON.stringify(props.data)}
      data-series={JSON.stringify(props.series)}
      data-key={props.dataKey}
      data-dots={String(props.withDots)}
      data-legend={String(props.withLegend)}
      data-nulls={String(props.connectNulls)}
      data-y-label={props.yAxisLabel}
    />
  ),
}));

const rows = [
  { date: "2026-09-28", pv: 10, ev: 6, ac: 8, pvOriginal: null },
  { date: "2026-09-29", pv: 12, ev: 9, ac: 10, pvOriginal: 14 },
];

describe("EpicProgressChart", () => {
  test("PV blue, EV teal, AC gray on one man-day axis over ISO dates, with a legend and gaps left as gaps", () => {
    renderWithProviders(<EpicProgressChart rows={rows} withOriginal={false} />);
    expect(
      screen.getByRole("group", { name: "Chart: cumulative planned value, earned value and actual cost in man-days by day" }),
    ).toBeInTheDocument();
    const chart = screen.getByTestId("line-chart");
    expect(JSON.parse(chart.getAttribute("data-series")!)).toEqual([
      { name: "pv", label: "Planned value (PV)", color: "flow.6" },
      { name: "ev", label: "Earned value (EV)", color: "teal.8" },
      { name: "ac", label: "Actual cost (AC)", color: "gray.6" },
    ]);
    expect(chart.getAttribute("data-key")).toBe("date");
    expect(chart.getAttribute("data-legend")).toBe("true");
    expect(chart.getAttribute("data-nulls")).toBe("false");
    expect(chart.getAttribute("data-dots")).toBe("false");
    expect(chart.getAttribute("data-y-label")).toBe("MD");
    expect(JSON.parse(chart.getAttribute("data-rows")!)).toEqual(rows);
  });

  test("the original plan is a fourth series: the plan blue again, told apart by its dash", () => {
    renderWithProviders(<EpicProgressChart rows={rows} withOriginal />);
    const series = JSON.parse(screen.getByTestId("line-chart").getAttribute("data-series")!);
    expect(series.map((s: { name: string }) => s.name)).toEqual(["pv", "pvOriginal", "ev", "ac"]);
    expect(series[1]).toEqual({ name: "pvOriginal", label: "Original plan (PV)", color: "flow.6", strokeDasharray: "6 4" });
    // Only the original plan is dashed.
    expect(series.filter((s: { strokeDasharray?: string }) => s.strokeDasharray)).toHaveLength(1);
  });
});
