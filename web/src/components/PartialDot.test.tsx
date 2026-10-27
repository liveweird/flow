import { describe, expect, test, vi } from "vitest";
import { fireEvent, render } from "@testing-library/react";
import { partialDot } from "./PartialDot";

function draw(partialAt: boolean[], props: { cx?: number | string; cy?: number | string; index: number }) {
  const Dot = partialDot("flow.6", partialAt);
  return render(
    <svg>
      <Dot {...props} />
    </svg>,
  ).container.querySelector("circle");
}

describe("partialDot", () => {
  test("a point in a full window is a filled dot in the series colour", () => {
    const dot = draw([false, true], { cx: 10, cy: 20, index: 0 });
    expect(dot).toHaveAttribute("fill", "var(--mantine-color-flow-6)");
    expect(dot).toHaveAttribute("r", "3");
    expect(dot).not.toHaveAttribute("data-partial");
  });

  test("a point in a partial window is HOLLOW: the surface inside a thicker ring, a bigger dot — a shape, not only a hue", () => {
    const dot = draw([false, true], { cx: 10, cy: 20, index: 1 });
    expect(dot).toHaveAttribute("data-partial", "true");
    expect(dot).toHaveAttribute("fill", "var(--mantine-color-body)");
    expect(dot).toHaveAttribute("stroke", "var(--mantine-color-flow-6)");
    expect(dot).toHaveAttribute("stroke-width", "2");
  });

  test("a gap (no coordinates) draws nothing", () => {
    expect(draw([true], { index: 0 })).toBeNull();
    expect(draw([true], { cx: "10", cy: "20", index: 0 })).toBeNull();
  });

  test("with a click handler a dot — filled or hollow — is a pointer target that reports its data index; without one it is inert", () => {
    const onClick = vi.fn();
    const Dot = partialDot("flow.6", [false, true], onClick);
    const { container } = render(
      <svg>
        <Dot cx={1} cy={2} index={0} />
        <Dot cx={3} cy={4} index={1} />
      </svg>,
    );
    const [filled, hollow] = Array.from(container.querySelectorAll("circle"));
    fireEvent.click(filled);
    fireEvent.click(hollow);
    expect(onClick.mock.calls).toEqual([[0], [1]]);
    expect(filled).toHaveStyle({ cursor: "pointer" });
    expect(draw([false], { cx: 1, cy: 2, index: 0 })).not.toHaveStyle({ cursor: "pointer" });
  });
});
