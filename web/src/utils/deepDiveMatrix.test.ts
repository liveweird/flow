import { describe, expect, test } from "vitest";
import type { DeepDiveReport } from "../api/reports";
import {
  MONTH_CROSSING,
  YEAR_CROSSING,
  deepDiveEpic,
  deepDiveReport,
  deepDiveTask,
} from "../test/deepDiveFixtures";
import {
  EMPTY_CELL,
  buildDeepDiveMatrix,
  expandedColumns,
  formatFigure,
  grainColumns,
  type AuthorCost,
  type DeepDiveGrain,
  type DeepDiveNoteKind,
  type DoneMarker,
  type EpicWindow,
  type LayerScale,
  type LayerTotals,
  type MatrixEpic,
  type MatrixRow,
  type MatrixRowKind,
  type TimeColumn,
} from "./deepDiveMatrix";

const GRAINS: readonly DeepDiveGrain[] = ["month", "week", "day"];
const spans = (columns: readonly TimeColumn[]) =>
  columns.map((c) => [c.fromDay, c.toDay]);

function expectPartition(columns: readonly TimeColumn[], days: number) {
  expect(columns[0].fromDay).toBe(0);
  expect(columns[columns.length - 1].toDay).toBe(days - 1);
  columns.forEach((c, i) => {
    expect(c.toDay).toBeGreaterThanOrEqual(c.fromDay);
    expect(c.days).toBe(c.toDay - c.fromDay + 1);
    if (i > 0) expect(c.fromDay).toBe(columns[i - 1].toDay + 1);
  });
  expect(new Set(columns.map((c) => c.id)).size).toBe(columns.length);
}

