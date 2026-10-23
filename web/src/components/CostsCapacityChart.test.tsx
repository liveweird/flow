import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import CostsCapacityChart from "./CostsCapacityChart";
import { renderWithProviders, screen, within } from "../test/render";
import type { CostsCapacityRow } from "../utils/costsSprints";

interface TooltipElement {
  props: { label: string; payload: { name: string }[] };
}
type TooltipContent = (props: { label: string; payload: unknown[] }) => TooltipElement;
interface BarProps {
  maxBarSize?: number;
  cursor?: string;
  onDoubleClick?: (entry: { payload: unknown }, index: number, event: unknown) => void;
}
interface ChartProps {
  data: unknown[];
  series: { name: string }[];
  xAxisProps?: unknown;
  valueFormatter: (v: number) => string;
  barProps: (series: { name: string }) => BarProps;
  tooltipProps: { content: TooltipContent };
}

// recharts draws nothing under happy-dom: the chart is a probe carrying its props; the bar handler and the tooltip are exercised by
// calling what the real chart would.
const captured = vi.hoisted(() => ({ content: undefined as TooltipContent | undefined }));
vi.mock("@mantine/charts", () => ({
  CompositeChart: (props: ChartProps) => {
    captured.content = props.tooltipProps.content;
    const bar = props.barProps({ name: "acMd" });
    return (
      <div>
        <div
          data-testid="composite-chart"
          data-series={JSON.stringify(props.series)}
          data-crowded={String(props.xAxisProps !== undefined)}
          data-formatted={props.valueFormatter(12.3456)}
          data-bar={JSON.stringify({ maxBarSize: bar.maxBarSize, cursor: bar.cursor ?? "" })}
        />
        <button data-testid="dbl" onClick={() => bar.onDoubleClick?.({ payload: props.data[0] }, 0, {})} />
      </div>
    );
  },
  ChartTooltip: () => null,
}));

const row = (label: string, over: Partial<CostsCapacityRow> = {}): CostsCapacityRow => ({
  id: `1:2:${label}`,
  label,
  acMd: 3,
  capacityMd: null,
  capacitySource: null,
  ...over,
});
const attr = (name: string) => JSON.parse(screen.getByTestId("composite-chart").getAttribute(name)!);

describe("CostsCapacityChart", () => {
  test("the actual-cost bar plus the capacity as a dashed line, with its own legend and an accessible name", () => {
    renderWithProviders(<CostsCapacityChart rows={[row("S1", { capacityMd: 20, capacitySource: "CALENDAR" }), row("S2")]} />);
    expect(screen.getByRole("group", { name: /chart/i })).toBeInTheDocument();
    expect(attr("data-series")).toEqual([
      expect.objectContaining({ name: "acMd", type: "bar" }),
      expect.objectContaining({ name: "capacityMd", type: "line", strokeDasharray: "6 4" }),
    ]);
    const legend = screen.getByRole("list");
    expect(within(legend).getAllByRole("listitem")).toHaveLength(2);
    expect(screen.getByTestId("composite-chart").getAttribute("data-formatted")).toBe("12.35");
    expect(screen.getByTestId("composite-chart").getAttribute("data-crowded")).toBe("false");
  });

  test("with no capacity anywhere the line and its legend entry stay out; many sprints tilt the labels", () => {
    renderWithProviders(<CostsCapacityChart rows={Array.from({ length: 7 }, (_, i) => row(`S${i}`))} />);
    expect(attr("data-series").map((s: { name: string }) => s.name)).toEqual(["acMd"]);
    expect(within(screen.getByRole("list")).getAllByRole("listitem")).toHaveLength(1);
    expect(screen.getByTestId("composite-chart").getAttribute("data-crowded")).toBe("true");
  });

  test("a bar double-click reports the sprint row and series only when a handler is given", async () => {
    const onBarDoubleClick = vi.fn();
    const { unmount } = renderWithProviders(<CostsCapacityChart rows={[row("S1")]} onBarDoubleClick={onBarDoubleClick} />);
    expect(attr("data-bar")).toEqual({ maxBarSize: 28, cursor: "pointer" });
    await userEvent.click(screen.getByTestId("dbl"));
    expect(onBarDoubleClick).toHaveBeenCalledWith({ rowId: "1:2:S1", series: "acMd" });
    unmount();

    renderWithProviders(<CostsCapacityChart rows={[row("S1")]} />);
    expect(attr("data-bar")).toEqual({ maxBarSize: 28, cursor: "" });
  });

  test("the tooltip names where the capacity comes from and drops the capacity entry of a sprint without one", () => {
    renderWithProviders(<CostsCapacityChart rows={[row("S1", { capacityMd: 20, capacitySource: "CALENDAR" })]} />);
    const payload = (r: CostsCapacityRow) => [
      { name: "acMd", payload: r },
      { name: "capacityMd", payload: r },
    ];
    const content = captured.content!;
    const withSource = content({ label: "S1", payload: payload(row("S1", { capacityMd: 20, capacitySource: "CALENDAR" })) });
    expect(withSource.props.label).toBe("S1 · Capacity: sprint calendar");
    expect(withSource.props.payload.map((entry) => entry.name)).toEqual(["acMd", "capacityMd"]);
    const none = content({ label: "S1", payload: payload(row("S1")) });
    expect(none.props.label).toBe("S1");
    expect(none.props.payload.map((entry) => entry.name)).toEqual(["acMd"]);
    expect(content({ label: "S1", payload: [] }).props.label).toBe("S1");
  });
});
