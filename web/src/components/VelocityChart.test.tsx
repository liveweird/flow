import { describe, expect, test, vi } from "vitest";
import { MantineProvider } from "@mantine/core";
import { render } from "@testing-library/react";
import VelocityChart from "./VelocityChart";
import { theme } from "../theme";
import { renderWithProviders, screen, within } from "../test/render";
import type { VelocityChartRow, VelocityChartUser, VelocityUserRow } from "../utils/velocityReport";

interface TooltipElement {
  props: { label: string; payload: { name: string }[] };
}
type TooltipContent = (props: { label: string; payload: unknown[] }) => TooltipElement;

interface ChartProps {
  data: unknown;
  dataKey: string;
  series: { name: string; stackId?: string }[];
  withLegend?: boolean;
  valueFormatter: (v: number) => string;
  xAxisProps?: unknown;
  connectNulls?: boolean;
  curveType?: string;
  dotProps?: { r: number };
  barProps?: ((s: { name: string; stackId?: string }) => unknown) | unknown;
  tooltipProps?: { content: TooltipContent };
}

// recharts renders nothing under happy-dom, so each chart is a probe carrying its props; the tooltip is exercised by
// calling the content function the real chart would.
const captured = vi.hoisted(() => ({ content: undefined as TooltipContent | undefined }));
vi.mock("@mantine/charts", async () => {
  const probe = (testId: string) => (props: ChartProps) => {
    if (props.tooltipProps) captured.content = props.tooltipProps.content;
    return (
      <div
        data-testid={testId}
        data-key={props.dataKey}
        data-rows={JSON.stringify(props.data)}
        data-series={JSON.stringify(props.series)}
        data-legend={String(props.withLegend ?? false)}
        data-formatted={props.valueFormatter(12.3456)}
        data-crowded={String(props.xAxisProps !== undefined)}
        data-connect-nulls={String(props.connectNulls)}
        data-curve={props.curveType ?? ""}
        data-dot-r={String(props.dotProps?.r ?? "")}
        data-bar-props={JSON.stringify(
          typeof props.barProps === "function"
            ? Object.fromEntries(props.series.map((s) => [s.name, (props.barProps as (s: unknown) => unknown)(s)]))
            : props.barProps,
        )}
      />
    );
  };
  return { CompositeChart: probe("composite-chart"), BarChart: probe("bar-chart"), ChartTooltip: () => null };
});

const row = (label: string, over: Partial<VelocityChartRow> = {}): VelocityChartRow => ({
  label,
  initialMd: 1,
  finalMd: 2,
  initialItems: 1,
  finalItems: 2,
  capacityMd: null,
  capacitySource: null,
  ...over,
});

const attr = (testId: string, name: string) => JSON.parse(screen.getByTestId(testId).getAttribute(name)!);