describe("time columns", () => {
  test("every grain partitions the days of a range crossing a month boundary mid-week", () => {
    const report = deepDiveReport(MONTH_CROSSING);
    for (const grain of GRAINS)
      expectPartition(grainColumns(report, grain), 13);
    expect(spans(grainColumns(report, "month"))).toEqual([
      [0, 4],
      [5, 12],
    ]);
    expect(grainColumns(report, "month").map((c) => c.label)).toEqual([
      "2026-08",
      "2026-09",
    ]);
    expect(grainColumns(report, "day")).toHaveLength(13);
  });

  test("weeks are ISO weeks clipped to their month: the week crossing the boundary is two columns", () => {
    const weeks = grainColumns(deepDiveReport(MONTH_CROSSING), "week");
    expect(spans(weeks)).toEqual([
      [0, 3],
      [4, 4],
      [5, 10],
      [11, 12],
    ]);
    expect(weeks.map((c) => c.label)).toEqual([
      "2026-W35",
      "2026-W36",
      "2026-W36",
      "2026-W37",
    ]);
    expect(weeks.map((c) => c.parentId)).toEqual([
      "month:2026-08",
      "month:2026-08",
      "month:2026-09",
      "month:2026-09",
    ]);
    expect(weeks.map((c) => [c.fromDate, c.toDate])).toEqual([
      ["2026-08-27", "2026-08-30"],
      ["2026-08-31", "2026-08-31"],
      ["2026-09-01", "2026-09-06"],
      ["2026-09-07", "2026-09-08"],
    ]);
  });

  test("a range crossing a year boundary partitions at every grain and labels the ISO week-year", () => {
    const report = deepDiveReport(YEAR_CROSSING);
    for (const grain of GRAINS)
      expectPartition(grainColumns(report, grain), 10);
    const weeks = grainColumns(report, "week");
    expect(spans(weeks)).toEqual([
      [0, 1],
      [2, 4],
      [5, 8],
      [9, 9],
    ]);
    // 2025-12-29 .. 2026-01-04 is ISO week 2026-W01, split by the year/month edge.
    expect(weeks.map((c) => c.label)).toEqual([
      "2025-W52",
      "2026-W01",
      "2026-W01",
      "2026-W02",
    ]);
    expect(grainColumns(report, "month").map((c) => c.id)).toEqual([
      "month:2025-12",
      "month:2026-01",
    ]);
  });

  test("ISO week 53 is labelled with the week-year it belongs to (2026-W53 into 2027-W01, and 2020-W53 on 2021-01-01)", () => {
    const week53 = deepDiveReport({
      range: { from: "2026-12-30", to: "2027-01-05", asOfDay: null },
      nonWorkingDays: [],
    });
    const weeks = grainColumns(week53, "week");
    expect(spans(weeks)).toEqual([
      [0, 1],
      [2, 4],
      [5, 6],
    ]);
    expect(weeks.map((c) => c.label)).toEqual([
      "2026-W53",
      "2026-W53",
      "2027-W01",
    ]);
    expectPartition(weeks, 7);
    const jan = deepDiveReport({
      range: { from: "2021-01-01", to: "2021-01-04", asOfDay: null },
      nonWorkingDays: [],
    });
    expect(
      grainColumns(jan, "week").map((c) => [c.label, c.fromDay, c.toDay]),
    ).toEqual([
      ["2020-W53", 0, 2],
      ["2021-W01", 3, 3],
    ]);
    expect(
      grainColumns(
        deepDiveReport({
          range: { from: "2021-01-01", to: "2021-01-01", asOfDay: null },
        }),
        "week",
      )[0].label,
    ).toBe("2020-W53");
  });

  test("a column is non-working only when every one of its days is", () => {
    const month = deepDiveReport(MONTH_CROSSING);
    expect(
      grainColumns(month, "day").flatMap((c, i) => (c.nonWorking ? [i] : [])),
    ).toEqual([2, 3, 9, 10]);
    expect(grainColumns(month, "week").map((c) => c.nonWorking)).toEqual([
      false,
      false,
      false,
      false,
    ]);
    expect(grainColumns(month, "month").map((c) => c.nonWorking)).toEqual([
      false,
      false,
    ]);
    // The clipped weekend-only week (Sat 12-27 .. Sun 12-28) is non-working as a column.
    expect(
      grainColumns(deepDiveReport(YEAR_CROSSING), "week").map(
        (c) => c.nonWorking,
      ),
    ).toEqual([true, false, false, false]);
  });

  test("a month expands into weeks and a week into days, only where the drill state says so", () => {
    const report = deepDiveReport(MONTH_CROSSING);
    expect(grainColumns(report, "month").map((c) => c.expandable)).toEqual([
      true,
      true,
    ]);
    const september = expandedColumns(report, new Set(["month:2026-09"]));
    expect(september.map((c) => c.id)).toEqual([
      "month:2026-08",
      "week:2026-09-01",
      "week:2026-09-07",
    ]);
    const drilled = expandedColumns(
      report,
      new Set(["month:2026-09", "week:2026-09-07"]),
    );
    expect(drilled.map((c) => c.id)).toEqual([
      "month:2026-08",
      "week:2026-09-01",
      "day:2026-09-07",
      "day:2026-09-08",
    ]);
    expectPartition(drilled, 13);
    // An id whose parent is collapsed is inert; no ids is the month view.
    expect(expandedColumns(report, new Set(["week:2026-09-07"]))).toEqual(
      grainColumns(report, "month"),
    );
    expect(expandedColumns(report, new Set())).toEqual(
      grainColumns(report, "month"),
    );
  });

  test("a one-day column is not expandable and a one-day range is a single column at every grain", () => {
    const report = deepDiveReport();
    for (const grain of GRAINS) {
      const columns = grainColumns(report, grain);
      expect(columns).toHaveLength(1);
      expect(columns[0].kind).toBe(grain);
      expect(columns[0].expandable).toBe(false);
    }
  });
});

// ---- a populated report over the month-crossing range ------------------------------------

const AUTHORS = [
  { accountId: "acc-ann", displayName: "Ann" },
  { accountId: "acc-bob", displayName: "Bob" },
  { accountId: "acc-ghost", displayName: " " },
];

