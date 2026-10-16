import { describe, expect, test } from "vitest";
import ExcludedList from "./ExcludedList";
import { renderWithProviders, screen, within } from "../test/render";

describe("ExcludedList", () => {
  test("states the population, the measured count and one counted line per reason — and the sum reconciles", () => {
    renderWithProviders(
      <ExcludedList
        population={20}
        measured={12}
        items={[
          { label: "No time logged", count: 3 },
          { label: "Never started", count: 0 },
          { label: "No estimate at start", count: 5 },
        ]}
      />,
    );
    expect(screen.getByRole("heading", { level: 5, name: "Left out of this distribution" })).toBeInTheDocument();
    expect(screen.getByText("Of 20 finished in this period:")).toBeInTheDocument();
    const items = screen.getAllByRole("listitem");
    expect(items.map((i) => i.textContent?.replace(/\s+/g, " ").trim())).toEqual([
      "12 in the distribution",
      "3 No time logged",
      "0 Never started",
      "5 No estimate at start",
    ]);
    expect(within(items[1]).getByText("3")).toBeInTheDocument();
    // n + Σ reasons = population, spelled out.
    expect(screen.getByText("12 + 3 + 0 + 5 = 20")).toBeInTheDocument();
  });

  test("an ofWhich breakout reads under its reason and stays out of the closing equation", () => {
    renderWithProviders(
      <ExcludedList
        population={10}
        measured={6}
        items={[
          { label: "Estimated late", count: 1 },
          { label: "Unestimated", count: 3, ofWhich: { label: "backfilled after done", count: 2 } },
        ]}
      />,
    );
    const items = screen.getAllByRole("listitem");
    expect(items[2].textContent?.replace(/\s+/g, " ").trim()).toBe("3 UnestimatedOf these, 2: backfilled after done");
    expect(screen.getByText("6 + 1 + 3 = 10")).toBeInTheDocument();
  });

  test("an ofWhich breakout with a zero count is not shown", () => {
    renderWithProviders(
      <ExcludedList
        population={4}
        measured={3}
        items={[{ label: "Unestimated", count: 1, ofWhich: { label: "backfilled after done", count: 0 } }]}
      />,
    );
    expect(screen.queryByText(/Of these/)).not.toBeInTheDocument();
  });
});
