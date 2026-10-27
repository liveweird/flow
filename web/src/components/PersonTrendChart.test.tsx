import type { ReactElement } from "react";
import { describe, expect, test, vi } from "vitest";
import PersonTrendChart from "./PersonTrendChart";
import type { TrendChartPoint } from "../utils/personTrendReport";
import { formatDays } from "../utils/reportFormat";
import { renderWithProviders, screen } from "../test/render";
import userEvent from "@testing-library/user-event";

type TooltipContent = (props: { label: string; payload: unknown[] }) => { props: { label: string } };
type DotComponent = (props: { cx?: number; cy?: number; index: number }) => ReactElement | null;

// recharts renders nothing under happy-dom: the chart is a probe carrying the props the component decides.
vi.mock("@mantine/charts", () => ({
  LineChart: (props: {
    data: unknown;
    series: unknown;
    connectNulls: boolean;
    withLegend?: boolean;
    yAxisLabel: string;
    lineProps: { dot: DotComponent; activeDot?: { onClick: (dot: unknown, event: unknown) => void } };
    yAxisProps?: { domain: number[] };
    tooltipProps: { content: TooltipContent };
  }) => {
    const Dot = props.lineProps.dot;
    return (
      <div
        data-testid="line-chart"
        data-rows={JSON.stringify(props.data)}
        data-series={JSON.stringify(props.series)}
        data-connect-nulls={String(props.connectNulls)}
        data-legend={String(props.withLegend ?? false)}
        data-axis={props.yAxisLabel}
        data-domain={JSON.stringify(props.yAxisProps?.domain ?? null)}
        data-tooltip-full={props.tooltipProps.content({ label: "2026-08-11", payload: [] }).props.label}
        data-tooltip-partial={props.tooltipProps.content({ label: "2026-07-14", payload: [] }).props.label}
        data-tooltip-hidden={props.tooltipProps.content({ label: "2026-08-25", payload: [] }).props.label}
        data-tooltip-gap={props.tooltipProps.content({ label: "2026-06-30", payload: [] }).props.label}
        data-tooltip-unknown={props.tooltipProps.content({ label: "x", payload: [] }).props.label}
      >
        <svg>
          <Dot cx={1} cy={2} index={1} />
          <Dot cx={3} cy={4} index={2} />
          <Dot index={3} />
        </svg>
        <button data-testid="active" onClick={() => props.lineProps.activeDot?.onClick({ payload: { label: "2026-08-11" } }, {})} />
        <button data-testid="active-gap" onClick={() => props.lineProps.activeDot?.onClick({ payload: { label: "2026-08-25" } }, {})} />
        <button data-testid="active-unknown" onClick={() => props.lineProps.activeDot?.onClick({ payload: { label: "x" } }, {})} />
      </div>
    );
  },
  ChartTooltip: () => null,
}));

const points: TrendChartPoint[] = [
  { label: "2026-06-30", value: null, n: 0, hidden: false, coverage: "NONE" },
  { label: "2026-07-14", value: 6.5, n: 8, hidden: false, coverage: "PARTIAL" },
  { label: "2026-08-11", value: 5, n: 9, hidden: false, coverage: "FULL" },
  { label: "2026-08-25", value: null, n: 3, hidden: true, coverage: "FULL" },
];

describe("PersonTrendChart", () => {
  function chart() {
    return renderWithProviders(<PersonTrendChart points={points} seriesLabel="Cycle time" axisLabel="Days" format={formatDays} minSampleSize={5} />);
  }

  test("one series, no legend, a unit on the axis; gaps BREAK the line and hidden or empty windows are null, never a zero", () => {
    chart();
    expect(screen.getByRole("group", { name: "Cycle time" })).toBeInTheDocument();
    const el = screen.getByTestId("line-chart");
    expect(el).toHaveAttribute("data-connect-nulls", "false");
    expect(el).toHaveAttribute("data-legend", "false");
    expect(el).toHaveAttribute("data-axis", "Days");
    expect(JSON.parse(el.getAttribute("data-series")!)).toEqual([{ name: "value", label: "Cycle time", color: "flow.6" }]);
    expect(JSON.parse(el.getAttribute("data-rows")!).map((row: { value: number | null }) => row.value)).toEqual([null, 6.5, 5, null]);
  });

  test("a share is drawn on a fixed 0..100% axis (an all-zero line does not stretch it); other units keep the library's own range", () => {
    const { unmount } = renderWithProviders(
      <PersonTrendChart points={points} seriesLabel="Share" axisLabel="Share" format={formatDays} minSampleSize={5} domain={[0, 1]} />,
    );
    expect(screen.getByTestId("line-chart")).toHaveAttribute("data-domain", "[0,1]");
    unmount();
    chart();
    expect(screen.getByTestId("line-chart")).toHaveAttribute("data-domain", "null");
  });

  test("the tooltip names the window's sample and says partial, withheld or no data — the shape of the dot is never the only cue", () => {
    chart();
    const el = screen.getByTestId("line-chart");
    expect(el).toHaveAttribute("data-tooltip-full", "2026-08-11 · n = 9");
    expect(el).toHaveAttribute("data-tooltip-partial", "2026-07-14 · n = 8, partial");
    expect(el).toHaveAttribute("data-tooltip-hidden", "2026-08-25 · n = 3, below the minimum of 5");
    expect(el).toHaveAttribute("data-tooltip-gap", "2026-06-30 · no data derived");
    expect(el).toHaveAttribute("data-tooltip-unknown", "x");
  });

  test("a point in a partial window is a hollow dot, the others filled", () => {
    chart();
    const dots = screen.getByTestId("line-chart").querySelectorAll("circle");
    expect(dots[0]).toHaveAttribute("data-partial", "true");
    expect(dots[1]).not.toHaveAttribute("data-partial");
  });

  describe("clicking a point", () => {
    function clickable(onPointClick: (end: string) => void) {
      return renderWithProviders(
        <PersonTrendChart points={points} seriesLabel="Cycle time" axisLabel="Days" format={formatDays} minSampleSize={5} onPointClick={onPointClick} />,
      );
    }

    test("a dot — hollow or filled — and the hovered (active) dot report the window end; a point with no figure (a gap, a withheld figure) reports nothing", async () => {
      const onPointClick = vi.fn();
      const user = userEvent.setup();
      clickable(onPointClick);
      const dots = screen.getByTestId("line-chart").querySelectorAll("circle");
      await user.click(dots[0]);
      await user.click(dots[1]);
      await user.click(screen.getByTestId("active"));
      expect(onPointClick.mock.calls).toEqual([["2026-07-14"], ["2026-08-11"], ["2026-08-11"]]);
      await user.click(screen.getByTestId("active-gap"));
      await user.click(screen.getByTestId("active-unknown"));
      expect(onPointClick).toHaveBeenCalledTimes(3);
    });

    test("without a handler no dot is a pointer target and there is no active-dot override", () => {
      chart();
      expect(screen.getByTestId("line-chart").querySelector("circle")).not.toHaveStyle({ cursor: "pointer" });
    });
  });
});
