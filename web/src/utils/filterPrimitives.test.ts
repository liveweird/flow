import { describe, expect, test } from "vitest";
import { isIntegerText, MAX_SPAN_DAYS, parseBoundedInteger, validRange } from "./filterPrimitives";
import { addDays } from "./isoDate";

describe("validRange", () => {
  test("an open end always passes", () => {
    expect(validRange(undefined, undefined)).toBe(true);
    expect(validRange("2026-09-01", undefined)).toBe(true);
    expect(validRange(undefined, "2026-09-01")).toBe(true);
  });

  test("an ordered pair passes, a single day passes, an inverted pair fails", () => {
    expect(validRange("2026-09-01", "2026-09-30")).toBe(true);
    expect(validRange("2026-09-01", "2026-09-01")).toBe(true);
    expect(validRange("2026-09-02", "2026-09-01")).toBe(false);
  });

  test("the span cap is inclusive of both ends: exactly MAX_SPAN_DAYS days pass, one more fails", () => {
    const from = "2024-01-01";
    expect(validRange(from, addDays(from, MAX_SPAN_DAYS - 1))).toBe(true);
    expect(validRange(from, addDays(from, MAX_SPAN_DAYS))).toBe(false);
  });
});

describe("isIntegerText / parseBoundedInteger", () => {
  test("digits only, at most 15 of them", () => {
    expect(isIntegerText("0")).toBe(true);
    expect(isIntegerText("999999999999999")).toBe(true);
    expect(isIntegerText("1000000000000000")).toBe(false);
    expect(isIntegerText("-1")).toBe(false);
    expect(isIntegerText("1.5")).toBe(false);
    expect(isIntegerText("1e3")).toBe(false);
    expect(isIntegerText("")).toBe(false);
  });

  test("honours min and max and accepts a number or text", () => {
    expect(parseBoundedInteger("5", 1)).toBe(5);
    expect(parseBoundedInteger(5, 1)).toBe(5);
    expect(parseBoundedInteger("0", 1)).toBeUndefined();
    expect(parseBoundedInteger("53", 1, 52)).toBeUndefined();
    expect(parseBoundedInteger("52", 1, 52)).toBe(52);
  });

  test("rejects null, undefined and non-integers", () => {
    expect(parseBoundedInteger(null, 0)).toBeUndefined();
    expect(parseBoundedInteger(undefined, 0)).toBeUndefined();
    expect(parseBoundedInteger(1.5, 0)).toBeUndefined();
    expect(parseBoundedInteger(-3, 0)).toBeUndefined();
    expect(parseBoundedInteger("12abc", 0)).toBeUndefined();
  });
});
