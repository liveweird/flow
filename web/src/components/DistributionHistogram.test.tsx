import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import DistributionHistogram from "./DistributionHistogram";
import type { DistributionPerson } from "../utils/distributionPeople";
import { renderWithProviders, screen } from "../test/render";

interface BarProps {
  maxBarSize?: number;
  cursor?: string;
  onDoubleClick?: (entry: { payload: unknown }, index: number, event: unknown) => void;
}

// recharts renders nothing under happy-dom: the chart is a probe carrying its props, with one button per series that fires the
// `onDoubleClick` recharts forwards to a rectangle (the last plotted row as payload).
vi.mock("@mantine/charts", () => ({
  BarChart: (props: {
    data: unknown[];
    series: { name: string }[];
    withLegend?: boolean;
    type?: string;
    xAxisProps?: unknown;
    xAxisLabel?: string;
    barProps?: BarProps | ((series: { name: string }) => BarProps);
  }) => {
    const resolve = (series: { name: string }): BarProps => (typeof props.barProps === "function" ? props.barProps(series) : (props.barProps ?? {}));
    return (
      <div>
        <div
          data-testid="bar-chart"
          data-type={props.type}
          data-rows={JSON.stringify(props.data)}
          data-series={JSON.stringify(props.series)}
          data-legend={String(props.withLegend ?? false)}
          data-x-label={props.xAxisLabel}
          data-tilted={String(props.xAxisProps !== undefined)}
        />
        {props.series.map((series) => (
          <button
            key={series.name}
            data-testid={`bar-${series.name}`}
            data-cursor={resolve(series).cursor ?? ""}
            onClick={() => resolve(series).onDoubleClick?.({ payload: props.data[props.data.length - 1] }, props.data.length - 1, {})}
          />
        ))}
      </div>
    );
  },
}));

const rows = (n: number) => Array.from({ length: n }, (_, i) => ({ id: String(i), label: `r${i}`, count: i }));

