import { describe, expect, test, vi } from "vitest";
import DistributionPanel from "./DistributionPanel";
import { TASK_ACCURACY, TASK_ACCURACY_HIDDEN, TASK_ACCURACY_TAIL } from "../test/reportFixtures";
import { formatRatio } from "../utils/reportFormat";
import { renderWithProviders, screen, within } from "../test/render";

vi.mock("@mantine/charts", () => ({
  BarChart: (props: { data: { label: string; count?: number; tail?: number }[]; xAxisLabel?: string }) => (
    <div
      data-testid="bar-chart"
      data-rows={props.data.map((r) => `${r.label}=${r.count ?? `tail ${r.tail}`}`).join("|")}
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

  test("the open long-tail bucket reads \"≥ X\" in the chart and the table, marked in words, and the counts still sum to n", async () => {
    renderWithProviders(
      <DistributionPanel title="At start" distribution={TASK_ACCURACY_TAIL.atStart} minSampleSize={5} format={formatRatio} axisLabel="actual ÷ estimate" />,
    );
    expect((await screen.findByTestId("bar-chart")).getAttribute("data-rows")).toBe(
      "0.5 – 1=1|1 – 1.5=3|1.5 – 2=5|2 – 2.5=2|≥ 2.5=tail 1",
    );
    const table = screen.getByRole("table", { name: "Histogram, as a table — At start" });
    const rows = within(table).getAllByRole("row").slice(1);
    const last = rows[rows.length - 1];
    expect(within(last).getAllByRole("cell")[0]).toHaveTextContent("≥ 2.5 (long tail)");
    expect(last.lastElementChild).toHaveTextContent("1");
    // Only the open bucket carries the marker.
    expect(rows.slice(0, -1).map((r) => r.firstElementChild?.textContent)).toEqual(["0.5 – 1", "1 – 1.5", "1.5 – 2", "2 – 2.5"]);
    const counts = rows.map((r) => Number(r.lastElementChild?.textContent));
    expect(counts.reduce((a, b) => a + b, 0)).toBe(TASK_ACCURACY_TAIL.atStart.n);
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

describe("DistributionPanel — people mode", () => {
  const people = [
    { accountId: "acc-ann", label: "Ann", position: 1 },
    { accountId: "acc-bob", label: "Bob", position: 2 },
  ];
  const split = {
    ...TASK_ACCURACY.atStart,
    byUser: [
      { accountId: "acc-ann", label: "Ann", counts: [1, 2, 3, 0, 1] },
      { accountId: "acc-bob", label: "Bob", counts: [0, 1, 2, 2, 0] },
    ],
  };

  test("the histogram table gains a right-aligned column per person beside the total, and the cells are the shares", () => {
    renderWithProviders(
      <DistributionPanel title="At start" distribution={split} minSampleSize={5} format={formatRatio} axisLabel="x" people={people} />,
    );
    const table = screen.getByRole("table", { name: "Histogram, as a table — At start" });
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Range", "1. Ann", "2. Bob", "Items"]);
    const rows = within(table).getAllByRole("row").slice(1).map((r) => within(r).getAllByRole("cell").map((c) => c.textContent));
    expect(rows.map((cells) => cells.slice(1))).toEqual([
      ["1", "0", "1"],
      ["2", "1", "3"],
      ["3", "2", "5"],
      ["0", "2", "2"],
      ["1", "0", "1"],
    ]);
    for (const header of within(table).getAllByRole("columnheader").slice(1)) expect(header).toHaveStyle({ textAlign: "right" });
  });

  test("without people the panel is exactly the plain one", () => {
    renderWithProviders(
      <DistributionPanel title="At start" distribution={TASK_ACCURACY.atStart} minSampleSize={5} format={formatRatio} axisLabel="x" />,
    );
    const table = screen.getByRole("table", { name: "Histogram, as a table — At start" });
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual(["Range", "Items"]);
  });
});
