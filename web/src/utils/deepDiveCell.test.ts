import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { MONTH_CROSSING, deepDiveEpic, deepDiveReport, deepDiveTask } from "../test/deepDiveFixtures";
import { barPercent, bucketLayers, cellFacts, cellHasFigures, cellSections, dayDate, epicPlanText, factsSummary, visibleRows } from "./deepDiveCell";
import { EMPTY_CELL, type MatrixCell } from "./deepDiveAggregate";
import { headerModel } from "./deepDiveHeader";
import { expandedColumns, grainColumns } from "./deepDiveCalendar";
import { buildDeepDiveMatrix } from "./deepDiveMatrix";

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

  test("approximate EV brings its earned-value line along in the figures table", () => {
    expect(bucketLayers({ pv: true, aev: true, cost: true })).toEqual(["pv", "aev", "ev", "cost"]);
    expect(bucketLayers({ pv: false, aev: true, cost: false })).toEqual(["aev", "ev"]);
    expect(bucketLayers({ pv: false, aev: false, cost: false })).toEqual([]);
  });
});

describe("cellFacts for the Approximate EV layer", () => {
  const cell: MatrixCell = {
    ...EMPTY_CELL,
    approxEvMd: 1.5,
    evMd: 3,
    done: [{ taskKey: "FLO-1", day: 2, evMd: 3 }],
    unearned: [
      { taskKey: "FLO-7", state: "OPEN", days: 3 },
      { taskKey: "FLO-9", state: "UNESTIMATED", days: 1 },
    ],
  };
  const all = { pv: true, aev: true, cost: true };

  test("the tooltip says the approximate EV, the EV, the done tasks and who was worked on without earning", () => {
    const facts = cellFacts(cell, all, t);
    expect(facts.aev).toBe("Approximate EV: 1.5 MD");
    expect(facts.ev).toBe("Earned value (EV): 3 MD");
    expect(facts.done).toBe("Done: 1 (FLO-1)");
    expect(facts.unearned).toBe("In progress, nothing earned: FLO-7 (not done yet, 3 days), FLO-9 (no estimate, 1 day)");
    expect(factsSummary(facts)).toContain("Approximate EV: 1.5 MD; Earned value (EV): 3 MD; Done: 1 (FLO-1); In progress, nothing earned: FLO-7");
  });

  test("the tooltip sections hold the same figures: heading and total, then done, worked-not-earned and author rows", () => {
    const withCost: MatrixCell = { ...cell, pvMd: 2, costMd: 2, cost: [{ authorIndex: 0, name: "Ann Lee", md: 1.5 }, { authorIndex: null, name: null, md: 0.5 }] };
    const sections = cellSections(withCost, all, t);
    expect(sections.map((section) => section.id)).toEqual(["pv", "aev", "ev", "unearned", "cost"]);
    expect(sections.map((section) => [section.heading, section.value])).toEqual([
      ["Plan (PV)", "2 MD"],
      ["Approximate EV", "1.5 MD"],
      ["Earned value (EV)", "3 MD"],
      ["In progress, nothing earned", null],
      ["Cost (AC)", "2 MD"],
    ]);
    expect(sections[2].rows).toEqual([{ label: "Done", value: "1 (FLO-1)" }]);
    expect(sections[3].rows).toEqual([
      { label: "FLO-7", value: "not done yet, 3 days" },
      { label: "FLO-9", value: "no estimate, 1 day" },
    ]);
    expect(sections[4].rows).toEqual([
      { label: "Ann Lee", value: "1.5 MD" },
      { label: "unknown author", value: "0.5 MD" },
    ]);
  });

  test("sections are omitted with their layer, and the lists add rows only when they hold something", () => {
    const bare = cellSections(EMPTY_CELL, all, t);
    expect(bare.map((section) => section.id)).toEqual(["pv", "aev", "ev", "cost"]);
    expect(bare.every((section) => section.rows.length === 0)).toBe(true);
    expect(cellSections(cell, { pv: false, aev: false, cost: true }, t).map((section) => section.id)).toEqual(["cost"]);
    expect(cellSections(cell, { pv: false, aev: false, cost: false }, t)).toEqual([]);
  });

  test("a cell with only a hollow mark still has figures; with the layer off it has none", () => {
    const hollow: MatrixCell = { ...EMPTY_CELL, unearned: [{ taskKey: "FLO-7", state: "OPEN", days: 1 }] };
    expect(cellHasFigures(hollow, all)).toBe(true);
    expect(cellHasFigures(hollow, { pv: true, aev: false, cost: true })).toBe(false);
    expect(cellFacts(hollow, { pv: true, aev: false, cost: true }, t).unearned).toBeNull();
  });
});
