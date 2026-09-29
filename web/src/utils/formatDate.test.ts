import { describe, expect, test } from "vitest";
import { formatDate } from "./formatDate";

describe("formatDate", () => {
  test("slices the UTC ISO date", () => {
    expect(formatDate(Date.UTC(2026, 8, 29, 23, 59))).toBe("2026-09-29");
  });

  test("reads the calendar day in the given zone", () => {
    // 22:30Z on the 29th is already the 30th in Warsaw (CEST, +2) — and still the 29th in UTC.
    const at = Date.UTC(2026, 8, 29, 22, 30);
    expect(formatDate(at, "—", "Europe/Warsaw")).toBe("2026-09-30");
    expect(formatDate(at)).toBe("2026-09-29");
    // Winter time (+1): 23:30Z crosses midnight, 22:30Z does not.
    expect(formatDate(Date.UTC(2026, 0, 10, 23, 30), "—", "Europe/Warsaw")).toBe("2026-01-11");
    expect(formatDate(Date.UTC(2026, 0, 10, 22, 30), "—", "Europe/Warsaw")).toBe("2026-01-10");
  });

  test("null and undefined render the fallback", () => {
    expect(formatDate(null)).toBe("—");
    expect(formatDate(undefined, "open")).toBe("open");
  });
});
