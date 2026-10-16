import type { ReactElement } from "react";
import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import AgingChartSection from "./AgingChartSection";
import { renderWithProviders, screen, within } from "../test/render";
import { AGING_HIDDEN, AGING_TRUNCATED, AGING_UNIT } from "../test/reportFixtures";
import type { AgingWipReport } from "../api/reports";

interface ProbeProps {
  data: { data: { x: number; y: number; i: number }[] }[];
  xAxisProps: { ticks: number[]; tickFormatter: (value: number) => string };
  referenceLines: { y: number; label: { value: string } }[];
  yAxisProps: { scale?: string };
  scatterProps: { shape: (props: { cx: number; cy: number; payload: { x: number; y: number; i: number } }) => ReactElement };
}

// recharts draws nothing under happy-dom: a probe carrying the chart's props.
vi.mock("@mantine/charts", () => ({
  ScatterChart: (props: ProbeProps) => (
    <div
      data-testid="scatter"
      data-count={props.data[0].data.length}
      data-y-scale={props.yAxisProps.scale ?? "linear"}
      data-ages={props.data[0].data.map((point) => point.y).join(",")}
      data-labels={props.xAxisProps.ticks.map((tick) => props.xAxisProps.tickFormatter(tick)).join("|")}
      data-lines={props.referenceLines.map((line) => line.label.value).join("|")}
    />
  ),
}));

/** Epics with visible thresholds of their own (3 / 5 / 8 working days) — a different scale from the tasks' 8 / 15 / 25. */
const WITH_EPIC_THRESHOLDS: AgingWipReport = {
  ...AGING_UNIT,
  epicThresholds: { n: 9, hidden: false, percentiles: [{ percentile: 50, workingDays: 3 }, { percentile: 85, workingDays: 5 }, { percentile: 95, workingDays: 8 }] },
  items: AGING_UNIT.items.map((item) => (item.itemKind === "EPIC" ? { ...item, band: "P95" } : item)),
};

const noop = () => {};
const probe = () => screen.findByTestId("scatter");

