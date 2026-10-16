import { describe, expect, test } from "vitest";
import AdjustmentsBlock from "./AdjustmentsBlock";
import type { AdjustmentFigures } from "../api/reports";
import { ADJUSTMENTS } from "../test/reportFixtures";
import { renderWithProviders, screen } from "../test/render";

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
