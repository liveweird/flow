import type { ReactElement } from "react";
import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import AgingChartSection from "./AgingChartSection";
import { renderWithProviders, screen, within } from "../test/render";
import { AGING_HIDDEN, AGING_TRUNCATED, AGING_UNIT } from "../test/reportFixtures";
import type { AgingWipReport } from "../api/reports";
import type { AgingKind } from "../utils/agingChart";

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
const renderSection = (report: AgingWipReport, kind: AgingKind = "TASK") =>
  renderWithProviders(<AgingChartSection report={report} kind={kind} selectedKey={null} onSelect={noop} />);

describe("AgingChartSection", () => {
  test("the chart plots the kind it is given, with that kind's thresholds — and has no switch of its own", async () => {
    const { unmount } = renderSection(WITH_EPIC_THRESHOLDS, "TASK");
    const tasks = await probe();
    expect(tasks).toHaveAttribute("data-count", "4");
    expect(tasks).toHaveAttribute("data-lines", "p50 · 8 wd|p85 · 15 wd|p95 · 25 wd");
    expect(tasks).toHaveAttribute("data-labels", "In progress|In review|On hold");
    expect(screen.queryByRole("radiogroup")).not.toBeInTheDocument();
    unmount();

    renderSection(WITH_EPIC_THRESHOLDS, "EPIC");
    const epics = await probe();
    expect(epics).toHaveAttribute("data-count", "1");
    expect(epics).toHaveAttribute("data-ages", "40");
    expect(epics).toHaveAttribute("data-lines", "p50 · 3 wd|p85 · 5 wd|p95 · 8 wd");
    expect(epics).toHaveAttribute("data-labels", "Epic doing");
    expect(screen.queryByRole("radiogroup")).not.toBeInTheDocument();
  });

  test("hidden thresholds: the note says why, no line is drawn, the dots still render", async () => {
    renderSection(AGING_HIDDEN);
    const chart = await probe();
    expect(chart).toHaveAttribute("data-lines", "");
    expect(chart).toHaveAttribute("data-count", "4");
    expect(screen.getByRole("note")).toHaveTextContent(/Only 3 finished items in the window/);
    const legend = within(screen.getByRole("list", { name: "Dot legend" }));
    expect(legend.getByText("No band (thresholds hidden)")).toBeInTheDocument();
    expect(legend.queryByText(/^Above/)).not.toBeInTheDocument();
  });

  test("the legend names the bands by rank and the two marker details in words", async () => {
    renderSection(AGING_UNIT);
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

  test("an epics-only list plots its epics", async () => {
    const epicsOnly = { ...AGING_UNIT, items: AGING_UNIT.items.filter((item) => item.itemKind === "EPIC") };
    renderSection(epicsOnly, "EPIC");
    expect(await probe()).toHaveAttribute("data-count", "1");
  });

  test("a truncated list is plotted as listed and carries the truncation note", async () => {
    renderSection(AGING_TRUNCATED);
    expect(await probe()).toHaveAttribute("data-count", "4");
    expect(screen.getByText("Showing the oldest 4 items; more match. Narrow the team or the domain to see the rest.")).toBeInTheDocument();
  });

  test("the log scale is a Switch, off by default; toggling it sets the log axis, the note and the remembered choice", async () => {
    renderSection(AGING_UNIT);
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

  test("a stored log choice is restored, for either kind", async () => {
    localStorage.setItem("flow.viewSettings.reports.agingWip.logScale", "true");
    const { unmount } = renderSection(WITH_EPIC_THRESHOLDS);
    expect(await probe()).toHaveAttribute("data-y-scale", "log");
    expect(screen.getByRole("switch", { name: "Log scale" })).toBeChecked();
    unmount();
    renderSection(WITH_EPIC_THRESHOLDS, "EPIC");
    expect(await probe()).toHaveAttribute("data-y-scale", "log");
  });

  test("a corrupt stored value falls back to linear", async () => {
    localStorage.setItem("flow.viewSettings.reports.agingWip.logScale", '"yes"');
    renderSection(AGING_UNIT);
    expect(await probe()).toHaveAttribute("data-y-scale", "linear");
  });
});
