import { describe, expect, test } from "vitest";
import { CHANGELOG } from "./entries";
import { APP_VERSION } from "./version";

describe("changelog entries", () => {
  test("the app version is the newest entry's version", () => {
    expect(APP_VERSION).toBe(CHANGELOG[0].version);
  });

  test("entries are sorted newest first (same-day releases keep authoring order)", () => {
    for (let i = 1; i < CHANGELOG.length; i++) {
      expect(CHANGELOG[i - 1].date >= CHANGELOG[i].date).toBe(true);
    }
  });

  test("versions are unique", () => {
    const versions = CHANGELOG.map((e) => e.version);
    expect(new Set(versions).size).toBe(versions.length);
  });

  test("every entry has a version, an ISO date, and both language bodies", () => {
    for (const entry of CHANGELOG) {
      expect(entry.version).not.toBe("");
      expect(entry.date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
      expect(entry.en.trim()).not.toBe("");
      expect(entry.pl.trim()).not.toBe("");
    }
  });
});
