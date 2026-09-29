import { describe, expect, test, vi } from "vitest";
import DistributionPanel from "./DistributionPanel";
import { TASK_ACCURACY, TASK_ACCURACY_HIDDEN } from "../test/reportFixtures";
import { formatRatio } from "../utils/reportFormat";
import { renderWithProviders, screen, within } from "../test/render";

vi.mock("@mantine/charts", () => ({
  BarChart: (props: { data: { label: string; count: number }[]; xAxisLabel?: string }) => (
    <div
      data-testid="bar-chart"
      data-rows={props.data.map((r) => `${r.label}=${r.count}`).join("|")}
      data-x-label={props.xAxisLabel}
    />
  ),
}));

describe("DistributionPanel", () => {
  test("shows the percentile strip, the histogram and the histogram as a table", async () => {
    renderWithProviders(
      <DistributionPanel title="At start" caption="Primary" distribution={TASK_ACCURACY.atStart} minSampleSize={5} format={formatRatio} axisLabel="actual ÷ estimate" />,
    );
    const strip = screen.getByRole("group", { name: "Percentiles" });
    for (const [label, value] of [["Median (p50)", "1.1"], ["p90", "1.9"], ["p95", "2.2"], ["Mean", "1.25"], ["Items", "12"]]) {
      expect(within(strip).getByText(label).nextElementSibling).toHaveTextContent(value);
    }
    expect(screen.getByText("Primary")).toBeInTheDocument();
    expect((await screen.findByTestId("bar-chart")).getAttribute("data-rows")).toBe(
      "0.5 – 1=1|1 – 1.5=3|1.5 – 2=5|2 – 2.5=2|2.5 – 3=1",
    );
    // The chart's numbers: one row per range, summing to n.
    const table = screen.getByRole("table", { name: "Histogram, as a table — At start" });
    const counts = within(table).getAllByRole("row").slice(1).map((r) => Number(r.lastElementChild?.textContent));
    expect(counts).toEqual([1, 3, 5, 2, 1]);
    expect(counts.reduce((a, b) => a + b, 0)).toBe(TASK_ACCURACY.atStart.n);
  });

  test("the x axis is titled with what the ranges measure", async () => {
    renderWithProviders(
      <DistributionPanel title="At start" distribution={TASK_ACCURACY.atStart} minSampleSize={5} format={formatRatio} axisLabel="actual ÷ estimate" />,
    );
    expect((await screen.findByTestId("bar-chart")).getAttribute("data-x-label")).toBe("actual ÷ estimate");
  });

  test("a tight distribution widens its range labels instead of printing 1 – 1 twice", async () => {
    const tight = {
      n: 9,
      hidden: false,
      p50: 1.001,
      p90: 1.003,
      p95: 1.004,
      mean: 1.002,
      histogram: [
        { from: 1.0, to: 1.001, count: 4 },
        { from: 1.001, to: 1.002, count: 3 },
        { from: 1.002, to: 1.003, count: 2 },
      ],
    };
    renderWithProviders(<DistributionPanel title="Tight" distribution={tight} minSampleSize={5} format={formatRatio} axisLabel="x" />);
    const table = screen.getByRole("table", { name: "Histogram, as a table — Tight" });
    const labels = within(table).getAllByRole("row").slice(1).map((r) => r.firstElementChild?.textContent);
    expect(new Set(labels).size).toBe(3);
    expect(labels).toEqual(["1 – 1.001", "1.001 – 1.002", "1.002 – 1.003"]);
    expect((await screen.findByTestId("bar-chart")).getAttribute("data-rows")).toBe("1 – 1.001=4|1.001 – 1.002=3|1.002 – 1.003=2");
  });

  test("below the minimum sample it shows only the notice — no strip, chart or table", () => {
    renderWithProviders(
      <DistributionPanel title="At start" distribution={TASK_ACCURACY_HIDDEN.atStart} minSampleSize={5} format={formatRatio} axisLabel="actual ÷ estimate" />,
    );
    expect(screen.getByRole("note")).toHaveTextContent("Only 3 items in this selection — at least 5 are needed");
    expect(screen.queryByRole("group", { name: "Percentiles" })).not.toBeInTheDocument();
    expect(screen.queryByTestId("bar-chart")).not.toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });

  test("a percentile the server left null renders as a dash", () => {
    renderWithProviders(
      <DistributionPanel
        title="x"
        distribution={{ ...TASK_ACCURACY.atStart, p90: null, mean: null }}
        minSampleSize={5}
        format={formatRatio}
        axisLabel="actual ÷ estimate"
      />,
    );
    const strip = screen.getByRole("group", { name: "Percentiles" });
    expect(within(strip).getByText("p90").nextElementSibling).toHaveTextContent("—");
    expect(within(strip).getByText("Mean").nextElementSibling).toHaveTextContent("—");
  });
});
