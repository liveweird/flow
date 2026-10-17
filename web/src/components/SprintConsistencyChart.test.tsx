import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import SprintConsistencyChart from "./SprintConsistencyChart";
import { renderWithProviders, screen } from "../test/render";

type BarProps = Record<string, unknown> & { onDoubleClick?: (entry: { payload: unknown }, index: number, event: unknown) => void };

// The chart is a probe: `barProps` is a function of the series (as recharts' <Bar> gets it), so the probe resolves it for the first
// series, and gives every series a button that fires the `onDoubleClick` recharts would forward to its rectangles (first row as payload).
vi.mock("@mantine/charts", () => ({
  BarChart: (props: {
    data: unknown[];
    series: { name: string }[];
    type?: string;
    withLegend: boolean;
    barProps: (series: unknown) => BarProps;
    xAxisProps?: unknown;
  }) => {
    const plain = { ...props.barProps(props.series[0]) };
    delete plain.onDoubleClick;
    return (
      <div>
        <div
          data-testid="bar-chart"
          data-series={JSON.stringify(props.series)}
          data-type={props.type}
          data-legend={String(props.withLegend)}
          data-bar={JSON.stringify(plain)}
          data-tilted={String(props.xAxisProps !== undefined)}
        />
        {props.series.map((series) => (
          <button
            key={series.name}
            data-testid={`dbl-${series.name}`}
            onClick={() => props.barProps(series).onDoubleClick?.({ payload: props.data[0] }, 0, {})}
          />
        ))}
      </div>
    );
  },
}));

const row = (label: string) => ({
  id: `1:${label.length}`,
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

  test("without a handler the bars are not drillable: no pointer cursor, no double-click handler, text stays selectable", () => {
    renderWithProviders(<SprintConsistencyChart kind="scope" rows={[row("a")]} />);
    expect(JSON.parse(screen.getByTestId("bar-chart").getAttribute("data-bar")!)).toEqual({ maxBarSize: 28 });
    expect(screen.getByRole("group")).not.toHaveStyle({ userSelect: "none" });
  });

  test("a double-click reports the plotted row and the series — each segment of the stacked partition on its own", async () => {
    const onHit = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<SprintConsistencyChart kind="partition" rows={[row("abc")]} onBarDoubleClick={onHit} />);
    expect(JSON.parse(screen.getByTestId("bar-chart").getAttribute("data-bar")!)).toMatchObject({ cursor: "pointer", strokeWidth: 2 });
    expect(screen.getByRole("group")).toHaveStyle({ userSelect: "none" });
    for (const name of ["carriedOverMd", "deliveredMd", "droppedMd"]) await user.click(screen.getByTestId(`dbl-${name}`));
    expect(onHit.mock.calls.map(([hit]) => hit)).toEqual([
      { rowId: "1:3", series: "carriedOverMd" },
      { rowId: "1:3", series: "deliveredMd" },
      { rowId: "1:3", series: "droppedMd" },
    ]);
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
