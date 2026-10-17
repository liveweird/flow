import type { ReactNode } from "react";
import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import CostBreakdownChart, { type DonutEntry } from "./CostBreakdownChart";
import { renderWithProviders, screen } from "../test/render";

interface Cell {
  name: string;
  value: number;
  color: string;
  id: string;
}

// recharts draws nothing under happy-dom: the donut is a probe that exposes what it was given and forwards a slice click and the
// tooltip content exactly as recharts would (the pie's `onClick(sector, index)`, the tooltip's `content({ payload })`).
vi.mock("@mantine/charts", () => ({
  DonutChart: (props: {
    data: Cell[];
    tooltipDataSource?: string;
    pieProps: { onClick: (sector: Cell, index: number) => void };
    tooltipProps: { content: (args: { payload: Array<{ payload: { id: string } }> }) => ReactNode };
  }) => (
    <div data-testid="donut" data-cells={JSON.stringify(props.data)} data-source={props.tooltipDataSource}>
      {props.data.map((cell, index) => (
        <button key={cell.name} data-testid={`slice-${index}`} onClick={() => props.pieProps.onClick(cell, index)} />
      ))}
      {props.data.map((cell) => (
        <div key={cell.id} data-testid={`tip-${cell.id}`}>
          {props.tooltipProps.content({ payload: [{ payload: { id: cell.id } }] })}
        </div>
      ))}
      <div data-testid="tip-unknown">{props.tooltipProps.content({ payload: [{ payload: { id: "nope" } }] })}</div>
      <div data-testid="tip-empty">{props.tooltipProps.content({ payload: [] })}</div>
    </div>
  ),
}));

const ENTRIES: DonutEntry[] = [
  { id: "0", label: "Development", md: 8.5, share: 0.586, color: "flow.6" },
  { id: "1", label: "Other (2)", md: 0.5, share: 0.034, color: "gray.6", includes: "Planning, Ops" },
];

describe("CostBreakdownChart", () => {
  test("one segment per entry in its colour, a labelled group, and the tooltip describes the hovered segment only", () => {
    renderWithProviders(<CostBreakdownChart entries={ENTRIES} label="Chart: Man-days by activity type" onSelect={() => {}} />);
    expect(screen.getByRole("group", { name: "Chart: Man-days by activity type" })).toBeInTheDocument();
    const donut = screen.getByTestId("donut");
    expect(JSON.parse(donut.getAttribute("data-cells")!)).toEqual([
      { name: "Development", value: 8.5, color: "flow.6", id: "0" },
      { name: "Other (2)", value: 0.5, color: "gray.6", id: "1" },
    ]);
    expect(donut.getAttribute("data-source")).toBe("segment");
  });

  test("a click on a segment selects that entry by its id", async () => {
    const onSelect = vi.fn();
    renderWithProviders(<CostBreakdownChart entries={ENTRIES} label="Chart" onSelect={onSelect} />);
    await userEvent.setup().click(screen.getByTestId("slice-1"));
    expect(onSelect).toHaveBeenCalledWith("1");
  });

  test("the tooltip prints the figure and share, and for Other what it folds; an unknown or missing segment prints nothing", () => {
    renderWithProviders(<CostBreakdownChart entries={ENTRIES} label="Chart" onSelect={() => {}} />);
    expect(screen.getByTestId("tip-0").textContent).toBe("Development8.5 MD · 58.6%");
    expect(screen.getByTestId("tip-1").textContent).toBe("Other (2)0.5 MD · 3.4%Includes: Planning, Ops");
    expect(screen.getByTestId("tip-unknown")).toBeEmptyDOMElement();
    expect(screen.getByTestId("tip-empty")).toBeEmptyDOMElement();
  });

  test("two entries that read alike get distinct segment names (Mantine keys a segment by its name), and the hover card still finds each by id", () => {
    const twins: DonutEntry[] = [
      { id: "0", label: "Run", md: 2, share: 0.5, color: "flow.6" },
      { id: "1", label: "Run", md: 2, share: 0.5, color: "violet.5" },
    ];
    renderWithProviders(<CostBreakdownChart entries={twins} label="Chart" onSelect={() => {}} />);
    const names = (JSON.parse(screen.getByTestId("donut").getAttribute("data-cells")!) as Cell[]).map((cell) => cell.name);
    expect(new Set(names).size).toBe(2);
    expect(screen.getByTestId("tip-1").textContent).toBe("Run2 MD · 50%");
  });
});
