import { useState } from "react";
import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import MappingTable, { type MappingRow } from "./MappingTable";
import { renderWithProviders } from "../test/render";

function SelectHarness({ onPicked }: { onPicked: (value: string) => void }) {
  const [value, setValue] = useState("");
  const rows: MappingRow[] = [
    {
      id: "a",
      label: "Row A",
      fields: [
        {
          type: "select",
          ariaLabel: "Pick for Row A",
          value,
          options: [
            { value: "x", label: "Option X" },
            { value: "y", label: "Option Y" },
          ],
          onChange: (v) => {
            setValue(v);
            onPicked(v);
          },
        },
      ],
    },
  ];
  return <MappingTable label="Mapping" idColumnLabel="Id" fieldColumnLabels={["Value"]} rows={rows} />;
}

function TextHarness() {
  const [value, setValue] = useState("");
  const rows: MappingRow[] = [{ id: "a", label: "Row A", fields: [{ type: "text", ariaLabel: "Text for Row A", value, onChange: setValue }] }];
  return <MappingTable label="Mapping" idColumnLabel="Id" fieldColumnLabels={["Value"]} rows={rows} />;
}

describe("MappingTable", () => {
  test("renders an empty message when there are no rows", () => {
    renderWithProviders(<MappingTable label="Mapping" idColumnLabel="Id" fieldColumnLabels={["Value"]} rows={[]} emptyMessage="Nothing here" />);
    expect(screen.getByText("Nothing here")).toBeInTheDocument();
  });

  test("the table carries its accessible name", () => {
    renderWithProviders(<MappingTable label="Statuses" idColumnLabel="Id" fieldColumnLabels={["Value"]} rows={[{ id: "1", label: "A", fields: [] }]} />);
    expect(screen.getByRole("table", { name: "Statuses" })).toBeInTheDocument();
  });

  test("renders a select column and calls onChange with the picked option", async () => {
    const onPicked = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<SelectHarness onPicked={onPicked} />);

    await user.click(screen.getByRole("combobox", { name: "Pick for Row A" }));
    await user.click(await screen.findByRole("option", { name: "Option Y" }));

    expect(onPicked).toHaveBeenCalledWith("y");
    expect((screen.getByRole("combobox", { name: "Pick for Row A" }) as HTMLInputElement).value).toBe("Option Y");
  });

  test("renders a text column and reflects typed input", async () => {
    const user = userEvent.setup();
    renderWithProviders(<TextHarness />);

    const input = screen.getByLabelText("Text for Row A") as HTMLInputElement;
    await user.type(input, "hi");

    expect(input.value).toBe("hi");
  });

  test("shows a field error inline", () => {
    const rows: MappingRow[] = [
      {
        id: "a",
        label: "Row A",
        fields: [{ type: "text", ariaLabel: "Text for Row A", value: "", onChange: vi.fn(), error: "Conflict!" }],
      },
    ];
    renderWithProviders(<MappingTable label="Mapping" idColumnLabel="Id" fieldColumnLabels={["Value"]} rows={rows} />);

    expect(screen.getByText("Conflict!")).toBeInTheDocument();
  });
});
