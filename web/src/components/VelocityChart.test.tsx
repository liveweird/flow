import { describe, expect, test, vi } from "vitest";
import { MantineProvider } from "@mantine/core";
import { render } from "@testing-library/react";
import VelocityChart from "./VelocityChart";
import { theme } from "../theme";
import { renderWithProviders, screen } from "../test/render";

vi.mock("@mantine/charts", () => ({
  BarChart: (props: {
    data: unknown;
    dataKey: string;
    series: unknown;
    withLegend: boolean;
    valueFormatter: (v: number) => string;
    xAxisProps?: unknown;
  }) => (
    <div
      data-testid="bar-chart"
      data-key={props.dataKey}
      data-rows={JSON.stringify(props.data)}
      data-series={JSON.stringify(props.series)}
      data-legend={String(props.withLegend)}
      data-formatted={props.valueFormatter(12.3456)}
      data-crowded={String(props.xAxisProps !== undefined)}
    />
  ),
}));

const row = (label: string) => ({ label, initialMd: 1, finalMd: 2, initialItems: 1, finalItems: 2 });

describe("VelocityChart", () => {
  test("plots initial and final MD per sprint, blue/blue vocabulary, with a legend and an accessible name", () => {
    renderWithProviders(<VelocityChart rows={[row("S1"), row("S2")]} />);
    expect(screen.getByRole("group", { name: "Chart: initial and final scope by sprint" })).toBeInTheDocument();
    const chart = screen.getByTestId("bar-chart");
    expect(chart.getAttribute("data-key")).toBe("label");
    expect(JSON.parse(chart.getAttribute("data-series")!)).toEqual([
      { name: "initialMd", label: "Initial (committed)", color: "flow.6" },
      { name: "finalMd", label: "Final", color: "flow.8" },
    ]);
    expect(chart.getAttribute("data-legend")).toBe("true");
    expect(chart.getAttribute("data-formatted")).toBe("12.35");
    expect(chart.getAttribute("data-crowded")).toBe("false");
  });

  test("the final series switches to a lighter blue in the dark scheme (flow.8 fails 3:1 on dark paper)", () => {
    render(
      <MantineProvider env="test" theme={theme} forceColorScheme="dark">
        <VelocityChart rows={[row("S1")]} />
      </MantineProvider>,
    );
    const series = JSON.parse(screen.getByTestId("bar-chart").getAttribute("data-series")!);
    expect(series.map((s: { color: string }) => s.color)).toEqual(["flow.6", "flow.4"]);
  });

  test("many sprints tilt the x labels instead of overlapping", () => {
    renderWithProviders(<VelocityChart rows={Array.from({ length: 8 }, (_, i) => row(`S${i}`))} />);
    expect(screen.getByTestId("bar-chart").getAttribute("data-crowded")).toBe("true");
  });
});
