import { describe, expect, test, vi } from "vitest";
import ThroughputChart from "./ThroughputChart";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, within } from "../test/render";
import type { ThroughputPerson, ThroughputPlotRow } from "../utils/throughputReport";

interface TooltipElement {
  props: { label: string; payload: unknown[] };
}
type TooltipContent = (props: { label: string; payload: unknown[] }) => TooltipElement;

interface BarProps {
  maxBarSize?: number;
  cursor?: string;
  onDoubleClick?: (entry: { payload: unknown }, index: number, event: unknown) => void;
}

interface ChartProps {
  data: unknown;
  dataKey: string;
  series: { name: string }[];
  type?: string;
  curveType?: string;
  withLegend?: boolean;
  withDots?: boolean;
  withGradient?: boolean;
  fillOpacity?: number;
  barProps?: BarProps | ((series: { name: string }) => BarProps);
  yAxisLabel?: string;
  valueFormatter: (v: number) => string;
  xAxisProps?: unknown;
  tooltipProps: { content: TooltipContent };
}

// recharts renders nothing under happy-dom, so each chart is a probe carrying its props; the tooltip is exercised by
// calling the content function the real chart would. A bar chart's `barProps(series)` is resolved per series, and each
// series gets a button that fires the `onDoubleClick` recharts would forward to its rectangles, with the first row as payload.
const captured = vi.hoisted(() => ({ content: undefined as TooltipContent | undefined }));
vi.mock("@mantine/charts", () => {
  const resolve = (props: ChartProps, series: { name: string }): BarProps | null =>
    typeof props.barProps === "function" ? props.barProps(series) : (props.barProps ?? null);
  const probe = (testId: string) => (props: ChartProps) => {
    captured.content = props.tooltipProps.content;
    return (
      <div>
        <div
          data-testid={testId}
          data-rows={JSON.stringify(props.data)}
          data-series={JSON.stringify(props.series)}
          data-type={props.type ?? ""}
          data-curve={props.curveType ?? ""}
          data-legend={String(props.withLegend ?? false)}
          data-dots={String(props.withDots ?? false)}
          data-gradient={String(props.withGradient ?? "")}
          data-fill={String(props.fillOpacity ?? "")}
          data-bar-props={JSON.stringify(resolve(props, props.series[0]))}
          data-axis-label={props.yAxisLabel}
          data-formatted={props.valueFormatter(12.3456)}
          data-tilted={String(props.xAxisProps !== undefined)}
        />
        {props.series.map((series) => (
          <button
            key={series.name}
            data-testid={`bar-${series.name}`}
            data-cursor={resolve(props, series)?.cursor ?? ""}
            onClick={() => resolve(props, series)?.onDoubleClick?.({ payload: (props.data as unknown[])[0] }, 0, {})}
          />
        ))}
      </div>
    );
  };
  return { BarChart: probe("bar-chart"), AreaChart: probe("area-chart"), ChartTooltip: () => null };
});

const row = (label: string, over: Partial<ThroughputPlotRow> = {}): ThroughputPlotRow => ({ id: `id-${label}`, label, md: 1, items: 2, ...over });
const person = (position: number, label: string): ThroughputPerson => ({ accountId: `acc-${position}`, label, position, key: `p${position - 1}` });
const PEOPLE = [person(1, "Ann"), person(2, "Bob"), person(3, "Cy"), person(4, "Di")];
const peopleRows = [row("a", { p0: 1, p1: 2, p2: 0, p3: 0 }), row("b", { p0: 1, p1: 2, p2: 3, p3: 4 })];

const attr = (testId: string, name: string) => screen.getByTestId(testId).getAttribute(name);
const series = (testId: string) => JSON.parse(attr(testId, "data-series")!) as { name: string; label: string; color: string }[];
const tooltip = (label: string, payload: unknown[]) => captured.content!({ label, payload }).props.label;

