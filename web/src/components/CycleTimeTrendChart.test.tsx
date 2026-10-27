import type { ReactElement } from "react";
import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import CycleTimeTrendChart from "./CycleTimeTrendChart";
import { renderWithProviders, screen } from "../test/render";

type LineProps = {
  dot: (props: { cx: number; cy: number; index: number }) => ReactElement | null;
  activeDot?: { onClick: (dot: unknown, event: unknown) => void };
};
type TooltipContent = (props: { label: string; payload: unknown[] }) => { props: { label: string } };

vi.mock("@mantine/charts", () => ({
  LineChart: (props: {
    data: unknown;
    series: unknown;
    connectNulls: boolean;
    withDots: boolean;
    withLegend: boolean;
    xAxisProps?: unknown;
    lineProps?: (item: { name: string; color: string }) => LineProps;
    tooltipProps: { content: TooltipContent };
  }) => {
    const Dot = props.lineProps?.({ name: "p50", color: "flow.6" }).dot;
    const p90 = props.lineProps?.({ name: "p90", color: "gray.6" });
    return (
    <div
      data-testid="line-chart"
      data-rows={JSON.stringify(props.data)}
      data-series={JSON.stringify(props.series)}
      data-connect-nulls={String(props.connectNulls)}
      data-dots={String(props.withDots)}
      data-legend={String(props.withLegend)}
      data-tilted={String(props.xAxisProps !== undefined)}
      data-tooltip-visible={props.tooltipProps.content({ label: "a", payload: [{ payload: { n: 6 } }] }).props.label}
      data-tooltip-hidden={props.tooltipProps.content({ label: "b", payload: [] }).props.label}
      data-tooltip-empty-bucket={props.tooltipProps.content({ label: "e", payload: [] }).props.label}
      data-tooltip-unknown={props.tooltipProps.content({ label: "x", payload: [] }).props.label}
      data-custom-dots={String(Dot !== undefined)}
    >
      {Dot && (
        <svg>
          <Dot cx={1} cy={2} index={0} />
          <Dot cx={3} cy={4} index={1} />
        </svg>
      )}
      <button data-testid="active-p90" onClick={() => p90?.activeDot?.onClick({ payload: { label: "b" } }, {})} />
      <button data-testid="active-p90-gap" onClick={() => p90?.activeDot?.onClick({ payload: { label: "c" } }, {})} />
    </div>
    );
  },
  ChartTooltip: () => null,
}));

const row = (label: string, p50: number | null = 1, p90: number | null = 2) => ({ label, p50, p90, n: 6 });

describe("CycleTimeTrendChart", () => {
  test("median solid blue, p90 dashed gray — both ≥ 3:1 — with a legend, dots, and lines that BREAK at gaps", () => {
    renderWithProviders(<CycleTimeTrendChart rows={[row("a"), row("b", null, null), row("c")]} />);
    expect(screen.getByRole("group", { name: "Chart: median and p90 cycle time in working days by period" })).toBeInTheDocument();
    const chart = screen.getByTestId("line-chart");
    expect(JSON.parse(chart.getAttribute("data-series")!)).toEqual([
      { name: "p50", label: "Median (p50)", color: "flow.6" },
      { name: "p90", label: "p90", color: "gray.6", strokeDasharray: "6 4" },
    ]);
    // Gaps: the hidden bucket's values are null (never 0) and the line is not bridged over it.
    expect(chart.getAttribute("data-connect-nulls")).toBe("false");
    expect(JSON.parse(chart.getAttribute("data-rows")!)[1]).toMatchObject({ label: "b", p50: null, p90: null });
    expect(chart.getAttribute("data-dots")).toBe("true");
    expect(chart.getAttribute("data-legend")).toBe("true");
  });

  test("the tooltip names the bucket's item count — and, for a hidden bucket, why it has no value", () => {
    renderWithProviders(
      <CycleTimeTrendChart rows={[row("a"), { label: "b", p50: null, p90: null, n: 3 }, { label: "e", p50: null, p90: null, n: 0 }]} />,
    );
    const chart = screen.getByTestId("line-chart");
    expect(chart.getAttribute("data-tooltip-visible")).toBe("a · 6 items");
    // The hidden bucket arrives with an EMPTY payload; the row is found by its label.
    expect(chart.getAttribute("data-tooltip-hidden")).toBe("b · 3 items, below the minimum sample");
    expect(chart.getAttribute("data-tooltip-empty-bucket")).toBe("e · 0 items");
    expect(chart.getAttribute("data-tooltip-unknown")).toBe("x");
  });

  test("a plain cycle-time trend keeps the library's dots; Person trend's rows marked partial draw hollow ones and say so in the tooltip", () => {
    const { unmount } = renderWithProviders(<CycleTimeTrendChart rows={[row("a"), row("b")]} />);
    expect(screen.getByTestId("line-chart").getAttribute("data-custom-dots")).toBe("false");
    unmount();
    renderWithProviders(<CycleTimeTrendChart rows={[{ ...row("a"), partial: true }, row("b")]} />);
    const chart = screen.getByTestId("line-chart");
    expect(chart.getAttribute("data-custom-dots")).toBe("true");
    const dots = chart.querySelectorAll("circle");
    expect(dots[0]).toHaveAttribute("data-partial", "true");
    expect(dots[1]).not.toHaveAttribute("data-partial");
    expect(chart.getAttribute("data-tooltip-visible")).toBe("a · 6 items, partial");
  });

  test("many buckets tilt the x labels", () => {
    renderWithProviders(<CycleTimeTrendChart rows={Array.from({ length: 9 }, (_, i) => row(`b${i}`))} />);
    expect(screen.getByTestId("line-chart").getAttribute("data-tilted")).toBe("true");
  });

  describe("clicking a point (Person trend's drill)", () => {
    const rows = [row("a"), row("b"), { label: "c", p50: 3, p90: null, n: 3 }];

    test("a dot of the median line and the hovered dot of the p90 line report their row and the line they are on; a gap reports nothing", async () => {
      const onPointClick = vi.fn();
      const user = userEvent.setup();
      renderWithProviders(<CycleTimeTrendChart rows={rows} onPointClick={onPointClick} />);
      const chart = screen.getByTestId("line-chart");
      expect(chart.getAttribute("data-custom-dots")).toBe("true");
      const dots = chart.querySelectorAll("circle");
      await user.click(dots[1]);
      await user.click(screen.getByTestId("active-p90"));
      expect(onPointClick.mock.calls).toEqual([[{ label: "b", series: "p50" }], [{ label: "b", series: "p90" }]]);
      // Row c has no p90 (withheld): its active dot is a gap on that line.
      await user.click(screen.getByTestId("active-p90-gap"));
      expect(onPointClick).toHaveBeenCalledTimes(2);
    });

    test("without a handler the dots are the library's own and nothing is clickable", () => {
      renderWithProviders(<CycleTimeTrendChart rows={rows} />);
      expect(screen.getByTestId("line-chart").getAttribute("data-custom-dots")).toBe("false");
    });
  });
});
