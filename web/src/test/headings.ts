import { screen } from "@testing-library/react";

/** Every rendered heading as `[level, text]`, in document order — the page's outline as a screen reader walks it. */
export function headingOutline(): [number, string][] {
  return screen.getAllByRole("heading").map((heading): [number, string] => [Number(heading.tagName.slice(1)), heading.textContent ?? ""]);
}
