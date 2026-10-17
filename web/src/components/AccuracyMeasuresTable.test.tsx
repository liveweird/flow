import { describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import AccuracyMeasuresTable from "./AccuracyMeasuresTable";
import type { AccuracyMeasures } from "../api/reports";
import { renderWithProviders, screen, within } from "../test/render";

const MEASURES: AccuracyMeasures = { pred10: 0.25, pred25: 0.5, pred50: 0.75, mdmre: 0.18, smape: 0.215, iqr: 0.45, idr: 1.2, mad: 0.25, tailShare: 0.083 };

describe("AccuracyMeasuresTable", () => {
  test("rows carry only the measure and its value; the meaning is not rendered until asked for", () => {
    renderWithProviders(<AccuracyMeasuresTable measures={MEASURES} name="At start" />);
    const table = screen.getByRole("table", { name: "Accuracy measures — At start" });
    expect(within(table).getAllByRole("row")).toHaveLength(10);
    expect(within(table).getAllByRole("button")).toHaveLength(9);
    expect(screen.queryByText(/relative to the actual/)).not.toBeInTheDocument();
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
  });

  test("the info icon is reachable by Tab, opens on keyboard focus, describes the button and closes on Escape", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AccuracyMeasuresTable measures={MEASURES} name="At start" />);
    await user.tab(); // the scroll region is the first stop
    await user.tab();
    const icon = screen.getByRole("button", { name: "About PRED(10)" });
    expect(icon).toHaveFocus();
    const tip = await screen.findByRole("tooltip");
    expect(tip).toHaveTextContent("Share of items whose estimate missed the actual cost by no more than 10%");
    expect(icon).toHaveAccessibleDescription(tip.textContent ?? "");
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
  });

  test("hovering the icon shows the meaning of that measure", async () => {
    const user = userEvent.setup();
    renderWithProviders(<AccuracyMeasuresTable measures={MEASURES} name="At start" />);
    await user.hover(screen.getByRole("button", { name: "About MdMRE" }));
    expect(await screen.findByRole("tooltip")).toHaveTextContent("Median relative error");
  });
});
