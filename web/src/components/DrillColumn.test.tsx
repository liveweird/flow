import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import type { TFunction } from "i18next";
import { drillColumn, sprintDrillColumn } from "./DrillColumn";
import { renderWithProviders, screen } from "../test/render";

// The column's labels are keys here: the test pins structure and the disabled state, not copy.
const t = ((key: string, options?: { name?: string; bucket?: string }) => [key, options?.name, options?.bucket].filter(Boolean).join(" ")) as unknown as TFunction;
const row = { name: "Alpha 2" };

describe("the Items columns", () => {
  test("a row button opens the row; disabled, it stays in the row but cannot", async () => {
    const onDrill = vi.fn();
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<>{drillColumn(t, (r) => r.name, onDrill).render(row)}</>);
    await user.click(screen.getByRole("button", { name: "reports.drill.openAria Alpha 2" }));
    expect(onDrill).toHaveBeenCalledTimes(1);
    unmount();

    renderWithProviders(<>{drillColumn(t, (r) => r.name, onDrill, true).render(row)}</>);
    const button = screen.getByRole("button", { name: "reports.drill.openAria Alpha 2" });
    expect(button).toBeDisabled();
    await user.click(button);
    expect(onDrill).toHaveBeenCalledTimes(1);
  });

  test("a sprint row keeps one button per bucket up to two, and the kebab past two, disabled when asked", () => {
    const onDrill = vi.fn();
    const two = renderWithProviders(<>{sprintDrillColumn(t, (r) => r.name, ["COMMITTED", "FINAL"], onDrill, true).render(row)}</>);
    expect(screen.getAllByRole("button")).toHaveLength(2);
    for (const button of screen.getAllByRole("button")) expect(button).toBeDisabled();
    two.unmount();

    renderWithProviders(<>{sprintDrillColumn(t, (r) => r.name, ["COMMITTED", "ADDED", "FINAL"], onDrill, true).render(row)}</>);
    expect(screen.getByRole("button", { name: "common.table.operationsAria Alpha 2" })).toBeDisabled();
    renderWithProviders(<>{sprintDrillColumn(t, (r) => r.name, ["COMMITTED", "ADDED", "FINAL"], onDrill).render({ name: "Beta 1" })}</>);
    expect(screen.getByRole("button", { name: "common.table.operationsAria Beta 1" })).toBeEnabled();
  });
});
