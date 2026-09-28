import { describe, expect, test, vi } from "vitest";
import { epochMillisToIsoDate, isoDateToEpochMillis, isValidIsoDate, startOfTodayEpochMillis, todayIsoDate } from "./isoDate";

describe("isValidIsoDate", () => {
  test("rejects a malformed shape", () => {
    expect(isValidIsoDate("2024-1-1")).toBe(false);
    expect(isValidIsoDate("not-a-date")).toBe(false);
    expect(isValidIsoDate("")).toBe(false);
  });

  test("rejects an impossible calendar date (overflow normalizes to a different date)", () => {
    expect(isValidIsoDate("2024-02-30")).toBe(false);
    expect(isValidIsoDate("2024-13-01")).toBe(false);
  });

  test("accepts a real date, including a leap day", () => {
    expect(isValidIsoDate("2024-02-29")).toBe(true);
    expect(isValidIsoDate("2024-01-01")).toBe(true);
  });
});

describe("isoDateToEpochMillis / epochMillisToIsoDate", () => {
  test("round-trips through UTC midnight", () => {
    const ms = isoDateToEpochMillis("2024-01-15");
    expect(ms).toBe(Date.UTC(2024, 0, 15));
    expect(epochMillisToIsoDate(ms)).toBe("2024-01-15");
  });
});

describe("todayIsoDate / startOfTodayEpochMillis", () => {
  test("agree with each other under a fixed clock", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2024-06-15T13:45:00Z"));
    expect(todayIsoDate()).toBe("2024-06-15");
    expect(startOfTodayEpochMillis()).toBe(Date.UTC(2024, 5, 15));
    vi.useRealTimers();
  });
});
