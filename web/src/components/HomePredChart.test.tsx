import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import HomePredChart from "./HomePredChart";
import { renderWithProviders, screen } from "../test/render";
import { HOME } from "../test/homeFixtures";

// recharts draws nothing under happy-dom: the shared Home probe exposes the line chart's points and fires its dot handlers.
vi.mock("@mantine/charts", async () => (await import("../test/homeChartsMock")).homeChartsMock);

const [ALPHA, , GAMMA] = HOME.teams;

describe("HomePredChart", () => {
  test("a solid tasks line and a dashed epics line over the three shares, one point per share, with a named group", () => {
    renderWithProviders(<HomePredChart team={ALPHA} />);
    expect(screen.getByRole("group", { name: `Chart: ${ALPHA.name}, share of tasks and epics within ±10, 25 and 50% of their estimate` })).toBeInTheDocument();
    const chart = screen.getByTestId("line-chart");
    expect(chart.getAttribute("data-labels")).toBe("±10%|±25%|±50%");
    expect(chart.getAttribute("data-series")).toBe("tasks,epics:dashed");
    expect(chart.getAttribute("data-connect-nulls")).toBe("false");
    expect(chart.getAttribute("data-tasks")).toBe("0.3,0.6,0.8");
    expect(chart.getAttribute("data-epics")).toBe("null,null,null");
  });

  test("a point is missing (a gap, never a zero) where the sample is under the minimum", () => {
    renderWithProviders(<HomePredChart team={GAMMA} />);
    const chart = screen.getByTestId("line-chart");
    expect(chart.getAttribute("data-tasks")).toBe("null,null,null");
    expect(chart.getAttribute("data-epics")).toBe("null,null,null");
  });

  test("the tooltip names how many items are within each share, and leaves out a line with no value", () => {
    renderWithProviders(<HomePredChart team={ALPHA} />);
    const tip = screen.getByTestId("tooltip-1").querySelector("[data-testid='tooltip-content']")!;
    expect(tip.getAttribute("data-label")).toBe("±25%");
    expect(tip.getAttribute("data-items")).toBe("Tasks (6 / 10)=60%");
  });

  test("a double-click on a dot (plain or active) reports the share as the row id and the line; without a handler the dots are untouched", async () => {
    const onDotDoubleClick = vi.fn();
    const { unmount } = renderWithProviders(<HomePredChart team={ALPHA} onDotDoubleClick={onDotDoubleClick} />);
    await userEvent.click(screen.getByTestId("dot-tasks-1"));
    expect(onDotDoubleClick).toHaveBeenLastCalledWith({ rowId: "25", series: "tasks" });
    await userEvent.click(screen.getByTestId("active-epics-2"));
    expect(onDotDoubleClick).toHaveBeenLastCalledWith({ rowId: "50", series: "epics" });
    unmount();

    onDotDoubleClick.mockClear();
    renderWithProviders(<HomePredChart team={ALPHA} />);
    await userEvent.click(screen.getByTestId("dot-tasks-1"));
    expect(onDotDoubleClick).not.toHaveBeenCalled();
  });
});
