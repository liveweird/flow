import { describe, expect, test, vi } from "vitest";
import { Table } from "@mantine/core";
import ScrollRegion from "./ScrollRegion";
import { renderWithProviders, screen } from "../test/render";
import { fireEvent } from "@testing-library/react";

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

  test("scrolls a focused overflowing region with unmodified horizontal arrows only", () => {
    renderWithProviders(
      <ScrollRegion label="Figures" minWidth={400}>
        <Table aria-label="Figures">
          <Table.Tbody>
            <Table.Tr>
              <Table.Td>
                <button type="button">Cell action</button>
              </Table.Td>
            </Table.Tr>
          </Table.Tbody>
        </Table>
      </ScrollRegion>,
    );
    const region = screen.getByRole("region", { name: "Figures" });
    const scrollBy = vi.fn();
    Object.defineProperties(region, {
      clientWidth: { configurable: true, value: 300 },
      scrollWidth: { configurable: true, value: 600 },
      scrollBy: { configurable: true, value: scrollBy },
    });

    region.focus();
    fireEvent.keyDown(region, { key: "ArrowRight" });
    fireEvent.keyDown(region, { key: "ArrowLeft" });
    expect(scrollBy).toHaveBeenNthCalledWith(1, { left: 40 });
    expect(scrollBy).toHaveBeenNthCalledWith(2, { left: -40 });

    fireEvent.keyDown(region, { key: "ArrowRight", ctrlKey: true });
    fireEvent.keyDown(screen.getByRole("button", { name: "Cell action" }), { key: "ArrowRight" });
    expect(scrollBy).toHaveBeenCalledTimes(2);
  });
});