describe("ThroughputChart — per period, whole team", () => {
  test("one teal series on one MD axis, no legend, an accessible name, and the item count in the tooltip", () => {
    renderWithProviders(<ThroughputChart scope="period" cumulative={false} rows={[row("a"), row("b")]} people={[]} />);
    expect(screen.getByRole("group", { name: "Chart: delivered scope by period" })).toBeInTheDocument();
    expect(series("bar-chart")).toEqual([{ name: "md", label: "Delivered", color: "teal.8" }]);
    expect(attr("bar-chart", "data-type")).toBe("default");
    expect(attr("bar-chart", "data-legend")).toBe("false");
    expect(attr("bar-chart", "data-axis-label")).toBe("MD");
    expect(attr("bar-chart", "data-formatted")).toBe("12.35");
    expect(JSON.parse(attr("bar-chart", "data-bar-props")!)).toEqual({ maxBarSize: 28 });
    expect(tooltip("2026-09-07", [{ payload: { items: 5 } }])).toBe("2026-09-07 · 5 items");
    expect(tooltip("x", [])).toBe("x");
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
    expect(screen.queryByTestId("area-chart")).not.toBeInTheDocument();
  });

  test("many buckets tilt the x labels; sprints tilt earlier", () => {
    const { unmount } = renderWithProviders(
      <ThroughputChart scope="period" cumulative={false} rows={Array.from({ length: 8 }, (_, i) => row(`b${i}`))} people={[]} />,
    );
    expect(attr("bar-chart", "data-tilted")).toBe("false");
    unmount();
    const nine = renderWithProviders(
      <ThroughputChart scope="period" cumulative={false} rows={Array.from({ length: 9 }, (_, i) => row(`b${i}`))} people={[]} />,
    );
    expect(attr("bar-chart", "data-tilted")).toBe("true");
    nine.unmount();
    renderWithProviders(<ThroughputChart scope="sprint" cumulative={false} rows={Array.from({ length: 7 }, (_, i) => row(`s${i}`))} people={[]} />);
    expect(attr("bar-chart", "data-tilted")).toBe("true");
  });

  test("the sprint view names itself", () => {
    renderWithProviders(<ThroughputChart scope="sprint" cumulative={false} rows={[row("Alpha 1")]} people={[]} />);
    expect(screen.getByRole("group", { name: "Chart: delivered scope by sprint" })).toBeInTheDocument();
  });
});

describe("ThroughputChart — cumulative, whole team", () => {
  test("one teal stacked area (the running total) on the same axis, no legend, the total so far in the tooltip", () => {
    renderWithProviders(<ThroughputChart scope="period" cumulative rows={[row("a"), row("b", { md: 3, items: 5 })]} people={[]} />);
    expect(screen.getByRole("group", { name: "Chart: delivered scope by period, cumulative" })).toBeInTheDocument();
    expect(screen.queryByTestId("bar-chart")).not.toBeInTheDocument();
    expect(series("area-chart")).toEqual([{ name: "md", label: "Delivered, cumulative", color: "teal.8" }]);
    expect(attr("area-chart", "data-type")).toBe("stacked");
    expect(attr("area-chart", "data-curve")).toBe("linear");
    expect(attr("area-chart", "data-legend")).toBe("false");
    expect(attr("area-chart", "data-gradient")).toBe("false");
    expect(attr("area-chart", "data-fill")).toBe("0.55");
    expect(attr("area-chart", "data-axis-label")).toBe("MD");
    expect(tooltip("b", [{ payload: { items: 5 } }])).toBe("b · 5 items so far");
  });

  test("a single slot still shows (dots), several are plain areas", () => {
    const { unmount } = renderWithProviders(<ThroughputChart scope="sprint" cumulative rows={[row("Alpha 1")]} people={[]} />);
    expect(attr("area-chart", "data-dots")).toBe("true");
    expect(screen.getByRole("group", { name: "Chart: delivered scope by sprint, cumulative" })).toBeInTheDocument();
    unmount();
    renderWithProviders(<ThroughputChart scope="sprint" cumulative rows={[row("a"), row("b")]} people={[]} />);
    expect(attr("area-chart", "data-dots")).toBe("false");
  });
});

