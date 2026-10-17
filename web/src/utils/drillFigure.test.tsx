import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import type { ColumnDef } from "../components/ColumnTable";
import { renderWithProviders, screen } from "../test/render";
import { drillFigureColumn } from "./drillFigure";

interface Row {
  name: string;
  items: number;
}

const figure: ColumnDef<Row> = { key: "items", header: "Items", render: (row) => `${row.items} items` };
const alpha: Row = { name: "Alpha 2", items: 8 };

function spec(over: Partial<Parameters<typeof drillFigureColumn<Row>>[1]> = {}) {
  return {
    label: (row: Row, text: string, column: string) => `${text} — ${column}: show the items of ${row.name}`,
    count: (row: Row) => row.items,
    onOpen: vi.fn(),
    ...over,
  };
}

describe("drillFigureColumn", () => {
  test("keeps the column and turns its figure into a named button that hands the pressed element to the handler", async () => {
    const user = userEvent.setup();
    const options = spec();
    const column = drillFigureColumn(figure, options);
    expect(column.key).toBe("items");
    expect(column.header).toBe("Items");
    renderWithProviders(<>{column.render(alpha)}</>);
    const button = screen.getByRole("button");
    expect(button).toHaveTextContent("8 items");
    expect(button.tagName).toBe("BUTTON");
    // The name starts with the visible figure (WCAG 2.5.3) and carries the column's header.
    expect(button).toHaveAccessibleName("8 items — Items: show the items of Alpha 2");
    await user.click(button);
    expect(options.onOpen).toHaveBeenCalledExactlyOnceWith(alpha, button);
  });

  test("a numeric figure is read into the name as its digits", () => {
    const numeric: ColumnDef<Row> = { key: "n", header: "Count", render: (row) => row.items };
    renderWithProviders(<>{drillFigureColumn(numeric, spec()).render(alpha)}</>);
    expect(screen.getByRole("button")).toHaveAccessibleName("8 — Count: show the items of Alpha 2");
  });

  test("is reachable and operable from the keyboard", async () => {
    const user = userEvent.setup();
    const options = spec();
    renderWithProviders(<>{drillFigureColumn(figure, options).render(alpha)}</>);
    await user.tab();
    expect(screen.getByRole("button")).toHaveFocus();
    await user.keyboard("{Enter}");
    expect(options.onOpen).toHaveBeenCalledTimes(1);
  });

  test.each([
    ["a zero figure (nothing to list)", { items: 0 }, {}],
    ["rows kept from a previous filter", {}, { disabled: true }],
    ["a column without a handler", {}, { onOpen: undefined }],
  ])("%s renders as plain text, not a button", (_name, row, over) => {
    renderWithProviders(<>{drillFigureColumn(figure, spec(over)).render({ ...alpha, ...row })}</>);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(screen.getByText(`${"items" in row ? row.items : alpha.items} items`)).toBeInTheDocument();
  });
});
