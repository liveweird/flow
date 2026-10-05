import { useState } from "react";
import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import type { DeepDiveReport } from "../api/reports";
import { MONTH_CROSSING, deepDiveEpic, deepDiveReport, deepDiveTask } from "../test/deepDiveFixtures";
import { headingOutline } from "../test/headings";
import { renderWithProviders, screen, within } from "../test/render";
import DeepDiveBurnup from "./DeepDiveBurnup";

vi.mock("@mantine/charts", () => ({
  LineChart: (props: {
    data: unknown;
    series: unknown;
    referenceLines: unknown;
    dataKey: string;
    withDots: boolean;
    withLegend: boolean;
    connectNulls: boolean;
    yAxisLabel: string;
  }) => (
    <div
      data-testid="line-chart"
      data-rows={JSON.stringify(props.data)}
      data-series={JSON.stringify(props.series)}
      data-reference-lines={JSON.stringify(props.referenceLines)}
      data-key={props.dataKey}
      data-dots={String(props.withDots)}
      data-legend={String(props.withLegend)}
      data-nulls={String(props.connectNulls)}
      data-y-label={props.yAxisLabel}
    />
  ),
}));

/** The page owns the budget switch; this stands in for it. */
function Burnup({ report, budget = false }: { report: DeepDiveReport; budget?: boolean }) {
  const [withBudget, setWithBudget] = useState(budget);
  return <DeepDiveBurnup report={report} withBudget={withBudget} onWithBudgetChange={setWithBudget} />;
}

type Row = { date: string; pv: number; ev: number | null; ac: number | null; budget?: number | null };

// Thu 2026-08-27 (offset 0) … Tue 2026-09-08 (offset 12); offsets 2, 3, 9, 10 are the weekends.
function report(partial: Partial<DeepDiveReport> = {}): DeepDiveReport {
  return deepDiveReport({
    ...MONTH_CROSSING,
    mode: "SPRINTS",
    epics: [deepDiveEpic("FLO-1", { summary: "Onboarding" })],
    tasks: [
      deepDiveTask("FLO-11", {
        epicKey: "FLO-1",
        pv: [
          { d: 1, md: 1 },
          { d: 11, md: 2.25 },
        ],
        cost: [{ d: 1, a: null, md: 1.5 }],
        done: { d: 3, evMd: 1 },
      }),
    ],
    ...partial,
  });
}

const withBudget = (partial: Partial<DeepDiveReport> = {}) =>
  report({ epics: [deepDiveEpic("FLO-1", { plannedStart: 0, plannedDue: 4, budgetMd: 10 })], ...partial });

const chart = async () => screen.findByTestId("line-chart");
const rowsOf = (el: HTMLElement) => JSON.parse(el.getAttribute("data-rows")!) as Row[];
const seriesOf = (el: HTMLElement) => JSON.parse(el.getAttribute("data-series")!) as Array<{ name: string; label: string; color: string; strokeDasharray?: string }>;

