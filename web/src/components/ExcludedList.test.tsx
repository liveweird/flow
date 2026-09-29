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
});
