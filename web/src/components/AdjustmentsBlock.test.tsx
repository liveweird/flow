import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import AdjustmentsBlock from "./AdjustmentsBlock";
import type { AdjustmentFigures } from "../api/reports";
import { ADJUSTMENTS } from "../test/reportFixtures";
import { renderWithProviders, screen, within } from "../test/render";
import { showItems } from "../test/drill";

/** The tasks fixture with its change distribution emptied: `unchanged` measurable items, none adjusted. */
function allUnchanged(unchanged: number): AdjustmentFigures {
  const { changeExcluded } = ADJUSTMENTS.tasks;
  return {
    ...ADJUSTMENTS.tasks,
    changeDistribution: { n: 0, hidden: true, histogram: [], byUser: [] },
    changeExcluded: { ...changeExcluded, unchanged, population: unchanged + changeExcluded.estimatedLate + changeExcluded.unestimated },
  };
}

describe("AdjustmentsBlock unchanged items", () => {
  test("no adjusted item but some unchanged ones: a specific notice, not 'Nothing to measure'", () => {
    renderWithProviders(<AdjustmentsBlock title="Tasks" figures={allUnchanged(4)} minSampleSize={5} />);
    expect(
      screen.getByText("No estimate changed: all 4 measurable items finished with the estimates they started with."),
    ).toBeInTheDocument();
    expect(screen.queryByText("Nothing to measure in this selection.")).not.toBeInTheDocument();
    expect(screen.queryByText(/^Not charted:/)).not.toBeInTheDocument();
    // The population still adds up in the accounting: 0 charted + 4 unchanged + 2 late + 3 unestimated.
    expect(screen.getByText("0 + 4 + 2 + 3 = 9")).toBeInTheDocument();
  });

  test("a single unchanged item reads in the singular", () => {
    renderWithProviders(<AdjustmentsBlock title="Tasks" figures={allUnchanged(1)} minSampleSize={5} />);
    expect(screen.getByText("No estimate changed: the 1 measurable item finished with the estimate it started with.")).toBeInTheDocument();
  });

  test("nothing measurable at all keeps the generic notice and shows no 'Not charted' line", () => {
    renderWithProviders(<AdjustmentsBlock title="Tasks" figures={allUnchanged(0)} minSampleSize={5} />);
    expect(screen.getByText("Nothing to measure in this selection.")).toBeInTheDocument();
    expect(screen.queryByText(/^Not charted:/)).not.toBeInTheDocument();
    expect(screen.queryByText(/No estimate changed/)).not.toBeInTheDocument();
  });

  test("some adjusted and no unchanged items: the 'Not charted' line is hidden at zero", () => {
    const figures: AdjustmentFigures = {
      ...ADJUSTMENTS.tasks,
      changeExcluded: { ...ADJUSTMENTS.tasks.changeExcluded, unchanged: 0, population: 15 },
    };
    renderWithProviders(<AdjustmentsBlock title="Tasks" figures={figures} minSampleSize={5} />);
    expect(screen.queryByText(/^Not charted:/)).not.toBeInTheDocument();
  });
});

describe("AdjustmentsBlock drill", () => {
  test("a block given onDrill makes each charted bucket's count a button and keeps the 'Not charted' line and the accounting", async () => {
    const onDrill = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<AdjustmentsBlock title="Tasks" figures={ADJUSTMENTS.tasks} minSampleSize={5} onDrill={onDrill} />);
    const table = await screen.findByRole("table", { name: "Histogram, as a table — Tasks — Change from start to done" });
    expect(screen.getByText("Not charted: 3 finished items with an unchanged estimate (a 0% change).")).toBeInTheDocument();
    await user.click(within(table).getByRole("button", { name: showItems("Tasks — Change from start to done: 0% – +25%") }));
    expect(onDrill).toHaveBeenCalledWith(1, undefined, expect.any(HTMLElement));
  });

  test("without onDrill there is no Items column and no hint", async () => {
    renderWithProviders(<AdjustmentsBlock title="Tasks" figures={ADJUSTMENTS.tasks} minSampleSize={5} />);
    const table = await screen.findByRole("table", { name: "Histogram, as a table — Tasks — Change from start to done" });
    expect(within(table).queryByRole("button")).not.toBeInTheDocument();
  });

  test.each([
    ["no estimate changed", 4],
    ["nothing measurable", 0],
  ])("%s: there is no histogram, so nothing to drill into even with onDrill", (_name, unchanged) => {
    renderWithProviders(<AdjustmentsBlock title="Tasks" figures={allUnchanged(unchanged)} minSampleSize={5} onDrill={vi.fn()} />);
    expect(screen.queryByRole("button", { name: /show the items of/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("table", { name: /^Histogram, as a table/ })).not.toBeInTheDocument();
  });
});
