import { describe, expect, test } from "vitest";
import { Table } from "@mantine/core";
import ScrollRegion from "./ScrollRegion";
import { renderWithProviders, screen } from "../test/render";

describe("ScrollRegion", () => {
  test("wraps its table in a focusable region named after it", () => {
    renderWithProviders(
      <ScrollRegion label="Figures" minWidth={400} maxHeight={200}>
        <Table aria-label="Figures">
          <Table.Tbody>
            <Table.Tr>
              <Table.Td>1</Table.Td>
            </Table.Tr>
          </Table.Tbody>
        </Table>
      </ScrollRegion>,
    );
    const region = screen.getByRole("region", { name: "Figures" });
    expect(region).toHaveAttribute("tabindex", "0");
    expect(region).toContainElement(screen.getByRole("table", { name: "Figures" }));
  });
});
