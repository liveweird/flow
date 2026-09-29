import { describe, expect, test, vi } from "vitest";
import SprintConsistencyChart from "./SprintConsistencyChart";
import { renderWithProviders, screen } from "../test/render";

vi.mock("@mantine/charts", () => ({
  BarChart: (props: {
    series: unknown;
    type?: string;
    withLegend: boolean;
    barProps: unknown;
    xAxisProps?: unknown;
  }) => (
    <div
      data-testid="bar-chart"
      data-series={JSON.stringify(props.series)}
      data-type={props.type}
      data-legend={String(props.withLegend)}
      data-bar={JSON.stringify(props.barProps)}
      data-tilted={String(props.xAxisProps !== undefined)}
    />
  ),
}));

const row = (label: string) => ({
  label,
  committedMd: 1,
  deliveredMd: 1,
  carriedOverMd: 1,
  droppedMd: 1,
  addedMd: 1,
  removedMd: 1,
});
const colors = () => JSON.parse(screen.getByTestId("bar-chart").getAttribute("data-series")!).map((s: { color: string }) => s.color);

describe("SprintConsistencyChart", () => {
  test("scope: committed (blue) beside delivered (teal), grouped", () => {
    renderWithProviders(<SprintConsistencyChart kind="scope" rows={[row("a")]} />);
    expect(screen.getByRole("group", { name: "Chart: committed and delivered scope by sprint" })).toBeInTheDocument();
    expect(colors()).toEqual(["flow.6", "teal.8"]);
    expect(screen.getByTestId("bar-chart").getAttribute("data-type")).toBe("default");
    expect(screen.getByTestId("bar-chart").getAttribute("data-legend")).toBe("true");
  });

  test("partition: stacked carried over / delivered / dropped — teal between orange and red, with a surface stroke", () => {
    renderWithProviders(<SprintConsistencyChart kind="partition" rows={[row("a")]} />);
    expect(colors()).toEqual(["orange.8", "teal.8", "red.7"]);
    const chart = screen.getByTestId("bar-chart");
    expect(chart.getAttribute("data-type")).toBe("stacked");
    expect(JSON.parse(chart.getAttribute("data-bar")!)).toEqual({ stroke: "var(--mantine-color-body)", strokeWidth: 2 });
  });

  test("changes: added (orange) beside removed (gray)", () => {
    renderWithProviders(<SprintConsistencyChart kind="changes" rows={[row("a")]} />);
    expect(colors()).toEqual(["orange.8", "gray.6"]);
    expect(screen.getByRole("group", { name: "Chart: scope added and removed during each sprint" })).toBeInTheDocument();
  });

  test("many sprints tilt the x labels", () => {
    renderWithProviders(<SprintConsistencyChart kind="scope" rows={Array.from({ length: 7 }, (_, i) => row(`s${i}`))} />);
    expect(screen.getByTestId("bar-chart").getAttribute("data-tilted")).toBe("true");
  });
});
