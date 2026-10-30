import { describe, expect, test } from "vitest";
import HomeFreshness from "./HomeFreshness";
import { HOME } from "../test/homeFixtures";
import { renderWithProviders, screen, within } from "../test/render";

describe("HomeFreshness and the history horizon", () => {
  test("says nothing about a horizon while it is off", () => {
    renderWithProviders(<HomeFreshness overview={HOME} />);
    expect(screen.queryByText(/history horizon/)).not.toBeInTheDocument();
  });

  test("says since when the reports show work while a horizon is set", () => {
    const overview = { ...HOME, meta: { ...HOME.meta, historyHorizon: { months: 24, from: "2024-09-29" } } };
    renderWithProviders(<HomeFreshness overview={overview} />);
    const freshness = screen.getByRole("group", { name: "How fresh the data is" });
    expect(within(freshness).getByText("Reports show work since 2024-09-29 (history horizon: 24 months)")).toBeInTheDocument();
  });
});