describe("DistributionHistogram", () => {
  test("one blue series of item counts, no legend, named after its panel", () => {
    renderWithProviders(<DistributionHistogram rows={rows(3)} name="Against the estimate at start" xAxisLabel="actual ÷ estimate" />);
    expect(
      screen.getByRole("group", { name: "Histogram: number of items per range — Against the estimate at start" }),
    ).toBeInTheDocument();
    const chart = screen.getByTestId("bar-chart");
    expect(JSON.parse(chart.getAttribute("data-series")!)).toEqual([{ name: "count", label: "Items", color: "flow.6" }]);
    expect(chart.getAttribute("data-legend")).toBe("false");
    expect(chart.getAttribute("data-x-label")).toBe("actual ÷ estimate");
    expect(chart.getAttribute("data-tilted")).toBe("false");
  });

  test("an open long-tail bucket is its own gray series, stacked, with a legend; the other series is absent there, never 0", () => {
    const tail = [
      { id: "0", label: "0 – 2", count: 4 },
      { id: "1", label: "2 – 4", count: 2 },
      { id: "2", label: "≥ 4", count: 3, open: true },
    ];
    renderWithProviders(<DistributionHistogram rows={tail} name="Working days" xAxisLabel="working days" />);
    const chart = screen.getByTestId("bar-chart");
    expect(JSON.parse(chart.getAttribute("data-series")!)).toEqual([
      { name: "count", label: "Items", color: "flow.6" },
      { name: "tail", label: "Long tail (top values)", color: "gray.6" },
    ]);
    expect(chart.getAttribute("data-legend")).toBe("true");
    expect(chart.getAttribute("data-type")).toBe("stacked");
    // JSON drops an undefined, which is exactly what a series a bar does not belong to must be (the tooltip lists only its own).
    expect(JSON.parse(chart.getAttribute("data-rows")!)).toEqual([
      { id: "0", label: "0 – 2", count: 4 },
      { id: "1", label: "2 – 4", count: 2 },
      { id: "2", label: "≥ 4", tail: 3 },
    ]);
  });

  describe("people mode", () => {
    const people: DistributionPerson[] = [
      { accountId: "acc-bob", label: "Bob", position: 1 },
      { accountId: "acc-ann", label: "Ann", position: 2 },
    ];
    const stacked = [
      { id: "0", label: "0 – 2", count: 4, shares: [3, 1] },
      { id: "1", label: "2 – 4", count: 2, shares: [0, 2] },
      { id: "2", label: "≥ 4", count: 3, open: true, shares: [1, 2] },
    ];

    test("one stacked, outlined series per person in the categorical palette, numbered in the legend and the tooltip, the tail bucket split too", () => {
      renderWithProviders(<DistributionHistogram rows={stacked} name="Ratio" xAxisLabel="x" people={people} />);
      expect(
        screen.getByRole("group", { name: "Histogram: number of items per range, stacked by person — Ratio" }),
      ).toBeInTheDocument();
      const chart = screen.getByTestId("bar-chart");
      expect(chart.getAttribute("data-type")).toBe("stacked");
      expect(JSON.parse(chart.getAttribute("data-series")!)).toEqual([
        { name: "p0", label: "1. Bob", color: "flow.6" },
        { name: "p1", label: "2. Ann", color: "violet.5" },
      ]);
      expect(JSON.parse(chart.getAttribute("data-rows")!)).toEqual([
        { id: "0", label: "0 – 2", p0: 3, p1: 1 },
        { id: "1", label: "2 – 4", p0: 0, p1: 2 },
        { id: "2", label: "≥ 4", p0: 1, p1: 2 },
      ]);
      // No gray "long tail" series and no chart legend of its own: the page's numbered list names the people.
      expect(chart.getAttribute("data-legend")).toBe("false");
      expect(screen.getByRole("list", { name: "Chart legend" })).toHaveTextContent("1. Bob2. Ann");
    });

    test("grouped, the people sit side by side (the chart's default type, not stacked) and the group names it; without people it changes nothing", () => {
      const { unmount } = renderWithProviders(<DistributionHistogram rows={stacked} name="Ratio" xAxisLabel="x" people={people} grouped />);
      expect(screen.getByRole("group", { name: "Histogram: number of items per range, one bar per person — Ratio" })).toBeInTheDocument();
      const chart = screen.getByTestId("bar-chart");
      expect(chart.getAttribute("data-type")).toBe("default");
      expect(JSON.parse(chart.getAttribute("data-series")!)).toHaveLength(2);
      expect(screen.getByRole("list", { name: "Chart legend" })).toHaveTextContent("1. Bob2. Ann");
      unmount();
      renderWithProviders(<DistributionHistogram rows={rows(3)} name="Ratio" xAxisLabel="x" grouped />);
      expect(screen.getByTestId("bar-chart").getAttribute("data-type")).toBe("stacked");
    });

    test("one person has the stack but no legend", () => {
      renderWithProviders(<DistributionHistogram rows={stacked} name="Ratio" xAxisLabel="x" people={people.slice(0, 1)} />);
      expect(JSON.parse(screen.getByTestId("bar-chart").getAttribute("data-series")!)).toHaveLength(1);
      expect(screen.queryByRole("list", { name: "Chart legend" })).not.toBeInTheDocument();
    });
  });

  describe("the opt-in drill", () => {
    const people: DistributionPerson[] = [
      { accountId: "acc-bob", label: "Bob", position: 1 },
      { accountId: "acc-ann", label: "Ann", position: 2 },
    ];
    const stacked = [
      { id: "0", label: "0 – 2", count: 4, shares: [3, 1] },
      { id: "1", label: "2 – 4", count: 2, shares: [0, 2] },
    ];

    test("without a handler the bars have no cursor, no handler and the labels stay selectable", () => {
      renderWithProviders(<DistributionHistogram rows={rows(3)} name="x" xAxisLabel="x" />);
      expect(screen.getByTestId("bar-count").getAttribute("data-cursor")).toBe("");
      expect(screen.getByRole("group").style.userSelect).toBe("");
    });

    test("a double-click on the whole-team bar reports the bucket's id and the count series, with a pointer cursor", async () => {
      const onHit = vi.fn();
      const user = userEvent.setup();
      renderWithProviders(<DistributionHistogram rows={rows(3)} name="x" xAxisLabel="x" onBarDoubleClick={onHit} />);
      expect(screen.getByTestId("bar-count").getAttribute("data-cursor")).toBe("pointer");
      expect(screen.getByRole("group").style.userSelect).toBe("none");
      await user.click(screen.getByTestId("bar-count"));
      expect(onHit).toHaveBeenCalledWith({ rowId: "2", series: "count" });
    });

    test("the open tail bar is its own series, so the bucket's id arrives with 'tail'", async () => {
      const onHit = vi.fn();
      const user = userEvent.setup();
      const tail = [
        { id: "0", label: "0 – 2", count: 4 },
        { id: "1", label: "≥ 2", count: 3, open: true },
      ];
      renderWithProviders(<DistributionHistogram rows={tail} name="x" xAxisLabel="x" onBarDoubleClick={onHit} />);
      await user.click(screen.getByTestId("bar-tail"));
      expect(onHit).toHaveBeenCalledWith({ rowId: "1", series: "tail" });
    });

    test("a stacked segment reports its person's series (p<i>)", async () => {
      const onHit = vi.fn();
      const user = userEvent.setup();
      renderWithProviders(<DistributionHistogram rows={stacked} name="x" xAxisLabel="x" people={people} onBarDoubleClick={onHit} />);
      await user.click(screen.getByTestId("bar-p1"));
      expect(onHit).toHaveBeenCalledWith({ rowId: "1", series: "p1" });
    });
  });

  test("many ranges tilt the x labels", () => {
    renderWithProviders(<DistributionHistogram rows={rows(8)} name="x" xAxisLabel="x" />);
    expect(screen.getByTestId("bar-chart").getAttribute("data-tilted")).toBe("true");
  });
});