describe("VelocityChart — team mode", () => {
  test("committed and final bars plus the capacity as a dashed gray line with dots, on one axis, with a legend and an accessible name", () => {
    renderWithProviders(
      <VelocityChart
        mode="team"
        rows={[row("S1", { capacityMd: 25, capacitySource: "DEFAULT" }), row("S2", { capacityMd: 12, capacitySource: "CONFIGURED" })]}
      />,
    );
    expect(screen.getByRole("group", { name: "Chart: initial and final scope by sprint" })).toBeInTheDocument();
    const chart = screen.getByTestId("composite-chart");
    expect(chart.getAttribute("data-key")).toBe("label");
    expect(attr("composite-chart", "data-series")).toEqual([
      { name: "initialMd", type: "bar", label: "Initial (committed)", color: "flow.6" },
      { name: "finalMd", type: "bar", label: "Final", color: "flow.8" },
      { name: "capacityMd", type: "line", label: "Capacity", color: "gray.6", strokeDasharray: "6 4" },
    ]);
    // A sprint with no capacity is a gap in the line, never a zero; the dots are 8 px across.
    expect(chart.getAttribute("data-connect-nulls")).toBe("false");
    expect(chart.getAttribute("data-curve")).toBe("linear");
    expect(chart.getAttribute("data-dot-r")).toBe("4");
    expect(chart.getAttribute("data-legend")).toBe("true");
    expect(chart.getAttribute("data-formatted")).toBe("12.35");
    expect(chart.getAttribute("data-crowded")).toBe("false");
    expect(screen.queryByTestId("bar-chart")).not.toBeInTheDocument();
  });

  test("the capacity series stays out when no sprint has a capacity", () => {
    renderWithProviders(<VelocityChart mode="team" rows={[row("S1"), row("S2")]} />);
    expect(attr("composite-chart", "data-series").map((s: { name: string }) => s.name)).toEqual(["initialMd", "finalMd"]);
  });

  test("the tooltip names a default capacity in words, a configured one too, and drops the capacity entry of a sprint without one", () => {
    renderWithProviders(<VelocityChart mode="team" rows={[row("S1", { capacityMd: 25, capacitySource: "DEFAULT" })]} />);
    const payload = (capacityMd: number | null, capacitySource: VelocityChartRow["capacitySource"]) => [
      { name: "initialMd", payload: row("S1", { capacityMd, capacitySource }) },
      { name: "capacityMd", payload: row("S1", { capacityMd, capacitySource }) },
    ];
    const content = captured.content!;
    expect(content({ label: "S1", payload: payload(25, "DEFAULT") }).props.label).toBe(
      "S1 · Capacity: default (members × working days)",
    );
    expect(content({ label: "S1", payload: payload(12, "CONFIGURED") }).props.label).toBe("S1 · Capacity: configured");
    const none = content({ label: "S1", payload: payload(null, null) });
    expect(none.props.label).toBe("S1");
    expect(none.props.payload.map((entry) => entry.name)).toEqual(["initialMd"]);
    expect(content({ label: "S1", payload: [] }).props.label).toBe("S1");
  });

  test("the final series switches to a lighter blue in the dark scheme (flow.8 fails 3:1 on dark paper)", () => {
    render(
      <MantineProvider env="test" theme={theme} forceColorScheme="dark">
        <VelocityChart mode="team" rows={[row("S1")]} />
      </MantineProvider>,
    );
    const series = attr("composite-chart", "data-series");
    expect(series.map((s: { color: string }) => s.color)).toEqual(["flow.6", "flow.4"]);
  });

  test("many sprints tilt the x labels instead of overlapping", () => {
    renderWithProviders(<VelocityChart mode="team" rows={Array.from({ length: 8 }, (_, i) => row(`S${i}`))} />);
    expect(screen.getByTestId("composite-chart").getAttribute("data-crowded")).toBe("true");
  });
});