function populated(): DeepDiveReport {
  return deepDiveReport({
    ...MONTH_CROSSING,
    authors: AUTHORS,
    epics: [
      deepDiveEpic("FLO-1", {
        summary: "Alpha",
        plannedStart: 3,
        plannedDue: 7,
        budgetMd: 9,
      }),
      deepDiveEpic("FLO-2", { summary: "Beta" }),
      deepDiveEpic(null),
    ],
    tasks: [
      deepDiveTask("FLO-10", {
        epicKey: "FLO-1",
        pv: [
          { d: 0, md: 0.5 },
          { d: 4, md: 0.25 },
          { d: 5, md: 0.25 },
          { d: 12, md: 1 },
        ],
        exec: [
          { d: 0, td: 0.5 },
          { d: 5, td: 1 },
          { d: 6, td: 0.3333 },
        ],
        done: { d: 6, evMd: 2 },
        cost: [
          { d: 0, a: 0, md: 0.5 },
          { d: 5, a: 0, md: 1 },
          { d: 6, a: 0, md: 0.5 },
          { d: 6, a: 1, md: 0.25 },
          { d: 7, a: null, md: 0.125 },
        ],
        totals: { pvMd: 2, execTaskDays: 1.8333, evMd: 2, costMd: 2.375 },
      }),
      deepDiveTask("FLO-11", {
        epicKey: "FLO-1",
        pv: [{ d: 5, md: 0.75 }],
        exec: [{ d: 5, td: 0.5 }],
        done: { d: 12, evMd: 1.5 },
        cost: [
          { d: 5, a: 0, md: 0.25 },
          { d: 5, a: 2, md: 0.5 },
          { d: 11, a: 7, md: 0.1 },
        ],
        totals: { pvMd: 0.75, execTaskDays: 0.5, evMd: 1.5, costMd: 3 },
      }),
      deepDiveTask("FLO-20", {
        epicKey: "FLO-2",
        pv: [{ d: 1, md: 2 }],
        totals: { pvMd: 2, execTaskDays: 0, evMd: 0, costMd: 0 },
      }),
      deepDiveTask("FLO-30", {
        epicKey: null,
        exec: [{ d: 11, td: 1 }],
        totals: { pvMd: 0, execTaskDays: 1, evMd: 0, costMd: 0 },
      }),
    ],
  });
}

const rowsOf = (m: ReturnType<typeof buildDeepDiveMatrix>): MatrixRow[] =>
  m.epics.flatMap((e: MatrixEpic) => [e.row, ...e.tasks]);

/** The layer sums of one row's cells, summed over the given column indexes. */
function sumCells(row: MatrixRow, from = 0, to = row.cells.length) {
  const sums: LayerTotals = { pvMd: 0, execTaskDays: 0, evMd: 0, costMd: 0 };
  for (const cell of row.cells.slice(from, to)) {
    sums.pvMd += cell.pvMd;
    sums.execTaskDays += cell.execTaskDays;
    sums.evMd += cell.evMd;
    sums.costMd += cell.costMd;
  }
  return sums;
}

