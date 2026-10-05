import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { MONTH_CROSSING, deepDiveEpic, deepDiveReport, deepDiveTask } from "../test/deepDiveFixtures";
import { barPercent, bucketLayers, dayDate, epicPlanText, visibleRows } from "./deepDiveCell";
import { headerModel } from "./deepDiveHeader";
import { buildDeepDiveMatrix, expandedColumns, grainColumns } from "./deepDiveMatrix";

const t = i18n.t.bind(i18n);

function matrixOf(expanded: string[] = []) {
  const report = deepDiveReport({
    ...MONTH_CROSSING,
    epics: [
      deepDiveEpic("E-1", { plannedStart: 3, plannedDue: 8, budgetMd: 5 }),
      deepDiveEpic("E-2"),
    ],
    tasks: [deepDiveTask("FLO-1", { epicKey: "E-1" }), deepDiveTask("FLO-2", { epicKey: "E-2" })],
  });
  const columns = expandedColumns(report, new Set(expanded));
  const lineage = new Map([...grainColumns(report, "month"), ...grainColumns(report, "week")].map((c) => [c.id, c]));
  return { report, columns, lineage, matrix: buildDeepDiveMatrix(report, columns) };
}

describe("visibleRows", () => {
  test("a shared cache hands back the same row objects for rows an epic toggle did not touch", () => {
    const { matrix } = matrixOf();
    const cache = new Map();
    const closed = visibleRows(matrix.epics, new Set(), cache);
    const open = visibleRows(matrix.epics, new Set(["epic:E-1"]), cache);
    expect(closed.map((r) => r.row.id)).toEqual(["epic:E-1", "epic:E-2"]);
    expect(open.map((r) => r.row.id)).toEqual(["epic:E-1", "task:FLO-1", "epic:E-2"]);
    expect(open[2]).toBe(closed[1]); // E-2 is untouched: the memoised row is not re-rendered
    expect(open[0]).not.toBe(closed[0]); // E-1 flipped to expanded
    expect(visibleRows(matrix.epics, new Set(["epic:E-1"]), cache)[1]).toBe(open[1]);
  });

  test("a row is rebuilt when its MatrixRow is (a new column partition)", () => {
    const first = matrixOf();
    const second = matrixOf(["month:2026-09"]);
    const cache = new Map();
    const a = visibleRows(first.matrix.epics, new Set(), cache);
    const b = visibleRows(second.matrix.epics, new Set(), cache);
    expect(b[0]).not.toBe(a[0]);
    expect(b[0].row).toBe(second.matrix.epics[0].row);
  });
});

describe("headerModel", () => {
  test("months only: one row of leaves", () => {
    const { columns, lineage } = matrixOf();
    const model = headerModel(columns, lineage);
    expect(model.depth).toBe(1);
    expect(model.cornerRow).toBe(0);
    expect(model.rows[0].map((c) => [c.role, c.column.id, c.r, c.c, c.colSpan, c.rowSpan])).toEqual([
      ["leaf", "month:2026-08", 0, 1, 1, 1],
      ["leaf", "month:2026-09", 0, 2, 1, 1],
    ]);
  });

  test("an open month is a group over its weeks; the shallower month spans both rows", () => {
    const { columns, lineage } = matrixOf(["month:2026-09"]);
    const model = headerModel(columns, lineage);
    expect(model.depth).toBe(2);
    expect(model.cornerRow).toBe(-1);
    expect(model.rows[0].map((c) => [c.role, c.column.id, c.r, c.c, c.colSpan, c.rowSpan])).toEqual([
      ["leaf", "month:2026-08", -1, 1, 1, 2],
      ["group", "month:2026-09", -1, 2, 2, 1],
    ]);
    expect(model.rows[1].map((c) => [c.role, c.column.label, c.r, c.c, c.colSpan, c.rowSpan])).toEqual([
      ["leaf", "2026-W36", 0, 2, 1, 1],
      ["leaf", "2026-W37", 0, 3, 1, 1],
    ]);
  });

  test("three levels: a week's days sit under the open week and the open month", () => {
    const { columns, lineage } = matrixOf(["month:2026-09", "week:2026-09-07"]);
    const model = headerModel(columns, lineage);
    expect(model.depth).toBe(3);
    const group = (row: number) => model.rows[row].filter((c) => c.role === "group").map((c) => [c.column.id, c.c, c.colSpan]);
    expect(group(0)).toEqual([["month:2026-09", 2, 3]]);
    expect(group(1)).toEqual([["week:2026-09-07", 3, 2]]);
    expect(model.rows[2].map((c) => c.column.label)).toEqual(["2026-09-07", "2026-09-08"]);
    // W36 is a leaf one level above the days: it spans down to the day row
    expect(model.rows[1].find((c) => c.column.label === "2026-W36")?.rowSpan).toBe(2);
    // every leaf's span reaches the bottom header row (r = 0)
    for (const row of model.rows) for (const c of row) if (c.role === "leaf") expect(c.r + c.rowSpan - 1).toBe(0);
  });
});

describe("epic plan text and dates", () => {
  test("day offsets become dates from range.from; the plan line holds the window and the budget", () => {
    const { matrix } = matrixOf();
    expect(dayDate("2026-08-27", 3)).toBe("2026-08-30");
    expect(dayDate("2026-08-27", -10)).toBe("2026-08-17");
    expect(epicPlanText(matrix.epics[0], matrix.range.from, t)).toBe("Planned 2026-08-30 to 2026-09-04 · Budget 5 MD");
    expect(epicPlanText(matrix.epics[1], matrix.range.from, t)).toBeNull();
  });
});

describe("small helpers", () => {
  test("barPercent clamps to 1..100 and is 0 for nothing", () => {
    expect(barPercent(0, 5)).toBe(0);
    expect(barPercent(1, 0)).toBe(0);
    expect(barPercent(0.001, 100)).toBe(1);
    expect(barPercent(50, 100)).toBe(50);
    expect(barPercent(500, 100)).toBe(100);
  });

  test("execution brings its earned-value line along in the figures table", () => {
    expect(bucketLayers({ pv: true, exec: true, cost: true })).toEqual(["pv", "exec", "ev", "cost"]);
    expect(bucketLayers({ pv: false, exec: true, cost: false })).toEqual(["exec", "ev"]);
    expect(bucketLayers({ pv: false, exec: false, cost: false })).toEqual([]);
  });
});
