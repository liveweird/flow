import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { dataSourceStateColor, formatEpochMillis } from "./dataSourceState";

describe("dataSourceState", () => {
  test("colours CURRENT teal, FAILED red, and every other state gray", () => {
    expect(dataSourceStateColor("CURRENT")).toBe("teal");
    expect(dataSourceStateColor("FAILED")).toBe("red");
    expect(dataSourceStateColor("NEVER_SYNCED")).toBe("gray");
    expect(dataSourceStateColor("STALE")).toBe("gray");
    expect(dataSourceStateColor("DISABLED")).toBe("gray");
  });

  test("formats an epoch-millis timestamp, and falls back to the 'never' vocabulary for null/undefined", () => {
    expect(formatEpochMillis(1_700_000_000_000, i18n.t)).toBe("2023-11-14 22:13");
    expect(formatEpochMillis(null, i18n.t)).toBe("Never");
    expect(formatEpochMillis(undefined, i18n.t)).toBe("Never");
  });
});
