import type { ReactElement, ReactNode } from "react";
import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import AgingScatterChart from "./AgingScatterChart";
import { renderWithProviders, screen, within } from "../test/render";
import { AGING_BOARD, AGING_HIDDEN, AGING_UNIT } from "../test/reportFixtures";
import { agingPlot } from "../utils/agingChart";

interface Point {
  x: number;
  y: number;
  i: number;
}
interface ProbeProps {
  h: number;
  data: { name: string; color: string; data: Point[] }[];
  xAxisLabel: string;
  yAxisLabel: string;
  withLegend: boolean;
  xAxisProps: { domain: number[]; ticks: number[]; interval: number; tickFormatter: (value: number) => string };
  yAxisProps: { domain: number[]; scale?: string; ticks?: number[] };
  referenceLines: { y: number; color: string; strokeDasharray: string; label: { value: string; fill: string } }[];
  scatterProps: { shape: (props: { cx?: number; cy?: number; payload?: Point }) => ReactElement };
  tooltipProps: { content: (props: { payload?: { payload: Point }[] }) => ReactNode };
}

// recharts draws nothing under happy-dom (no layout): the chart is a probe carrying its props, and drawing each point's
// custom shape inside an <svg> so the marks themselves can be asserted.
vi.mock("@mantine/charts", () => ({
  ScatterChart: (props: ProbeProps) => (
    <div
      data-testid="scatter"
      data-points={JSON.stringify(props.data[0].data)}
      data-ticks={JSON.stringify(props.xAxisProps.ticks)}
      data-tick-labels={JSON.stringify(props.xAxisProps.ticks.map((tick) => props.xAxisProps.tickFormatter(tick)))}
      data-x-domain={JSON.stringify(props.xAxisProps.domain)}
      data-y-domain={JSON.stringify(props.yAxisProps.domain)}
      data-y-scale={props.yAxisProps.scale ?? "linear"}
      data-y-ticks={JSON.stringify(props.yAxisProps.ticks ?? null)}
      data-lines={JSON.stringify(props.referenceLines)}
      data-x-label={props.xAxisLabel}
      data-y-label={props.yAxisLabel}
      data-legend={String(props.withLegend)}
    >
      <svg>
        {props.data[0].data.map((point) => (
          <g key={point.i} data-point={point.i}>
            {props.scatterProps.shape({ cx: 10 + point.i, cy: 20, payload: point })}
          </g>
        ))}
      </svg>
      {props.data[0].data.map((point) => (
        <div key={point.i} data-testid={`tooltip-${point.i}`}>
          {props.tooltipProps.content({ payload: [{ payload: point }] })}
        </div>
      ))}
      <div data-testid="tooltip-empty">{props.tooltipProps.content({ payload: [] })}</div>
    </div>
  ),
}));

const probe = () => screen.getByTestId("scatter");
const json = (name: string) => JSON.parse(probe().getAttribute(name)!);
const dotOf = (container: HTMLElement, key: string) => container.querySelector(`[data-item="${key}"]`) as SVGGElement;