describe("buildDeepDiveMatrix sums", () => {
  test("the sums at every grain equal the day-level sums, row by row and column by column", () => {
    const report = populated();
    const days = buildDeepDiveMatrix(report, grainColumns(report, "day"));
    for (const grain of ["week", "month"] as const) {
      const columns = grainColumns(report, grain);
      const matrix = buildDeepDiveMatrix(report, columns);
      rowsOf(matrix).forEach((row, r) => {
        const dayRow = rowsOf(days)[r];
        expect(row.id).toBe(dayRow.id);
        columns.forEach((col, c) => {
          const expected = sumCells(dayRow, col.fromDay, col.toDay + 1);
          const cell = row.cells[c];
          expect(cell.pvMd).toBeCloseTo(expected.pvMd, 10);
          expect(cell.execTaskDays).toBeCloseTo(expected.execTaskDays, 10);
          expect(cell.evMd).toBeCloseTo(expected.evMd, 10);
          expect(cell.costMd).toBeCloseTo(expected.costMd, 10);
        });
        expect(row.inRange.pvMd).toBeCloseTo(dayRow.inRange.pvMd, 10);
        expect(row.inRange.costMd).toBeCloseTo(dayRow.inRange.costMd, 10);
      });
    }
  });

  test("the day-level cells hold exactly the series entries", () => {
    const report = populated();
    const matrix = buildDeepDiveMatrix(report, grainColumns(report, "day"));
    const task = matrix.epics[0].tasks[0];
    expect(task.cells.map((c) => c.pvMd)).toEqual([
      0.5, 0, 0, 0, 0.25, 0.25, 0, 0, 0, 0, 0, 0, 1,
    ]);
    expect(task.cells[1]).toBe(EMPTY_CELL);
    expect(task.cells[6].execTaskDays).toBeCloseTo(0.3333, 10);
    expect(task.cells[6].costMd).toBeCloseTo(0.75, 10);
  });

  test("an epic row is the sum of its tasks, cell by cell, at every grain", () => {
    const report = populated();
    for (const grain of GRAINS) {
      const matrix = buildDeepDiveMatrix(report, grainColumns(report, grain));
      for (const epic of matrix.epics) {
        epic.row.cells.forEach((cell, c) => {
          const tasks = epic.tasks.map((t) => t.cells[c]);
          expect(cell.pvMd).toBeCloseTo(
            tasks.reduce((s, t) => s + t.pvMd, 0),
            10,
          );
          expect(cell.execTaskDays).toBeCloseTo(
            tasks.reduce((s, t) => s + t.execTaskDays, 0),
            10,
          );
          expect(cell.evMd).toBeCloseTo(
            tasks.reduce((s, t) => s + t.evMd, 0),
            10,
          );
          expect(cell.costMd).toBeCloseTo(
            tasks.reduce((s, t) => s + t.costMd, 0),
            10,
          );
          expect(cell.done).toHaveLength(
            tasks.reduce((s, t) => s + t.done.length, 0),
          );
        });
      }
    }
    const matrix = buildDeepDiveMatrix(report, grainColumns(report, "month"));
    const alpha = matrix.epics[0];
    expect(alpha.row.totals.pvMd).toBeCloseTo(2.75, 10);
    expect(alpha.row.totals.execTaskDays).toBeCloseTo(2.3333, 10);
    expect(alpha.row.totals.evMd).toBeCloseTo(3.5, 10);
    expect(alpha.row.totals.costMd).toBeCloseTo(5.375, 10);
    expect(alpha.row.cells[1].pvMd).toBeCloseTo(0.25 + 0.75 + 1, 10);
  });

  test("the grand sums add the epic rows (and never the epics' own worklogs)", () => {
    const report = populated();
    const matrix = buildDeepDiveMatrix(report, grainColumns(report, "week"));
    expect(matrix.totals.pvMd).toBeCloseTo(4.75, 10);
    expect(matrix.totals.execTaskDays).toBeCloseTo(3.3333, 10);
    expect(matrix.totals.evMd).toBeCloseTo(3.5, 10);
    expect(matrix.totals.costMd).toBeCloseTo(5.375, 10);
    expect(matrix.inRange.pvMd).toBeCloseTo(
      0.5 + 0.25 + 0.25 + 1 + 0.75 + 2,
      10,
    );
    expect(matrix.inRange.evMd).toBeCloseTo(3.5, 10);
    expect(matrix.ownCostTotalMd).toBe(0);
  });

  test("entries on days no column holds (out of range, fractional) are ignored, never misfiled", () => {
    const report = deepDiveReport({
      ...MONTH_CROSSING,
      epics: [deepDiveEpic("FLO-1")],
      tasks: [
        deepDiveTask("FLO-10", {
          epicKey: "FLO-1",
          pv: [
            { d: -1, md: 5 },
            { d: 13, md: 5 },
            { d: 1.5, md: 5 },
            { d: 0, md: 1 },
          ],
          done: { d: 99, evMd: 3 },
        }),
      ],
    });
    const matrix = buildDeepDiveMatrix(report, grainColumns(report, "month"));
    expect(matrix.inRange.pvMd).toBe(1);
    expect(matrix.inRange.evMd).toBe(0);
  });
});

