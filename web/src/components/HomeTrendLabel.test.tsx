import { describe, expect, test } from "vitest";
import { render, screen } from "@testing-library/react";
import { MantineProvider } from "@mantine/core";
import { HOME } from "../test/homeFixtures";
import { teamFigureRows } from "../utils/homeOverview";
import { theme } from "../theme";
import HomeTrendLabel from "./HomeTrendLabel";
import HomeTrendMark from "./HomeTrendMark";

const rows = teamFigureRows(HOME.teams, "TASK_CYCLE", (team) => team.granularity.cycleWorkingDays);

function inSvg(index: number, geometry = { x: 10, y: 20, width: 100, height: 18 }) {
  const { container } = render(
    <svg>
      <HomeTrendLabel rows={rows} index={index} {...geometry} />
    </svg>,
  );
  return container.querySelector("text");
}

describe("HomeTrendLabel (the arrow at the end of a bar)", () => {
  test("the arrow of the value's direction, in the verdict's colour, just past the bar and centred on it", () => {
    // Alpha's cycle rose against the previous window and that is worse: an orange up arrow.
    const mark = inSvg(0);
    expect(mark).toHaveTextContent("↑");
    expect(mark).toHaveAttribute("fill", "var(--mantine-color-orange-8)");
    expect(mark).toHaveAttribute("x", "116");
    expect(mark).toHaveAttribute("y", "29");
    // It is decoration: the verdict is text in the tooltip and the table.
    expect(mark).toHaveAttribute("aria-hidden", "true");
  });

  test("no arrow where there is nothing to compare with, or for a row the chart does not have", () => {
    // Beta's previous window was under the minimum sample: no direction.
    expect(inSvg(1)).toBeNull();
    expect(inSvg(9)).toBeNull();
  });

  test("a tie within the tolerance points sideways in gray, not up or down", () => {
    const cost = teamFigureRows(HOME.teams, "TASK_COST", (team) => team.granularity.costMd);
    // Alpha's logged cost moved by -0.1 against the previous window: noise, "No change".
    const { container } = render(
      <svg>
        <HomeTrendLabel rows={cost} index={0} x={10} y={20} width={100} height={18} />
      </svg>,
    );
    const mark = container.querySelector("text");
    expect(mark).toHaveTextContent("→");
    expect(mark).toHaveAttribute("fill", "var(--mantine-color-gray-6)");
  });

  test("nothing is drawn for geometry that is not a number", () => {
    expect(inSvg(0, { x: Number.NaN, y: 20, width: 100, height: 18 })).toBeNull();
  });
});

describe("HomeTrendMark (the arrow in the flow of a line)", () => {
  function mark(trend: Parameters<typeof HomeTrendMark>[0]["trend"]) {
    return render(
      <MantineProvider env="test" theme={theme}>
        <p data-testid="line">
          <HomeTrendMark trend={trend} />
        </p>
      </MantineProvider>,
    );
  }

  test("an arrow plus the verdict as a VISIBLE word, so it never travels by hue alone", () => {
    mark({ tone: "better", direction: "down" });
    expect(screen.getByTestId("line")).toHaveTextContent("↓ Better");
    expect(screen.getByText("↓")).toHaveAttribute("aria-hidden", "true");
  });

  test("a tie within the tolerance points sideways whatever the tiny difference was — never an up arrow", () => {
    mark({ tone: "same", direction: "up" });
    expect(screen.getByTestId("line")).toHaveTextContent("→ No change");
  });

  test("nothing for a figure with no trend or no direction", () => {
    mark(null);
    expect(screen.getByTestId("line")).toBeEmptyDOMElement();
    mark({ tone: "none", direction: null });
    expect(screen.getAllByTestId("line")[1]).toBeEmptyDOMElement();
  });
});
