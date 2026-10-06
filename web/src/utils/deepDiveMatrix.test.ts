import { describe, expect, test } from "vitest";
import { MONTH_CROSSING, deepDiveEpic, deepDiveReport } from "../test/deepDiveFixtures";
import { grainColumns, type DeepDiveGrain } from "./deepDiveCalendar";
import { buildDeepDiveMatrix, type EpicWindow } from "./deepDiveMatrix";
import type { DeepDiveNoteKind } from "./deepDiveNote";

describe("epic planned window outline", () => {
  const windowAt = (
    grain: DeepDiveGrain,
    plannedStart: number | null,
    plannedDue: number | null,
  ): EpicWindow | null => {
    const report = deepDiveReport({
      ...MONTH_CROSSING,
      epics: [deepDiveEpic("FLO-1", { plannedStart, plannedDue })],
    });
    return buildDeepDiveMatrix(report, grainColumns(report, grain)).epics[0]
      .window;
  };

  test("maps to the first and last column it touches at each grain", () => {
    expect(windowAt("month", 3, 7)).toMatchObject({
      firstColumn: 0,
      lastColumn: 1,
    });
    expect(windowAt("week", 3, 7)).toMatchObject({
      firstColumn: 0,
      lastColumn: 2,
    });
    expect(windowAt("day", 3, 7)).toMatchObject({
      firstColumn: 3,
      lastColumn: 7,
      startsBeforeRange: false,
      endsAfterRange: false,
    });
    expect(windowAt("month", 6, 8)).toMatchObject({
      firstColumn: 1,
      lastColumn: 1,
    });
  });

  test("an outline reaching outside the range is cut to it and flagged open", () => {
    expect(windowAt("day", -10, 2)).toEqual({
      startDay: -10,
      dueDay: 2,
      firstColumn: 0,
      lastColumn: 2,
      startsBeforeRange: true,
      endsAfterRange: false,
    });
    expect(windowAt("week", 11, 40)).toMatchObject({
      firstColumn: 3,
      lastColumn: 3,
      startsBeforeRange: false,
      endsAfterRange: true,
    });
  });

  test("no window without both ends or when inverted", () => {
    expect(windowAt("day", null, 5)).toBeNull();
    expect(windowAt("day", 5, null)).toBeNull();
    expect(windowAt("day", 8, 3)).toBeNull();
  });

  test("a window entirely before or after the range is kept with no columns, so the page can say so", () => {
    expect(windowAt("day", -9, -1)).toEqual({
      startDay: -9,
      dueDay: -1,
      firstColumn: -1,
      lastColumn: -1,
      startsBeforeRange: true,
      endsAfterRange: false,
    });
    expect(windowAt("month", 13, 20)).toEqual({
      startDay: 13,
      dueDay: 20,
      firstColumn: -1,
      lastColumn: -1,
      startsBeforeRange: false,
      endsAfterRange: true,
    });
  });

  test("the outline is never summed: it adds no plan to the epic row", () => {
    const report = deepDiveReport({
      ...MONTH_CROSSING,
      epics: [
        deepDiveEpic("FLO-1", {
          plannedStart: 0,
          plannedDue: 12,
          budgetMd: 40,
        }),
      ],
    });
    const epic = buildDeepDiveMatrix(report, grainColumns(report, "week"))
      .epics[0];
    expect(epic.window).not.toBeNull();
    expect(epic.row.inRange).toEqual({
      pvMd: 0,
      execTaskDays: 0,
      evMd: 0,
      costMd: 0,
    });
    expect(epic.tasks).toEqual([]);
  });
});


describe("empty and annotated reports", () => {
  test("a NOT_DERIVED answer is an empty matrix flagged as not derived", () => {
    const report = deepDiveReport({
      meta: { ...deepDiveReport().meta, derivedAt: null, configRevision: null },
      range: { from: "2026-10-05", to: "2026-10-05", asOfDay: null },
      note: "Not derived yet: no connection in scope has a successful DERIVE run",
    });
    const matrix = buildDeepDiveMatrix(report, grainColumns(report, "month"));
    expect(matrix.derived).toBe(false);
    const kind: DeepDiveNoteKind = "NOT_DERIVED";
    expect(matrix.note).toEqual({ kind, text: report.note });
    expect(matrix.epics).toEqual([]);
    expect(matrix.columns).toHaveLength(1);
    expect(matrix.scale).toEqual({ mdMax: 0, execMax: 0 });
    expect(matrix.totals).toEqual({
      pvMd: 0,
      execTaskDays: 0,
      evMd: 0,
      costMd: 0,
    });
  });

  test("derived follows the selection (asOfDay), not meta.derivedAt, which spans every connection in scope", () => {
    const report = deepDiveReport({
      meta: { ...deepDiveReport().meta, derivedAt: 1_700_000_000_000 },
      range: { from: "2026-10-05", to: "2026-10-05", asOfDay: null },
      note: "any wording the server uses for it",
    });
    const matrix = buildDeepDiveMatrix(report, grainColumns(report, "month"));
    expect(matrix.derived).toBe(false);
    expect(matrix.note).toEqual({
      kind: "NOT_DERIVED",
      text: "any wording the server uses for it",
    });
    // A derived selection with an unrelated note is OTHER, whatever the wording.
    const other = deepDiveReport({ note: "Not derived yet, says the text" });
    expect(
      buildDeepDiveMatrix(other, grainColumns(other, "month")),
    ).toMatchObject({ derived: true, note: { kind: "OTHER" } });
  });

  test("a derived report with no rows is empty without a note", () => {
    const report = deepDiveReport();
    const matrix = buildDeepDiveMatrix(report, grainColumns(report, "week"));
    expect(matrix.derived).toBe(true);
    expect(matrix.note).toBeNull();
    expect(matrix.epics).toEqual([]);
    expect(matrix.mode).toBe("EPICS");
  });

  test("the quality counters, mode and range pass through untouched", () => {
    const report = deepDiveReport({
      mode: "SPRINTS",
      quality: { ...deepDiveReport().quality, noEstimate: 3 },
    });
    const matrix = buildDeepDiveMatrix(report, grainColumns(report, "month"));
    expect(matrix.quality.noEstimate).toBe(3);
    expect(matrix.mode).toBe("SPRINTS");
    expect(matrix.range).toBe(report.range);
  });
});