describe("buildDeepDiveMatrix cells", () => {
  test("authors merge across the days of a column, name resolution falls back as documented", () => {
    const report = populated();
    const matrix = buildDeepDiveMatrix(report, grainColumns(report, "month"));
    const september = matrix.epics[0].tasks[0].cells[1];
    // Ann: 1 + 0.5 over two days; Bob 0.25; no author 0.125.
    const expectedAuthors: AuthorCost[] = [
      { authorIndex: 0, name: "Ann", md: 1.5 },
      { authorIndex: 1, name: "Bob", md: 0.25 },
      { authorIndex: null, name: null, md: 0.125 },
    ];
    expect(september.cost.map((a) => [a.authorIndex, a.name])).toEqual(
      expectedAuthors.map((a) => [a.authorIndex, a.name]),
    );
    september.cost.forEach((a, i) =>
      expect(a.md).toBeCloseTo(expectedAuthors[i].md, 10),
    );
    expect(september.costMd).toBeCloseTo(1.875, 10);
    // At the epic level the two tasks merge: a blank display name shows the account id, and an
    // index `authors` lacks is normalized to "unknown" BEFORE summing, so it joins the null author's entry.
    const epicSeptember = matrix.epics[0].row.cells[1];
    expect(epicSeptember.cost.map((a) => [a.authorIndex, a.name])).toEqual([
      [0, "Ann"],
      [2, "acc-ghost"],
      [1, "Bob"],
      [null, null],
    ]);
    expect(epicSeptember.cost.map((a) => a.md)).toEqual([
      1.75,
      0.5,
      0.25,
      expect.closeTo(0.225, 10),
    ]);
    // A negative index is unknown too.
    const negative = deepDiveReport({
      authors: AUTHORS,
      tasks: [
        deepDiveTask("FLO-1", {
          cost: [
            { d: 0, a: -1, md: 1 },
            { d: 0, a: null, md: 2 },
          ],
        }),
      ],
    });
    expect(
      buildDeepDiveMatrix(negative, grainColumns(negative, "day")).epics[0]
        .tasks[0].cells[0].cost,
    ).toEqual([{ authorIndex: null, name: null, md: 3 }]);
    // The same author on different days at day grain stays split per day.
    const daily = buildDeepDiveMatrix(report, grainColumns(report, "day"));
    expect(daily.epics[0].tasks[0].cells[5].cost).toEqual([
      { authorIndex: 0, name: "Ann", md: 1 },
    ]);
  });

  test("done markers list in the cell they fall in, an epic's cell carries its tasks'", () => {
    const report = populated();
    const matrix = buildDeepDiveMatrix(report, grainColumns(report, "week"));
    const [alpha] = matrix.epics;
    const marker: DoneMarker = { taskKey: "FLO-10", day: 6, evMd: 2 };
    expect(alpha.tasks[0].cells[2].done).toEqual([marker]);
    expect(alpha.tasks[1].cells[3].done).toEqual([
      { taskKey: "FLO-11", day: 12, evMd: 1.5 },
    ]);
    expect(alpha.row.cells[2].done).toEqual([
      { taskKey: "FLO-10", day: 6, evMd: 2 },
    ]);
    expect(alpha.row.cells[2].evMd).toBe(2);
    expect(alpha.row.cells[0].done).toEqual([]);
  });

  test("done markers within a day sort by issue key number, not text", () => {
    const report = deepDiveReport({
      tasks: [
        deepDiveTask("FLO-10", { epicKey: "FLO-1", done: { d: 0, evMd: 1 } }),
        deepDiveTask("FLO-2", { epicKey: "FLO-1", done: { d: 0, evMd: 1 } }),
      ],
    });
    const cell = buildDeepDiveMatrix(report, grainColumns(report, "day"))
      .epics[0].row.cells[0];
    expect(cell.done.map((m) => m.taskKey)).toEqual(["FLO-2", "FLO-10"]);
  });

  test("epic rows follow the report's order, with the null-key No epic row and tasks by key", () => {
    const matrix = buildDeepDiveMatrix(
      populated(),
      grainColumns(populated(), "month"),
    );
    const kinds: MatrixRowKind[] = matrix.epics.flatMap((e) => [
      e.row.kind,
      ...e.tasks.map((t) => t.kind),
    ]);
    expect(new Set(kinds)).toEqual(new Set(["epic", "task"]));
    expect(matrix.epics.map((e) => [e.key, e.row.id])).toEqual([
      ["FLO-1", "epic:FLO-1"],
      ["FLO-2", "epic:FLO-2"],
      [null, "epic:"],
    ]);
    expect(matrix.epics.map((e) => e.tasks.map((t) => t.taskKey))).toEqual([
      ["FLO-10", "FLO-11"],
      ["FLO-20"],
      ["FLO-30"],
    ]);
    expect(matrix.epics[2].tasks[0].epicKey).toBeNull();
    expect(matrix.epics[0].summary).toBe("Alpha");
    expect(matrix.epics[0].budgetMd).toBe(9);
    expect(matrix.epics[0].tasks[0].plan).toEqual({
      planBasisMd: null,
      planSource: "NONE",
      noPlanReason: null,
    });
  });

  test("a task whose epic the report does not list still gets a row (never dropped)", () => {
    const report = deepDiveReport({
      tasks: [
        deepDiveTask("FLO-9", { epicKey: "FLO-404" }),
        deepDiveTask("FLO-8"),
      ],
    });
    const matrix = buildDeepDiveMatrix(report, grainColumns(report, "month"));
    expect(matrix.epics.map((e) => e.key)).toEqual(["FLO-404", null]);
  });

  test("the colour scales: PV and AC share one man-day maximum, execution has its own", () => {
    const report = populated();
    const matrix = buildDeepDiveMatrix(report, grainColumns(report, "month"));
    // The biggest MD cell is FLO-1's September cost/PV at epic level; the biggest task cell is below it.
    const alphaSeptember = matrix.epics[0].row.cells[1];
    expect(matrix.scale.mdMax).toBe(
      Math.max(alphaSeptember.pvMd, alphaSeptember.costMd),
    );
    expect(matrix.scale.execMax).toBeCloseTo(alphaSeptember.execTaskDays, 10);
    expect(matrix.taskScale.mdMax).toBeLessThan(matrix.scale.mdMax);
    expect(matrix.taskScale.mdMax).toBeCloseTo(2, 10);
    // Shared scale: a PV cell larger than any cost cell sets the maximum for both.
    const pvHeavy = deepDiveReport({
      tasks: [
        deepDiveTask("FLO-1", {
          pv: [{ d: 0, md: 8 }],
          cost: [{ d: 0, a: null, md: 1 }],
          exec: [{ d: 0, td: 0.5 }],
        }),
      ],
    });
    const heavy = buildDeepDiveMatrix(pvHeavy, grainColumns(pvHeavy, "day"));
    const expectedScale: LayerScale = { mdMax: 8, execMax: 0.5 };
    expect(heavy.scale).toEqual(expectedScale);
  });
});

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

