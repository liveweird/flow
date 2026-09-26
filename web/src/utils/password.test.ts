import { describe, expect, test } from "vitest";
import { generatePassword, utf8ByteLength } from "./password";

describe("generatePassword", () => {
  test("produces 16 chars from the 64-char alphabet by default", () => {
    const password = generatePassword();
    expect(password).toMatch(/^[A-Za-z0-9_-]{16}$/);
  });

  test("honours a custom length and never repeats in practice", () => {
    expect(generatePassword(24)).toHaveLength(24);
    const many = new Set(Array.from({ length: 100 }, () => generatePassword()));
    expect(many.size).toBe(100);
  });
});

describe("utf8ByteLength", () => {
  test("counts bytes, not characters", () => {
    expect(utf8ByteLength("abc")).toBe(3);
    expect(utf8ByteLength("żółw")).toBe(7);
    expect(utf8ByteLength("🐸")).toBe(4);
  });
});
