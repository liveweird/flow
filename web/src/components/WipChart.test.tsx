import { describe, expect, test, vi } from "vitest";
import WipChart from "./WipChart";
import type { PaintedWipBand } from "../utils/wipReport";
import { renderWithProviders, screen } from "../test/render";

vi.mock("@mantine/charts", () => ({
  AreaChart: (props: {
    data: unknown;
    series: unknown;
    type: string;
    withDots: boolean;
    withLegend: boolean;
    fillOpacity: number;
    yAxisLabel: string;
  }) => (
    <div
      data-testid="area-chart"
      data-rows={JSON.stringify(props.data)}
      data-series={JSON.stringify(props.series)}
      data-type={props.type}
      data-dots={String(props.withDots)}
      data-legend={String(props.withLegend)}
      data-fill={String(props.fillOpacity)}
      data-y-label={props.yAxisLabel}
    />
  ),
}));

const band = (name: string, label: string, color: string): PaintedWipBand => ({ key: name, name, label, color });
const rows = [{ day: "2026-09-29", b0: 3, b1: 2 }];

describe("WipChart", () => {
  test("one stacked area per band, coloured as given, with a legend from two bands up and items on the one axis", () => {
    renderWithProviders(<WipChart rows={rows} bands={[band("b0", "In progress", "flow.6"), band("b1", "Done", "teal.8")]} />);
    expect(screen.getByRole("group", { name: "Chart: items at the end of each day, stacked by band" })).toBeInTheDocument();
    const chart = screen.getByTestId("area-chart");
    expect(chart.getAttribute("data-type")).toBe("stacked");
    expect(JSON.parse(chart.getAttribute("data-series")!)).toEqual([
      { name: "b0", label: "In progress", color: "flow.6" },
      { name: "b1", label: "Done", color: "teal.8" },
    ]);
    expect(chart.getAttribute("data-legend")).toBe("true");
    expect(chart.getAttribute("data-dots")).toBe("false");
    expect(chart.getAttribute("data-y-label")).toBe("Items");
    expect(JSON.parse(chart.getAttribute("data-rows")!)).toEqual(rows);
  });

  test("a single band has no legend", () => {
    renderWithProviders(<WipChart rows={rows} bands={[band("b0", "In progress", "flow.6")]} />);
    expect(screen.getByTestId("area-chart").getAttribute("data-legend")).toBe("false");
  });
});