describe("an epic's own worklogs (the '(on the epic)' line)", () => {
  const withOwnCost = () =>
    deepDiveReport({
      ...MONTH_CROSSING,
      authors: AUTHORS,
      epics: [
        deepDiveEpic("FLO-1", {
          ownCost: {
            cost: [
              { d: 5, a: 1, md: 0.5 },
              { d: 6, a: 1, md: 0.25 },
              { d: 0, a: null, md: 0.125 },
            ],
            totalMd: 2,
          },
        }),
        deepDiveEpic("FLO-2"),
      ],
      tasks: [
        deepDiveTask("FLO-10", {
          epicKey: "FLO-1",
          cost: [{ d: 5, a: 0, md: 1 }],
          totals: { pvMd: 0, execTaskDays: 0, evMd: 0, costMd: 1 },
        }),
      ],
    });

  test("is its own row, outside the epic row, with the whole-life total", () => {
    const report = withOwnCost();
    const matrix = buildDeepDiveMatrix(report, grainColumns(report, "month"));
    const [epic, plain] = matrix.epics;
    expect(plain.ownCost).toBeNull();
    expect(epic.ownCost).toMatchObject({
      id: "own:FLO-1",
      kind: "epicOwnCost",
      epicKey: "FLO-1",
      taskKey: null,
    });
    expect(epic.ownCost?.cells[1].cost).toEqual([
      { authorIndex: 1, name: "Bob", md: 0.75 },
    ]);
    expect(epic.ownCost?.cells[0].costMd).toBe(0.125);
    expect(epic.ownCost?.inRange.costMd).toBeCloseTo(0.875, 10);
    expect(epic.ownCost?.totals.costMd).toBe(2);
    // The epic row is exactly its tasks.
    expect(epic.row.cells[1].costMd).toBe(1);
    expect(epic.row.totals.costMd).toBe(1);
    expect(matrix.totals.costMd).toBe(1);
    expect(matrix.ownCostTotalMd).toBe(2);
  });

  test("counts toward the colour scales", () => {
    const report = withOwnCost();
    const matrix = buildDeepDiveMatrix(report, grainColumns(report, "month"));
    expect(matrix.taskScale.mdMax).toBe(1);
    const big = withOwnCost();
    big.epics[0].ownCost = { cost: [{ d: 5, a: 0, md: 6 }], totalMd: 6 };
    expect(
      buildDeepDiveMatrix(big, grainColumns(big, "month")).taskScale.mdMax,
    ).toBe(6);
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

  test("EMPTY_CELL is deep-frozen and shared by every empty cell", () => {
    expect(Object.isFrozen(EMPTY_CELL)).toBe(true);
    expect(Object.isFrozen(EMPTY_CELL.done)).toBe(true);
    expect(Object.isFrozen(EMPTY_CELL.cost)).toBe(true);
    // The types are read-only too: these lines fail to compile without the directives.
    // @ts-expect-error cells are read-only
    expect(() => (EMPTY_CELL.pvMd = 1)).toThrow(TypeError);
    // @ts-expect-error cell arrays are read-only
    const pushDone = () => EMPTY_CELL.done.push({ taskKey: "X-1", day: 0, evMd: 1 });
    expect(pushDone).toThrow(TypeError);
    const report = deepDiveReport({ tasks: [deepDiveTask("FLO-1")] });
    expect(
      buildDeepDiveMatrix(report, grainColumns(report, "day")).epics[0].tasks[0]
        .cells[0],
    ).toBe(EMPTY_CELL);
  });

  test("a derived report with no rows is empty without a note", () => {
    const report = deepDiveReport();
    const matrix = buildDeepDiveMatrix(report, grainColumns(report, "week"));
    expect(matrix.derived).toBe(true);
    expect(matrix.note).toBeNull();
    expect(matrix.epics).toEqual([]);
    expect(matrix.mode).toBe("EPICS");
  });

  test("RANGE_CLAMPED passes through with its text; any other note is OTHER", () => {
    const text =
      "RANGE_CLAMPED: the selection spans more than 1100 days, so only 1100 days are shown";
    const clamped = deepDiveReport({ note: text });
    expect(
      buildDeepDiveMatrix(clamped, grainColumns(clamped, "month")).note,
    ).toEqual({ kind: "RANGE_CLAMPED", text });
    const other = deepDiveReport({ note: "something else" });
    expect(
      buildDeepDiveMatrix(other, grainColumns(other, "month")).note,
    ).toEqual({ kind: "OTHER", text: "something else" });
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

  test("the longest range (1100 days) still partitions at every grain", () => {
    const report = deepDiveReport({
      range: { from: "2023-01-01", to: "2026-01-04", asOfDay: null },
    });
    const days = grainColumns(report, "day");
    expect(days).toHaveLength(1100);
    expectPartition(grainColumns(report, "week"), 1100);
    expectPartition(days, 1100);
  });
});

describe("formatFigure", () => {
  test("rounds only for display, trims trailing zeros and never prints negative zero or exponents", () => {
    expect(formatFigure(0.1 + 0.2)).toBe("0.3");
    expect(formatFigure(2)).toBe("2");
    expect(formatFigure(1.005 * 100)).toBe("100.5");
    expect(formatFigure(0.3333, 4)).toBe("0.3333");
    expect(formatFigure(0.004)).toBe("0");
    expect(formatFigure(-0.001)).toBe("0");
    expect(formatFigure(12.3456)).toBe("12.35");
    // Binary sums just below a decimal half still round as the decimal does (no double rounding).
    expect(formatFigure(0.3509 + 0.1441)).toBe("0.5");
    expect(formatFigure(0.9279 + 0.5871)).toBe("1.52");
    expect(formatFigure(-1.515)).toBe("-1.52");
  });
});
