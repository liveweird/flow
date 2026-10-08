import { describe, expect, test } from "vitest";
import { visibleFields } from "./metricsConfigForm";
import type { MetricsFieldOption } from "../api/metrics";

function field(fieldId: string, patch: Partial<MetricsFieldOption> = {}): MetricsFieldOption {
  return { fieldId, name: fieldId, type: "string", detectedRole: "OTHER", inScheme: null, nonNullCount: 0, ...patch };
}

const ids = (fields: MetricsFieldOption[]) => fields.map((f) => f.fieldId);

describe("the default Fields-tab filter", () => {
  test("with a known scheme it lists the in-scheme fields, and counts the rest as hidden", () => {
    const fields = [
      field("a", { inScheme: true, nonNullCount: 0 }),
      field("b", { inScheme: false, nonNullCount: 50 }),
      field("c", { inScheme: true, nonNullCount: 3 }),
    ];
    const result = visibleFields(fields, [], false);
    expect(ids(result.listed)).toEqual(["a", "c"]);
    expect(result.hiddenCount).toBe(1);
    expect(result.schemeUnknown).toBe(false);
  });

  test("a selected field stays listed even when the scheme excludes it", () => {
    const fields = [field("a", { inScheme: true }), field("b", { inScheme: false })];
    expect(ids(visibleFields(fields, ["", "b", "duedate"], false).listed)).toEqual(["a", "b"]);
    expect(visibleFields(fields, ["b"], false).hiddenCount).toBe(0);
  });

  test("with an unknown scheme it falls back to the fields that have data", () => {
    const fields = [field("a", { nonNullCount: 7 }), field("b"), field("c", { nonNullCount: 1 })];
    const result = visibleFields(fields, [], false);
    expect(ids(result.listed)).toEqual(["a", "c"]);
    expect(result.hiddenCount).toBe(1);
    expect(result.schemeUnknown).toBe(true);
  });

  test("showAll lists everything but still reports what the default hides", () => {
    const fields = [field("a", { inScheme: true }), field("b", { inScheme: false })];
    const result = visibleFields(fields, [], true);
    expect(result.listed).toBe(fields);
    expect(result.hiddenCount).toBe(1);
  });

  test("with no information at all nothing is hidden", () => {
    const unknownAndEmpty = [field("a"), field("b")];
    expect(visibleFields(unknownAndEmpty, [], false)).toEqual({ listed: unknownAndEmpty, hiddenCount: 0, schemeUnknown: true });
    expect(visibleFields([], [], false)).toEqual({ listed: [], hiddenCount: 0, schemeUnknown: true });
  });
});