describe("DeepDiveBurnup", () => {
  test("draws plan blue, earned value teal and cost gray on one man-day axis, with a legend and gaps left as gaps", async () => {
    renderWithProviders(<Burnup report={report()} />);
    const el = await chart();
    expect(screen.getByRole("group", { name: "Chart: cumulative plan, earned value and cost in man-days by day" })).toBeInTheDocument();
    expect(seriesOf(el)).toEqual([
      { name: "pv", label: "Plan (PV)", color: "flow.6" },
      { name: "ev", label: "Earned value (EV)", color: "teal.8" },
      { name: "ac", label: "Cost (AC)", color: "gray.6" },
    ]);
    expect(el.getAttribute("data-key")).toBe("date");
    expect(el.getAttribute("data-legend")).toBe("true");
    expect(el.getAttribute("data-nulls")).toBe("false");
    expect(el.getAttribute("data-dots")).toBe("false");
    expect(el.getAttribute("data-y-label")).toBe("MD");
    const rows = rowsOf(el);
    expect(rows).toHaveLength(13);
    expect(rows[0]).toEqual({ date: "2026-08-27", pv: 0, ev: 0, ac: 0 });
    // The as-of day is the range's last (the fixture default): every line runs to the end.
    expect(rows[12]).toEqual({ date: "2026-09-08", pv: 3.25, ev: 1, ac: 1.5 });
  });

  test("marks the as-of day with a labelled reference line", async () => {
    renderWithProviders(<Burnup report={report({ range: { from: "2026-08-27", to: "2026-09-08", asOfDay: "2026-08-31" } })} />);
    const el = await chart();
    expect(JSON.parse(el.getAttribute("data-reference-lines")!)).toEqual([
      { x: "2026-08-31", color: "gray.7", strokeDasharray: "2 4", label: "As of 2026-08-31", labelPosition: "insideBottomLeft" },
    ]);
    expect(rowsOf(el)[5]).toMatchObject({ date: "2026-09-01", ev: null });
    expect(rowsOf(el)[4]).toMatchObject({ date: "2026-08-31", ev: 1, ac: 1.5 });
    expect(screen.getByText(/Earned value and cost end on the day the data is current for/)).toBeInTheDocument();
  });

  test("the as-of label hangs left of its line in the range's right half, so it never runs past the plot edge", async () => {
    // 2026-09-06 is offset 10 of 13 (right half); 2026-09-02 is offset 6 (left half).
    const label = async (asOfDay: string) => {
      const { unmount } = renderWithProviders(<Burnup report={report({ range: { from: "2026-08-27", to: "2026-09-08", asOfDay } })} />);
      const lines = JSON.parse((await chart()).getAttribute("data-reference-lines")!) as Array<{ labelPosition: string }>;
      unmount();
      return lines[0].labelPosition;
    };
    expect(await label("2026-09-06")).toBe("insideBottomRight");
    expect(await label("2026-09-02")).toBe("insideBottomLeft");
  });

  test("an as-of day outside the range draws no marker and no note about ending actuals", async () => {
    renderWithProviders(<Burnup report={report({ range: { from: "2026-08-27", to: "2026-09-08", asOfDay: "2026-10-01" } })} />);
    const el = await chart();
    expect(JSON.parse(el.getAttribute("data-reference-lines")!)).toEqual([]);
    expect(rowsOf(el)[12]).toMatchObject({ ev: 1, ac: 1.5 });
    expect(screen.queryByText(/Earned value and cost end on the day/)).not.toBeInTheDocument();
  });

  test("states what the lines count and the range, under an h3 of its own", async () => {
    renderWithProviders(<Burnup report={report()} />);
    await chart();
    expect(screen.getByText("Showing 2026-08-27 to 2026-09-08.")).toBeInTheDocument();
    expect(screen.getByText(/Each line counts only what falls inside the range shown and starts at zero/)).toBeInTheDocument();
    expect(headingOutline()).toEqual([[3, "Burn-up"]]);
  });

  test("the epic budget plan is a dashed plan-blue line, off until switched on", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Burnup report={withBudget()} />);
    const toggle = screen.getByRole("switch", { name: /Epic budget plan/ });
    expect(toggle).not.toBeChecked();
    expect(seriesOf(await chart()).map((s) => s.name)).toEqual(["pv", "ev", "ac"]);
    expect(rowsOf(await chart())[0]).not.toHaveProperty("budget");

    await user.click(toggle);
    expect(toggle).toBeChecked();
    const on = await chart();
    expect(seriesOf(on)[3]).toEqual({ name: "budget", label: "Epic budget plan", color: "flow.6", strokeDasharray: "6 4" });
    expect(seriesOf(on).filter((s) => s.strokeDasharray)).toHaveLength(1);
    expect(rowsOf(on).slice(0, 5).map((r) => r.budget)).toEqual([3.33, 6.67, 6.67, 6.67, 10]);

    await user.click(toggle);
    expect(seriesOf(await chart()).map((s) => s.name)).toEqual(["pv", "ev", "ac"]);
  });

  test("a remembered budget choice starts the line on", async () => {
    renderWithProviders(<Burnup report={withBudget()} budget />);
    expect(screen.getByRole("switch", { name: /Epic budget plan/ })).toBeChecked();
    expect(seriesOf(await chart()).map((s) => s.name)).toEqual(["pv", "ev", "ac", "budget"]);
  });

  test("without an epic budget plan there is no switch, only the reason", async () => {
    renderWithProviders(<Burnup report={report()} />);
    await chart();
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
    expect(screen.getByText("No selected epic has both a planned window and a budget, so there is no budget plan to add.")).toBeInTheDocument();
  });

  test("the daily table holds every number, newest day first, a dash where an actual has ended", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Burnup report={withBudget({ range: { from: "2026-08-27", to: "2026-09-08", asOfDay: "2026-08-31" } })} />,
    );
    await chart();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Show daily figures" }));
    const table = screen.getByRole("table", { name: "Cumulative figures per day" });
    expect(within(table).getAllByRole("columnheader").map((h) => h.textContent)).toEqual([
      "Day",
      "Plan (PV, MD)",
      "Earned value (EV, MD)",
      "Cost (AC, MD)",
      "Epic budget plan (MD)",
    ]);
    const cells = (row: HTMLElement) => within(row).getAllByRole("cell").map((c) => c.textContent);
    const body = within(table).getAllByRole("row").slice(1);
    expect(body).toHaveLength(13);
    expect(cells(body[0])).toEqual(["2026-09-08", "3.25", "—", "—", "10"]);
    // Newest first: 2026-08-31 is the 9th from the end, the first day the last.
    expect(cells(body[8])).toEqual(["2026-08-31", "1", "1", "1.5", "10"]);
    expect(cells(body[12])).toEqual(["2026-08-27", "0", "0", "0", "3.33"]);
  });

  test("the table has no budget column when there is no budget plan", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Burnup report={report()} />);
    await chart();
    await user.click(screen.getByRole("button", { name: "Show daily figures" }));
    expect(within(screen.getByRole("table")).getAllByRole("columnheader")).toHaveLength(4);
  });

  test("says when the epics' own worklogs are in the cost line", async () => {
    const own = report({
      mode: "EPICS",
      authors: [{ accountId: "a1", displayName: "Ann Lee" }],
      epics: [deepDiveEpic("FLO-1", { ownCost: { cost: [{ d: 2, a: 0, md: 0.5 }], totalMd: 0.5 } })],
    });
    const { unmount } = renderWithProviders(<Burnup report={own} />);
    await chart();
    expect(screen.getByText("Cost includes the man-days logged on the epics themselves.")).toBeInTheDocument();
    expect(rowsOf(await chart())[12]).toMatchObject({ ac: 2 });
    unmount();
    renderWithProviders(<Burnup report={report()} />);
    await chart();
    expect(screen.queryByText(/Cost includes the man-days/)).not.toBeInTheDocument();
  });

  test("a selection with nothing to plot says so: no chart, no table", () => {
    renderWithProviders(<Burnup report={report({ tasks: [], epics: [] })} />);
    expect(screen.getByText("There is nothing to plot for this selection.")).toBeInTheDocument();
    expect(screen.queryByTestId("line-chart")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Show daily figures" })).not.toBeInTheDocument();
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
  });
});