describe("ThroughputChart — people", () => {
  test("per period: one stacked bar segment per person, numbered series labels, categorical palette colours, ringed in the surface", () => {
    renderWithProviders(<ThroughputChart scope="period" cumulative={false} rows={peopleRows} people={PEOPLE} />);
    expect(screen.getByRole("group", { name: "Chart: delivered scope by period, stacked by person" })).toBeInTheDocument();
    expect(attr("bar-chart", "data-type")).toBe("stacked");
    expect(series("bar-chart")).toEqual([
      { name: "p0", label: "1. Ann · delivered", color: "flow.6" },
      { name: "p1", label: "2. Bob · delivered", color: "violet.5" },
      { name: "p2", label: "3. Cy · delivered", color: "cyan.7" },
      { name: "p3", label: "4. Di · delivered", color: "indigo.5" },
    ]);
    expect(JSON.parse(attr("bar-chart", "data-bar-props")!)).toEqual({
      maxBarSize: 28,
      stroke: "var(--mantine-color-body)",
      strokeOpacity: 1,
      strokeWidth: 2,
    });
    // The chart's own legend stays off: the page's numbered list is the legend.
    expect(attr("bar-chart", "data-legend")).toBe("false");
    expect(tooltip("a", [{ payload: { items: 3 } }])).toBe("a · 3 items");
  });

  test("the legend numbers every person, so identity never rests on colour", () => {
    renderWithProviders(<ThroughputChart scope="period" cumulative={false} rows={peopleRows} people={PEOPLE} />);
    const legend = screen.getByRole("list", { name: "Chart legend" });
    expect(within(legend).getAllByRole("listitem").map((item) => item.textContent)).toEqual(["1. Ann", "2. Bob", "3. Cy", "4. Di"]);
  });

  test("cumulative: the same people as stacked areas of running totals", () => {
    renderWithProviders(<ThroughputChart scope="sprint" cumulative rows={peopleRows} people={PEOPLE.slice(0, 2)} />);
    expect(screen.getByRole("group", { name: "Chart: delivered scope by sprint, cumulative, stacked by person" })).toBeInTheDocument();
    expect(attr("area-chart", "data-type")).toBe("stacked");
    expect(series("area-chart").map((s) => s.label)).toEqual(["1. Ann · delivered, cumulative", "2. Bob · delivered, cumulative"]);
    expect(within(screen.getByRole("list", { name: "Chart legend" })).getAllByRole("listitem")).toHaveLength(2);
    expect(tooltip("b", [{ payload: { items: 9 } }])).toBe("b · 9 items so far");
  });

  test("one person is one stacked series with no legend list (the title and tooltip name them)", () => {
    renderWithProviders(<ThroughputChart scope="period" cumulative={false} rows={[row("a", { p0: 1 })]} people={[PEOPLE[0]]} />);
    expect(series("bar-chart")).toEqual([{ name: "p0", label: "1. Ann · delivered", color: "flow.6" }]);
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
  });
});

describe("ThroughputChart — drilling a bar", () => {
  test("a double-click on a whole-team bar reports the row id and the md series, and the bar shows a pointer", async () => {
    const onBarDoubleClick = vi.fn();
    renderWithProviders(
      <ThroughputChart scope="period" cumulative={false} rows={[row("a"), row("b")]} people={[]} onBarDoubleClick={onBarDoubleClick} />,
    );
    expect(screen.getByTestId("bar-md").getAttribute("data-cursor")).toBe("pointer");
    await userEvent.click(screen.getByTestId("bar-md"));
    expect(onBarDoubleClick).toHaveBeenCalledExactlyOnceWith({ rowId: "id-a", series: "md" });
    // The ordinary bar props are unchanged next to the handler.
    expect(JSON.parse(attr("bar-chart", "data-bar-props")!)).toEqual({ maxBarSize: 28, cursor: "pointer" });
  });

  test("each stacked segment reports its person's series key", async () => {
    const onBarDoubleClick = vi.fn();
    renderWithProviders(
      <ThroughputChart scope="period" cumulative={false} rows={peopleRows} people={PEOPLE.slice(0, 2)} onBarDoubleClick={onBarDoubleClick} />,
    );
    await userEvent.click(screen.getByTestId("bar-p1"));
    expect(onBarDoubleClick).toHaveBeenCalledExactlyOnceWith({ rowId: "id-a", series: "p1" });
  });

  test("without a handler the bars are not clickable and the props are exactly the plain ones", () => {
    renderWithProviders(<ThroughputChart scope="period" cumulative={false} rows={[row("a")]} people={[]} />);
    expect(screen.getByTestId("bar-md").getAttribute("data-cursor")).toBe("");
    expect(JSON.parse(attr("bar-chart", "data-bar-props")!)).toEqual({ maxBarSize: 28 });
  });

  test("the cumulative areas are not bars: no handler is wired", () => {
    renderWithProviders(<ThroughputChart scope="period" cumulative rows={[row("a")]} people={[]} onBarDoubleClick={vi.fn()} />);
    expect(screen.getByTestId("area-chart")).toBeInTheDocument();
    expect(screen.getByTestId("bar-md").getAttribute("data-cursor")).toBe("");
  });
});