describe("AgingChartSection", () => {
  test("tasks by default, with the task thresholds; the Epics segment swaps in the epics and THEIR thresholds", async () => {
    renderWithProviders(<AgingChartSection report={WITH_EPIC_THRESHOLDS} selectedKey={null} onSelect={noop} />);
    const tasks = await probe();
    expect(tasks).toHaveAttribute("data-count", "4");
    expect(tasks).toHaveAttribute("data-lines", "p50 · 8 wd|p85 · 15 wd|p95 · 25 wd");
    expect(tasks).toHaveAttribute("data-labels", "In progress|In review|On hold");

    await userEvent.click(screen.getByRole("radio", { name: "Epics" }));
    const epics = await probe();
    expect(epics).toHaveAttribute("data-count", "1");
    expect(epics).toHaveAttribute("data-ages", "40");
    expect(epics).toHaveAttribute("data-lines", "p50 · 3 wd|p85 · 5 wd|p95 · 8 wd");
    expect(epics).toHaveAttribute("data-labels", "Epic doing");

    await userEvent.click(screen.getByRole("radio", { name: "Tasks" }));
    expect(await probe()).toHaveAttribute("data-count", "4");
  });

  test("the switch exists only when the list holds both kinds", async () => {
    renderWithProviders(<AgingChartSection report={AGING_HIDDEN} selectedKey={null} onSelect={noop} />);
    await probe();
    expect(screen.queryByRole("radiogroup")).not.toBeInTheDocument();
    expect(screen.getByRole("list", { name: "Dot legend" })).toBeInTheDocument();
  });

  test("hidden thresholds: the note says why, no line is drawn, the dots still render", async () => {
    renderWithProviders(<AgingChartSection report={AGING_HIDDEN} selectedKey={null} onSelect={noop} />);
    const chart = await probe();
    expect(chart).toHaveAttribute("data-lines", "");
    expect(chart).toHaveAttribute("data-count", "4");
    expect(screen.getByRole("note")).toHaveTextContent(/Only 3 finished items in the window/);
    const legend = within(screen.getByRole("list", { name: "Dot legend" }));
    expect(legend.getByText("No band (thresholds hidden)")).toBeInTheDocument();
    expect(legend.queryByText(/^Above/)).not.toBeInTheDocument();
  });

  test("the legend names the bands by rank and the two marker details in words", async () => {
    renderWithProviders(<AgingChartSection report={AGING_UNIT} selectedKey={null} onSelect={noop} />);
    await probe();
    const items = within(screen.getByRole("list", { name: "Dot legend" })).getAllByRole("listitem");
    expect(items.map((item) => item.textContent)).toEqual([
      "Above p95",
      "Above p85",
      "At or below p85",
      "Hollow dot: waiting",
      "Outer red ring: blocked",
    ]);
  });

  test("an epics-only list opens on the epics without a switch", async () => {
    const epicsOnly = { ...AGING_UNIT, items: AGING_UNIT.items.filter((item) => item.itemKind === "EPIC") };
    renderWithProviders(<AgingChartSection report={epicsOnly} selectedKey={null} onSelect={noop} />);
    expect(await probe()).toHaveAttribute("data-count", "1");
    expect(screen.queryByRole("radiogroup")).not.toBeInTheDocument();
  });

  test("a truncated list is plotted as listed and carries the truncation note", async () => {
    renderWithProviders(<AgingChartSection report={AGING_TRUNCATED} selectedKey={null} onSelect={noop} />);
    expect(await probe()).toHaveAttribute("data-count", "4");
    expect(screen.getByText("Showing the oldest 5 items; more match. Narrow the team or the domain to see the rest.")).toBeInTheDocument();
  });

  test("the log scale is a Switch, off by default; toggling it sets the log axis, the note and the remembered choice", async () => {
    renderWithProviders(<AgingChartSection report={AGING_UNIT} selectedKey={null} onSelect={noop} />);
    expect(await probe()).toHaveAttribute("data-y-scale", "linear");
    const toggle = screen.getByRole("switch", { name: "Log scale" });
    expect(toggle).not.toBeChecked();
    expect(screen.queryByText(/Ages under half a day sit on the 0.5 line/)).not.toBeInTheDocument();

    await userEvent.click(toggle);
    expect(await probe()).toHaveAttribute("data-y-scale", "log");
    expect(screen.getByText(/Ages under half a day sit on the 0.5 line/)).toBeInTheDocument();
    expect(localStorage.getItem("flow.viewSettings.reports.agingWip.logScale")).toBe("true");
    // The reference lines and the dots are the same in both modes.
    expect(await probe()).toHaveAttribute("data-lines", "p50 · 8 wd|p85 · 15 wd|p95 · 25 wd");
    expect(await probe()).toHaveAttribute("data-count", "4");

    await userEvent.click(screen.getByRole("switch", { name: "Log scale" }));
    expect(await probe()).toHaveAttribute("data-y-scale", "linear");
  });

  test("a stored log choice is restored, and the Tasks | Epics switch keeps it", async () => {
    localStorage.setItem("flow.viewSettings.reports.agingWip.logScale", "true");
    renderWithProviders(<AgingChartSection report={WITH_EPIC_THRESHOLDS} selectedKey={null} onSelect={noop} />);
    expect(await probe()).toHaveAttribute("data-y-scale", "log");
    expect(screen.getByRole("switch", { name: "Log scale" })).toBeChecked();
    await userEvent.click(screen.getByRole("radio", { name: "Epics" }));
    expect(await probe()).toHaveAttribute("data-y-scale", "log");
  });

  test("a corrupt stored value falls back to linear", async () => {
    localStorage.setItem("flow.viewSettings.reports.agingWip.logScale", '"yes"');
    renderWithProviders(<AgingChartSection report={AGING_UNIT} selectedKey={null} onSelect={noop} />);
    expect(await probe()).toHaveAttribute("data-y-scale", "linear");
  });
});
