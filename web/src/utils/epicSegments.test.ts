import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { EPIC_PALETTE, NO_EPIC_COLOR, OTHER_EPICS_COLOR } from "./chartColors";
import { NO_EPIC, OTHER_EPICS, epicSegmentColour, epicSegmentKeyName, epicSegmentName, epicSummary, orderedEpicSegments, topEpicIds, type EpicSegmentIdentity } from "./epicSegments";

const epic = (id: number, summary = "Summary"): EpicSegmentIdentity => ({ key: String(id), epicId: id, epicKey: `FLO-${id}`, label: `FLO-${id} ${summary}` });
const OTHER: EpicSegmentIdentity = { key: OTHER_EPICS, epicId: null, epicKey: null, label: "Other epics" };
const NONE: EpicSegmentIdentity = { key: NO_EPIC, epicId: null, epicKey: null, label: "No epic" };
const keys = (rows: ReadonlyArray<ReadonlyArray<EpicSegmentIdentity>>) => orderedEpicSegments(rows).map((segment) => segment.key);

describe("the order of a by-epic chart's segments", () => {
  test("rows that share no epic follow the server's rank (the score, then the key), not the order the rows came in", () => {
    // Server rank A(3) > B(2) > C(1): sprint 1 holds [A, C], sprint 2 only [B] — the union alone would say [A, C, B].
    const score = (key: string) => ({ "3": [9], "2": [5], "1": [1] })[key] ?? [];
    const ordered = orderedEpicSegments([[epic(3), epic(1), NONE], [epic(2)]], score);
    expect(ordered.map((segment) => [segment.key, segment.position])).toEqual([["3", 0], ["2", 1], ["1", 2], [NO_EPIC, 3]]);
    // A tie on every measure falls to the epic key, as on the server.
    expect(orderedEpicSegments([[epic(7)], [epic(5)]], () => [4]).map((segment) => segment.key)).toEqual(["5", "7"]);
  });

  test("rows that agree give that order, with a position per segment", () => {
    const segments = orderedEpicSegments([[epic(1), epic(2), OTHER, NONE], [epic(1), NONE]]);
    expect(segments.map((segment) => [segment.key, segment.position])).toEqual([["1", 0], ["2", 1], [OTHER_EPICS, 2], [NO_EPIC, 3]]);
  });

  test("an epic a sprint lacks keeps its place: a later sprint's missing-then-present epic slots in by its row neighbours", () => {
    expect(keys([[epic(2), NONE], [epic(1), epic(2), NONE]])).toEqual(["1", "2", NO_EPIC]);
    expect(keys([[epic(1), NONE], [epic(2), NONE]])).toEqual(["1", "2", NO_EPIC]);
    expect(keys([[NONE], [epic(1), OTHER, NONE], [epic(2), epic(3)]])).toEqual(["1", "2", "3", OTHER_EPICS, NO_EPIC]);
  });

  test("the two special segments are always last, 'Other epics' before 'No epic'", () => {
    expect(keys([[NONE], [OTHER], [epic(5)]])).toEqual(["5", OTHER_EPICS, NO_EPIC]);
  });

  test("no rows or empty rows give no segments", () => {
    expect(orderedEpicSegments([])).toEqual([]);
    expect(orderedEpicSegments([[], []])).toEqual([]);
  });

  test("the top epics' ids are the epics' own, distinct, never a special segment's", () => {
    expect(topEpicIds([epic(101), epic(102), OTHER, NONE])).toEqual([101, 102]);
    expect(topEpicIds([OTHER, NONE])).toEqual([]);
    expect(topEpicIds([epic(101), epic(101)])).toEqual([101]);
  });
});

describe("a by-epic segment's colour", () => {
  const segments = orderedEpicSegments([[1, 2, 3, 4, 5, 6].map((id) => epic(id)).concat(OTHER, NONE)]);

  test("the Nth top epic wears the Nth non-gray palette colour, in both schemes", () => {
    const tops = segments.filter((segment) => segment.epicId !== null);
    expect(tops).toHaveLength(6);
    expect(tops.map((segment) => epicSegmentColour(segment, "light"))).toEqual([...EPIC_PALETTE]);
    expect(tops.map((segment) => epicSegmentColour(segment, "dark"))).toEqual([...EPIC_PALETTE]);
    expect(EPIC_PALETTE).not.toContain(OTHER_EPICS_COLOR);
  });

  test("an epic keeps its colour whatever sprint is drawn: the colour follows the chart's segment, not a row", () => {
    const wide = orderedEpicSegments([[epic(2), NONE], [epic(1), epic(2), NONE]]);
    expect(epicSegmentColour(wide[1], "light")).toBe(EPIC_PALETTE[1]);
    expect(wide.map((segment) => segment.key)).toEqual(["1", "2", NO_EPIC]);
  });

  test("'Other epics' is the neutral gray, 'No epic' a second neutral picked per scheme", () => {
    const other = segments.find((segment) => segment.key === OTHER_EPICS)!;
    const none = segments.find((segment) => segment.key === NO_EPIC)!;
    expect(epicSegmentColour(other, "light")).toBe("gray.6");
    expect(epicSegmentColour(other, "dark")).toBe("gray.6");
    expect(epicSegmentColour(none, "light")).toBe(NO_EPIC_COLOR.light);
    expect(epicSegmentColour(none, "dark")).toBe(NO_EPIC_COLOR.dark);
  });

  test("the palette repeats rather than fail if a chart ever had more than six top epics", () => {
    expect(epicSegmentColour({ key: "7", position: 6 }, "light")).toBe(EPIC_PALETTE[0]);
  });
});

describe("a by-epic segment's words", () => {
  const t = i18n.t.bind(i18n);

  test("an epic is its label; the two special segments are localised by key, whatever fallback the server sent", () => {
    expect(epicSegmentName(t, epic(10, "Checkout"))).toBe("FLO-10 Checkout");
    expect(epicSegmentName(t, { key: OTHER_EPICS, label: "fallback" })).toBe("Other epics");
    expect(epicSegmentName(t, { key: NO_EPIC, label: "fallback" })).toBe("No epic");
  });

  test("a drawer names a key from the report when it knows it, else by a fixed fallback", () => {
    expect(epicSegmentKeyName(t, "101", epic(101, "Checkout"))).toBe("FLO-101 Checkout");
    expect(epicSegmentKeyName(t, "101", undefined)).toBe("Epic 101");
    expect(epicSegmentKeyName(t, NO_EPIC, undefined)).toBe("No epic");
    expect(epicSegmentKeyName(t, OTHER_EPICS, undefined)).toBe("Other epics");
  });

  test("an epic's summary is its label without the key; one without a summary is the key alone", () => {
    expect(epicSummary(epic(10, "Checkout flow"))).toBe("Checkout flow");
    expect(epicSummary({ epicKey: "FLO-10", label: "FLO-10" })).toBe("");
    expect(epicSummary({ epicKey: null, label: "No epic" })).toBe("No epic");
    expect(epicSummary({ epicKey: "FLO-10", label: "Something else" })).toBe("Something else");
  });
});
