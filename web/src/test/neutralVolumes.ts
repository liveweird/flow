import { expect } from "vitest";
import { within } from "@testing-library/react";

/** Style declarations that tint: a Mantine `c=`/`bg=` prop renders as inline `color` / `background` (or a variable of them). */
const TINT = /color|background|bg|fill/i;
const tinted = (element: Element) => TINT.test(element.getAttribute("style") ?? "");

/**
 * The A50 posture of a volume table, proved on the DOM and not on the absence of a few icons: no row and no cell of the volume columns (every column
 * after the first, the member's name) carries a colour, a background or a tint variant, with the ONE allowed exception of the dimmed "commits not
 * counted" note. A Mantine `c="red"` on a cell, or a `bg` on a row, fails this.
 */
export function expectNeutralVolumes(table: HTMLElement): void {
  const rows = within(table).getAllByRole("row").slice(1);
  expect(rows.length).toBeGreaterThan(0);
  for (const row of rows) {
    expect(tinted(row), "a row is tinted").toBe(false);
    expect(row.hasAttribute("data-variant") || row.hasAttribute("data-color"), "a row carries a tint variant").toBe(false);
    for (const cell of Array.from(row.querySelectorAll("td")).slice(1)) {
      for (const element of [cell, ...Array.from(cell.querySelectorAll("*"))]) {
        expect(element.hasAttribute("data-variant") || element.hasAttribute("data-color"), "a cell carries a tint variant").toBe(false);
        if (tinted(element)) expect(element.textContent, "only the dimmed note may be coloured").toMatch(/^\s*\(commits not counted: \d+\)\s*$/);
      }
    }
  }
}