describe("AgingScatterChart", () => {
  test("one data point per listed item at its category and age, with the position names as the x ticks", () => {
    const plot = agingPlot(AGING_UNIT, "TASK");
    renderWithProviders(<AgingScatterChart plot={plot} thresholds={AGING_UNIT.thresholds} selectedKey={null} onSelect={() => {}} />);
    expect(json("data-points")).toEqual(plot.points);
    expect(json("data-points").map((point: Point) => point.y)).toEqual([30, 15.5, 9, 6]);
    expect(json("data-ticks")).toEqual([0, 1, 2]);
    expect(json("data-tick-labels")).toEqual(["In progress", "In review", "On hold"]);
    expect(json("data-x-domain")).toEqual([-0.5, 2.5]);
    expect(probe()).toHaveAttribute("data-x-label", "Workflow position");
    expect(probe()).toHaveAttribute("data-y-label", "Age (working days)");
    expect(probe()).toHaveAttribute("data-legend", "false");
    expect(screen.getByRole("group", { name: /Chart: age in working days of each item in progress/ })).toBeInTheDocument();
  });

  test("the y axis starts at 0 and reaches the oldest item, however far it is above p95", () => {
    const outlier = { ...AGING_UNIT, items: AGING_UNIT.items.map((item) => (item.issueKey === "FLO-1" ? { ...item, ageWorkingDays: 400 } : item)) };
    renderWithProviders(<AgingScatterChart plot={agingPlot(outlier, "TASK")} thresholds={AGING_UNIT.thresholds} selectedKey={null} onSelect={() => {}} />);
    const [low, high] = json("data-y-domain");
    expect(low).toBe(0);
    expect(high).toBeGreaterThanOrEqual(400);
  });

  test("a dashed line per threshold labelled with the percentile and its value, in the band colours; none when hidden", () => {
    const view = renderWithProviders(
      <AgingScatterChart plot={agingPlot(AGING_UNIT, "TASK")} thresholds={AGING_UNIT.thresholds} selectedKey={null} onSelect={() => {}} />,
    );
    expect(json("data-lines")).toMatchObject([
      { y: 8, color: "gray.6", strokeDasharray: "6 4", label: { value: "p50 · 8 wd", fill: "currentColor" } },
      { y: 15, color: "orange.8", label: { value: "p85 · 15 wd" } },
      { y: 25, color: "red.7", label: { value: "p95 · 25 wd" } },
    ]);
    view.unmount();
    renderWithProviders(<AgingScatterChart plot={agingPlot(AGING_HIDDEN, "TASK")} thresholds={AGING_HIDDEN.thresholds} selectedKey={null} onSelect={() => {}} />);
    expect(json("data-lines")).toEqual([]);
    expect(json("data-points")).toHaveLength(4); // dots still render
  });

  test("the dot wears the SERVER's band by rank, waiting is hollow, blocked is ringed — and a band-less dot is gray", () => {
    const { container } = renderWithProviders(
      <AgingScatterChart plot={agingPlot(AGING_UNIT, "TASK")} thresholds={AGING_UNIT.thresholds} selectedKey={null} onSelect={() => {}} />,
    );
    // FLO-1: P95 (red), blocked, solid.
    expect(dotOf(container, "TASK:FLO-1")).toHaveAttribute("data-tone", "red");
    expect(dotOf(container, "TASK:FLO-1").querySelector('[data-mark="solid"]')).toHaveAttribute("fill", "var(--mantine-color-red-7)");
    expect(dotOf(container, "TASK:FLO-1").querySelector('[data-mark="blocked"]')).toHaveAttribute("stroke", "var(--mantine-color-red-7)");
    // FLO-2: P85 (orange), neither waiting nor blocked.
    expect(dotOf(container, "TASK:FLO-2")).toHaveAttribute("data-tone", "orange");
    expect(dotOf(container, "TASK:FLO-2").querySelector('[data-mark="blocked"]')).toBeNull();
    expect(dotOf(container, "TASK:FLO-2").querySelector('[data-mark="waiting"]')).toBeNull();
    // FLO-3: P50 (gray) and WAITING: hollow — a body-coloured fill with a gray outline.
    const waiting = dotOf(container, "TASK:FLO-3").querySelector('[data-mark="waiting"]');
    expect(waiting).toHaveAttribute("fill", "var(--mantine-color-body)");
    expect(waiting).toHaveAttribute("stroke", "var(--mantine-color-gray-6)");
    expect(dotOf(container, "TASK:FLO-3").querySelector('[data-mark="solid"]')).toBeNull();
    // FLO-4: WITHIN (gray).
    expect(dotOf(container, "TASK:FLO-4")).toHaveAttribute("data-tone", "gray");
  });

  test("a BLOCKED-stage item is ringed as blocked and drawn solid, never hollow like a waiting one (A36)", () => {
    const held = {
      ...AGING_UNIT.items[2], issueKey: "FLO-9", blocked: true, waiting: false, stage: "BLOCKED" as const, statusId: "s-prog", position: "s-prog",
    };
    const report = { ...AGING_UNIT, items: [...AGING_UNIT.items, held] };
    const { container } = renderWithProviders(
      <AgingScatterChart plot={agingPlot(report, "TASK")} thresholds={report.thresholds} selectedKey={null} onSelect={() => {}} />,
    );
    const dot = dotOf(container, "TASK:FLO-9");
    expect(dot.querySelector('[data-mark="blocked"]')).toHaveAttribute("stroke", "var(--mantine-color-red-7)");
    expect(dot.querySelector('[data-mark="solid"]')).not.toBeNull();
    expect(dot.querySelector('[data-mark="waiting"]')).toBeNull();
  });

  test("hidden thresholds: every dot is gray, since no band was sent", () => {
    const { container } = renderWithProviders(
      <AgingScatterChart plot={agingPlot(AGING_HIDDEN, "TASK")} thresholds={AGING_HIDDEN.thresholds} selectedKey={null} onSelect={() => {}} />,
    );
    expect([...container.querySelectorAll("[data-item]")].map((dot) => dot.getAttribute("data-tone"))).toEqual(["gray", "gray", "gray", "gray"]);
  });

  test("a click on a dot selects it by key; the selected dot carries a ring", async () => {
    const onSelect = vi.fn();
    const view = renderWithProviders(
      <AgingScatterChart plot={agingPlot(AGING_UNIT, "TASK")} thresholds={AGING_UNIT.thresholds} selectedKey={null} onSelect={onSelect} />,
    );
    expect(view.container.querySelector('[data-mark="selected"]')).toBeNull();
    await userEvent.click(dotOf(view.container, "TASK:FLO-2"));
    expect(onSelect).toHaveBeenCalledWith("TASK:FLO-2");
    view.unmount();
    const selected = renderWithProviders(
      <AgingScatterChart plot={agingPlot(AGING_UNIT, "TASK")} thresholds={AGING_UNIT.thresholds} selectedKey="TASK:FLO-3" onSelect={onSelect} />,
    );
    expect(dotOf(selected.container, "TASK:FLO-3").querySelector('[data-mark="selected"]')).not.toBeNull();
    expect(dotOf(selected.container, "TASK:FLO-2").querySelector('[data-mark="selected"]')).toBeNull();
  });

  test("the tooltip names key, summary, position, age, band, assignee, waiting and blocked in words", () => {
    renderWithProviders(<AgingScatterChart plot={agingPlot(AGING_UNIT, "TASK")} thresholds={AGING_UNIT.thresholds} selectedKey={null} onSelect={() => {}} />);
    const blocked = within(screen.getByTestId("tooltip-0"));
    expect(blocked.getByText("FLO-1")).toBeInTheDocument();
    expect(blocked.getByText("Stuck on review")).toBeInTheDocument();
    expect(blocked.getByText("Position: In review")).toBeInTheDocument();
    expect(blocked.getByText("Age (working days): 30")).toBeInTheDocument();
    expect(blocked.getByText("Band: Above p95")).toBeInTheDocument();
    expect(blocked.getByText("Assignee: Ann Author")).toBeInTheDocument();
    expect(blocked.getByText("Blocked")).toBeInTheDocument();
    expect(within(screen.getByTestId("tooltip-2")).getByText("Waiting")).toBeInTheDocument();
    expect(within(screen.getByTestId("tooltip-3")).getByText("Band: Within")).toBeInTheDocument();
    // A hovered nothing renders nothing.
    expect(screen.getByTestId("tooltip-empty")).toBeEmptyDOMElement();
  });

  test("a hidden-threshold item's tooltip has no band", () => {
    renderWithProviders(<AgingScatterChart plot={agingPlot(AGING_HIDDEN, "TASK")} thresholds={AGING_HIDDEN.thresholds} selectedKey={null} onSelect={() => {}} />);
    expect(within(screen.getByTestId("tooltip-0")).getByText("Band: —")).toBeInTheDocument();
  });

  test("with many categories the tick labels are shortened — the tooltip and the table keep the full name", () => {
    const long = "A very long board column name";
    const positions = Array.from({ length: 8 }, (_, n) => ({ key: `c${n}`, label: n === 0 ? long : `Column ${n}`, kind: "COLUMN" as const }));
    const many = { ...AGING_BOARD, positions, items: positions.map((p, n) => ({ ...AGING_BOARD.items[1], issueKey: `X-${n}`, position: p.key })) };
    renderWithProviders(<AgingScatterChart plot={agingPlot(many, "TASK")} thresholds={AGING_UNIT.thresholds} selectedKey={null} onSelect={() => {}} />);
    expect(json("data-tick-labels").slice(0, 2)).toEqual(["A very long…", "Column 1"]);
  });

  describe("log scale", () => {
    /** A young item (0 days) and a fraction under the floor next to the old ones, as real data has. */
    const YOUNG = {
      ...AGING_UNIT,
      items: AGING_UNIT.items.map((item) =>
        item.issueKey === "FLO-4" ? { ...item, ageWorkingDays: 0 } : item.issueKey === "FLO-3" ? { ...item, ageWorkingDays: 0.3 } : item,
      ),
    };
    const renderLog = (logScale: boolean) =>
      renderWithProviders(<AgingScatterChart plot={agingPlot(YOUNG, "TASK")} thresholds={YOUNG.thresholds} selectedKey={null} onSelect={() => {}} logScale={logScale} />);

    test("linear by default: no log scale, the 0-based domain and the plain axis label", () => {
      renderLog(false);
      expect(probe()).toHaveAttribute("data-y-scale", "linear");
      expect(json("data-y-ticks")).toBeNull();
      expect(json("data-y-domain")[0]).toBe(0);
      expect(json("data-points").map((point: Point) => point.y)).toEqual([30, 15.5, 0.3, 0]);
      expect(probe()).toHaveAttribute("data-y-label", "Age (working days)");
    });

    test("log: scale=log, a positive domain from the floor to the oldest item, readable ticks, a label naming the scale", () => {
      renderLog(true);
      expect(probe()).toHaveAttribute("data-y-scale", "log");
      expect(probe()).toHaveAttribute("data-y-label", "Age (working days, log scale)");
      const [low, high] = json("data-y-domain");
      expect(low).toBe(0.5);
      expect(high).toBeGreaterThanOrEqual(30);
      expect(json("data-y-ticks")).toEqual([0.5, 1, 2, 5, 10, 20]);
    });

    test("the floor lifts zero and sub-floor ages in the plot only; the tooltip keeps the true age and says so", () => {
      renderLog(true);
      expect(json("data-points").map((point: Point) => point.y)).toEqual([30, 15.5, 0.5, 0.5]);
      const fraction = within(screen.getByTestId("tooltip-2"));
      expect(fraction.getByText("Age (working days): 0.3")).toBeInTheDocument();
      expect(fraction.getByText("Under half a day: plotted on the 0.5 line")).toBeInTheDocument();
      const zero = within(screen.getByTestId("tooltip-3"));
      expect(zero.getByText("Age (working days): 0")).toBeInTheDocument();
      expect(within(screen.getByTestId("tooltip-0")).queryByText(/plotted on the 0.5 line/)).not.toBeInTheDocument();
    });

    test("the reference lines stay in both modes, a zero-valued one floored on the log axis only", () => {
      const view = renderLog(false);
      expect(json("data-lines").map((line: { y: number }) => line.y)).toEqual([8, 15, 25]);
      view.unmount();
      const zeroed = { ...YOUNG.thresholds, percentiles: [{ percentile: 50, workingDays: 0 }, ...YOUNG.thresholds.percentiles.slice(1)] };
      renderWithProviders(<AgingScatterChart plot={agingPlot(YOUNG, "TASK")} thresholds={zeroed} selectedKey={null} onSelect={() => {}} logScale />);
      expect(json("data-lines").map((line: { y: number; label: { value: string } }) => [line.y, line.label.value])).toEqual([
        [0.5, "p50 · 0 wd"],
        [15, "p85 · 15 wd"],
        [25, "p95 · 25 wd"],
      ]);
    });

    test("an outlier is never clipped on the log axis either", () => {
      const outlier = { ...AGING_UNIT, items: AGING_UNIT.items.map((item) => (item.issueKey === "FLO-1" ? { ...item, ageWorkingDays: 742 } : item)) };
      renderWithProviders(<AgingScatterChart plot={agingPlot(outlier, "TASK")} thresholds={AGING_UNIT.thresholds} selectedKey={null} onSelect={() => {}} logScale />);
      expect(json("data-y-domain")[1]).toBeGreaterThanOrEqual(742);
      expect(json("data-y-ticks")).toEqual([0.5, 1, 2, 5, 10, 20, 50, 100, 200, 500]);
    });
  });
});
