import { afterEach, describe, expect, test, vi } from "vitest";
import { holidaysOfYear, holidayYears, localYear, weekdayName, yearOf } from "./publicHolidays";

const rows = [
  { id: 1, date: "2025-12-25", name: "Christmas" },
  { id: 2, date: "2026-01-01", name: "New Year" },
  { id: 3, date: "2026-01-06", name: "Epiphany" },
];

describe("public holiday helpers", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  test("yearOf slices the four-digit year", () => {
    expect(yearOf("2026-01-06")).toBe("2026");
  });

  test("holidayYears: the years in the data, the current year and the viewed year, ascending and distinct", () => {
    expect(holidayYears(rows, "2026", "2026")).toEqual(["2025", "2026"]);
    expect(holidayYears(rows, "2030", "2026")).toEqual(["2025", "2026", "2030"]);
    expect(holidayYears([], "2026", "2024")).toEqual(["2024", "2026"]);
  });

  test("holidaysOfYear keeps one year in the given order", () => {
    expect(holidaysOfYear(rows, "2026").map((h) => h.id)).toEqual([2, 3]);
    expect(holidaysOfYear(rows, "2031")).toEqual([]);
  });

  test("weekdayName is the full name in the language, read as a plain UTC day", () => {
    expect(weekdayName("2026-01-01", "en")).toBe("Thursday");
    expect(weekdayName("2026-01-01", "pl")).toBe("Czwartek");
  });

  test("localYear reads the browser's local calendar, so late New Year's Eve is still the old year", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 11, 31, 23, 30));
    expect(localYear()).toBe("2026");
    vi.setSystemTime(new Date(2027, 0, 1, 0, 30));
    expect(localYear()).toBe("2027");
  });
});
