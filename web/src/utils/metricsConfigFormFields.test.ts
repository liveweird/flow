import { describe, expect, test } from "vitest";
import { visibleFields } from "./metricsConfigForm";
import type { MetricsFieldOption } from "../api/metrics";

function field(fieldId: string, patch: Partial<MetricsFieldOption> = {}): MetricsFieldOption {
  return { fieldId, name: fieldId, type: "string", detectedRole: "OTHER", inScheme: null, nonNullCount: 0, inEpicScheme: null, inTaskScheme: null, ...patch };
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

describe("the per-scope Fields-tab filter", () => {
  const split = [
    field("epicOnly", { inScheme: true, inEpicScheme: true, inTaskScheme: false }),
    field("taskOnly", { inScheme: true, inEpicScheme: false, inTaskScheme: true }),
    field("both", { inScheme: true, inEpicScheme: true, inTaskScheme: true }),
    field("neither", { inScheme: false, inEpicScheme: false, inTaskScheme: false }),
  ];

  test("the epic scope lists the epic scheme's fields and the task scope the task scheme's", () => {
    expect(ids(visibleFields(split, [], false, "epic").listed)).toEqual(["epicOnly", "both"]);
    expect(ids(visibleFields(split, [], false, "task").listed)).toEqual(["taskOnly", "both"]);
    expect(ids(visibleFields(split, [], false, "any").listed)).toEqual(["epicOnly", "taskOnly", "both"]);
    expect(visibleFields(split, [], false, "epic").hiddenCount).toBe(2);
  });

  test("a selected field stays listed in a scope that excludes it, and showAll lists everything", () => {
    expect(ids(visibleFields(split, ["taskOnly"], false, "epic").listed)).toEqual(["epicOnly", "taskOnly", "both"]);
    expect(visibleFields(split, [], true, "task").listed).toBe(split);
  });

  test("an unknown split falls back to the union's scheme, then to the fields with data", () => {
    const unsplit = split.map((f) => ({ ...f, inEpicScheme: null, inTaskScheme: null }));
    expect(ids(visibleFields(unsplit, [], false, "epic").listed)).toEqual(["epicOnly", "taskOnly", "both"]);
    expect(ids(visibleFields(unsplit, [], false, "task").listed)).toEqual(["epicOnly", "taskOnly", "both"]);
    const noScheme = [field("a", { nonNullCount: 4 }), field("b")];
    const result = visibleFields(noScheme, [], false, "epic");
    expect(ids(result.listed)).toEqual(["a"]);
    expect(result.schemeUnknown).toBe(true);
  });

  test("a split that lists nothing for a scope lists everything rather than an empty Select", () => {
    const noEpic = split.map((f) => ({ ...f, inEpicScheme: false }));
    expect(ids(visibleFields(noEpic, [], false, "epic").listed)).toEqual(ids(noEpic));
  });
});