describe("VelocityChart — user mode", () => {
  const users: VelocityChartUser[] = [
    { accountId: "acc-ann", label: "Ann Author", position: 1, committedKey: "c0", finalKey: "f0" },
    { accountId: "acc-bob", label: "Bob Builder", position: 2, committedKey: "c1", finalKey: "f1" },
    { accountId: "acc-cy", label: "Cy Coder", position: 3, committedKey: "c2", finalKey: "f2" },
  ];
  const rows: VelocityUserRow[] = [{ label: "S1", c0: 1, f0: 2, c1: 3, f1: 4, c2: 5, f2: 6 }];

  test("two stacks per sprint, committed then final, one segment per person in the neutral blue/gray cycle — no capacity", () => {
    renderWithProviders(<VelocityChart mode="user" users={users} rows={rows} />);
    expect(screen.getByRole("group", { name: "Chart: initial and final scope by sprint, stacked by person" })).toBeInTheDocument();
    const series = attr("bar-chart", "data-series");
    expect(series).toEqual([
      { name: "c0", label: "1. Ann Author · initial", color: "flow.6", stackId: "committed" },
      { name: "c1", label: "2. Bob Builder · initial", color: "gray.6", stackId: "committed" },
      { name: "c2", label: "3. Cy Coder · initial", color: "flow.7", stackId: "committed" },
      { name: "f0", label: "1. Ann Author · final", color: "flow.6", stackId: "final" },
      { name: "f1", label: "2. Bob Builder · final", color: "gray.6", stackId: "final" },
      { name: "f2", label: "3. Cy Coder · final", color: "flow.7", stackId: "final" },
    ]);
    expect(screen.getByTestId("bar-chart").getAttribute("data-formatted")).toBe("12.35");
    expect(screen.queryByTestId("composite-chart")).not.toBeInTheDocument();
    expect(JSON.stringify(series)).not.toContain("capacity");
  });

  test("committed is a translucent fill inside a solid edge, final a solid fill ringed in the surface", () => {
    renderWithProviders(<VelocityChart mode="user" users={users} rows={rows} />);
    const bars = attr("bar-chart", "data-bar-props");
    expect(bars.c0).toEqual({ maxBarSize: 28, fillOpacity: 0.32, strokeOpacity: 1, strokeWidth: 2 });
    expect(bars.f0).toEqual({ maxBarSize: 28, stroke: "var(--mantine-color-body)", strokeOpacity: 1, strokeWidth: 2 });
  });

  test("the legend lists every person and explains the two stacks in words; the chart's own legend is off", () => {
    renderWithProviders(<VelocityChart mode="user" users={users} rows={rows} />);
    const legend = screen.getByRole("list", { name: "Chart legend" });
    expect(within(legend).getAllByRole("listitem").map((item) => item.textContent)).toEqual([
      "1. Ann Author",
      "2. Bob Builder",
      "3. Cy Coder",
      "Initial (left column, outlined)",
      "Final (right column, solid)",
    ]);
    expect(screen.getByTestId("bar-chart").getAttribute("data-legend")).toBe("false");
  });

  test("with more people than the palette has shades, colours repeat and the numbers carry identity", () => {
    const many: VelocityChartUser[] = Array.from({ length: 8 }, (_, i) => ({
      accountId: `acc-${i}`,
      label: `Person ${i}`,
      position: i + 1,
      committedKey: `c${i}`,
      finalKey: `f${i}`,
    }));
    renderWithProviders(<VelocityChart mode="user" users={many} rows={[{ label: "S1" }]} />);
    const series: { name: string; label: string; color: string }[] = attr("bar-chart", "data-series");
    const committed = series.filter((s) => s.name.startsWith("c"));
    // Pigeonhole: fewer than eight distinct colours, yet every series label is unique and starts with its number.
    expect(new Set(committed.map((s) => s.color)).size).toBeLessThan(8);
    expect(new Set(series.map((s) => s.label)).size).toBe(16);
    expect(committed.map((s) => s.label.slice(0, 2))).toEqual(["1.", "2.", "3.", "4.", "5.", "6.", "7.", "8."]);
    const items = within(screen.getByRole("list", { name: "Chart legend" })).getAllByRole("listitem");
    expect(items.slice(0, 8).map((item) => item.textContent)).toEqual(many.map((u) => `${u.position}. ${u.label}`));
  });

  test("the two treatment entries are neutral glyphs, never a person's swatch: no chart hue, a different shape, outlined vs solid", () => {
    renderWithProviders(<VelocityChart mode="user" users={users} rows={rows} />);
    const items = within(screen.getByRole("list", { name: "Chart legend" })).getAllByRole("listitem");
    const glyph = (item: HTMLElement) => item.querySelector("[aria-hidden]") as HTMLElement;
    const [person, committed, final] = [items[0], items[3], items[4]].map(glyph);
    expect(person.style.background).toContain("--mantine-color-flow-6");
    for (const treatment of [committed, final]) {
      expect(treatment.style.border).toBe("2px solid currentcolor");
      expect(treatment.outerHTML).not.toMatch(/--mantine-color-(flow|gray)-/);
      expect(treatment.style.width).not.toBe(person.style.width);
    }
    expect(committed.style.background).toBe("transparent");
    expect(final.style.background).toBe("currentcolor");
  });

  test("one person is the same shape with one segment per stack", () => {
    renderWithProviders(<VelocityChart mode="user" users={users.slice(0, 1)} rows={[{ label: "S1", c0: 1, f0: 2 }]} />);
    expect(attr("bar-chart", "data-series").map((s: { name: string }) => s.name)).toEqual(["c0", "f0"]);
    expect(within(screen.getByRole("list", { name: "Chart legend" })).getAllByRole("listitem")).toHaveLength(3);
  });

  test("many sprints tilt the x labels", () => {
    renderWithProviders(
      <VelocityChart mode="user" users={users} rows={Array.from({ length: 8 }, (_, i) => ({ ...rows[0], label: `S${i}` }))} />,
    );
    expect(screen.getByTestId("bar-chart").getAttribute("data-crowded")).toBe("true");
  });
});
