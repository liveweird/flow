import { describe, expect, test } from "vitest";
import TrendMark from "./TrendMark";
import { renderWithProviders, screen } from "../test/render";

const WORDS = { better: "Improved", worse: "Declined", same: "Flat", none: "Unknown" } as const;

function mark(trend: Parameters<typeof TrendMark>[0]["trend"]) {
  return renderWithProviders(
    <p data-testid="line">
      <TrendMark trend={trend} wordOf={(tone) => WORDS[tone]} />
    </p>,
  );
}

describe("TrendMark", () => {
  test("the arrow of the value's direction and the verdict in the caller's own word", () => {
    mark({ tone: "worse", direction: "up" });
    expect(screen.getByTestId("line")).toHaveTextContent("↑ Declined");
    expect(screen.getByText("↑")).toHaveAttribute("aria-hidden", "true");
  });

  test("nothing without a trend or a direction", () => {
    mark(null);
    expect(screen.getByTestId("line")).toBeEmptyDOMElement();
  });
});
