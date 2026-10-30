import { describe, expect, test } from "vitest";
import { deepDiveReport, deepDiveTask } from "../test/deepDiveFixtures";
import { isDerived, noteOf } from "./deepDiveNote";

describe("noteOf", () => {
  test("a report with no note has none", () => {
    expect(noteOf(deepDiveReport())).toBeNull();
  });

  test("RANGE_CLAMPED passes through with its text; any other note is OTHER", () => {
    const text =
      "RANGE_CLAMPED: the selection spans more than 1100 days, so only 1100 days are shown";
    const clamped = deepDiveReport({ note: text });
    expect(noteOf(clamped)).toEqual({ kind: "RANGE_CLAMPED", text, historyHorizon: false });
    const other = deepDiveReport({ note: "something else" });
    expect(noteOf(other)).toEqual({ kind: "OTHER", text: "something else", historyHorizon: false });
  });

  test("a lone HISTORY_HORIZON note is its own kind; beside a span clamp it follows it and both are flagged", () => {
    const lone =
      "HISTORY_HORIZON: the selection lies before 2026-04-30 (history horizon: 6 months), so only that day is shown; totals are whole-life";
    expect(noteOf(deepDiveReport({ note: lone }))).toEqual({ kind: "HISTORY_HORIZON", text: lone, historyHorizon: true });
    const combined = `RANGE_CLAMPED: the selection spans more than 1100 days. ${lone}`;
    expect(noteOf(deepDiveReport({ note: combined }))).toEqual({ kind: "RANGE_CLAMPED", text: combined, historyHorizon: true });
  });

  test("an underived selection with no tasks is NOT_DERIVED, whatever the wording; with tasks it is OTHER", () => {
    const range = { from: "2026-10-05", to: "2026-10-05", asOfDay: null };
    const empty = deepDiveReport({ range, note: "any wording the server uses for it" });
    expect(isDerived(empty)).toBe(false);
    expect(noteOf(empty)).toEqual({ kind: "NOT_DERIVED", text: "any wording the server uses for it", historyHorizon: false });
    const withTask = deepDiveReport({ range, note: "x", tasks: [deepDiveTask("FLO-1")] });
    expect(noteOf(withTask)).toEqual({ kind: "OTHER", text: "x", historyHorizon: false });
  });
});
