import { describe, expect, test } from "vitest";
import type { ComboboxItem } from "@mantine/core";
import { foldDiacritics, foldedOptionsFilter } from "./text";

describe("foldDiacritics", () => {
  test("lowercases and strips combining diacritics", () => {
    expect(foldDiacritics("Żółw")).toBe("zolw");
    expect(foldDiacritics("ĄĆĘŃŚŹŻ")).toBe("acenszz");
  });

  test("maps the non-decomposing letters and ligatures", () => {
    expect(foldDiacritics("Łódź")).toBe("lodz");
    expect(foldDiacritics("Søren")).toBe("soren");
    expect(foldDiacritics("Straße")).toBe("strasse");
    expect(foldDiacritics("Æther œuvre đen")).toBe("aether oeuvre den");
  });
});

describe("foldedOptionsFilter", () => {
  const options: ComboboxItem[] = [
    { value: "1", label: "Żółw Kowalski" },
    { value: "2", label: "Zolw Plain" },
    { value: "3", label: "Bob" },
  ];

  test("matches accent-insensitively in both directions", () => {
    expect(foldedOptionsFilter({ options, search: "zolw", limit: 10 })).toEqual([
      options[0],
      options[1],
    ]);
    expect(foldedOptionsFilter({ options, search: "żółw", limit: 10 })).toEqual([
      options[0],
      options[1],
    ]);
  });

  test("filters grouped options and drops emptied groups", () => {
    const grouped = [
      { group: "Turtles", items: [options[0], options[1]] },
      { group: "Builders", items: [options[2]] },
    ];
    expect(foldedOptionsFilter({ options: grouped, search: "zolw", limit: 10 })).toEqual([
      { group: "Turtles", items: [options[0], options[1]] },
    ]);
  });
});
