import { describe, expect, test } from "vitest";
import type { DeepDiveReport } from "../api/reports";
import { MONTH_CROSSING, deepDiveEpic, deepDiveReport, deepDiveTask } from "../test/deepDiveFixtures";
import { buildDeepDiveBurnup, type DeepDiveBurnup } from "./deepDiveBurnup";
import { buildDeepDiveMatrix, grainColumns } from "./deepDiveMatrix";

// MONTH_CROSSING: Thu 2026-08-27 (offset 0) … Tue 2026-09-08 (offset 12), non-working offsets 2, 3, 9, 10.
const DAYS = 13;

const col = (burnup: DeepDiveBurnup, key: "pv" | "ev" | "ac" | "budget") => burnup.points.map((p) => p[key]);

function report(partial: Partial<DeepDiveReport> = {}): DeepDiveReport {
  return deepDiveReport({ ...MONTH_CROSSING, ...partial });
}

/** Three tasks over two epics plus an epic's own worklogs; figures chosen so every layer has several days. */
function sample(partial: Partial<DeepDiveReport> = {}): DeepDiveReport {
  return report({
    mode: "EPICS",
    authors: [{ accountId: "a1", displayName: "Ann Lee" }],
    epics: [
      deepDiveEpic("FLO-1", { ownCost: { cost: [{ d: 4, a: 0, md: 0.5 }], totalMd: 1.25 } }),
      deepDiveEpic("FLO-2"),
      deepDiveEpic(null),
    ],
    tasks: [
      deepDiveTask("FLO-11", {
        epicKey: "FLO-1",
        pv: [
          { d: 1, md: 1.1 },
          { d: 4, md: 0.7 },
        ],
        cost: [
          { d: 1, a: 0, md: 0.4 },
          { d: 5, a: null, md: 1.2 },
        ],
        done: { d: 6, evMd: 2.3 },
      }),
      deepDiveTask("FLO-21", {
        epicKey: "FLO-2",
        pv: [{ d: 11, md: 3.05 }],
        cost: [{ d: 12, a: 0, md: 0.15 }],
        done: { d: 12, evMd: 3 },
      }),
      deepDiveTask("FLO-31", { epicKey: null }),
    ],
    ...partial,
  });
}

describe("buildDeepDiveBurnup — the cumulative lines", () => {
  test("one point per day of the range, dates in ISO form, working days from the calendar", () => {
    const burnup = buildDeepDiveBurnup(sample());
    expect(burnup.derived).toBe(true);
    expect(burnup.points).toHaveLength(DAYS);
    expect(burnup.points[0]).toMatchObject({ day: 0, date: "2026-08-27", nonWorking: false });
    expect(burnup.points[2]).toMatchObject({ day: 2, date: "2026-08-29", nonWorking: true });
    expect(burnup.points[12]).toMatchObject({ day: 12, date: "2026-09-08", nonWorking: false });
  });

  test("every line starts at zero on the first day and never decreases", () => {
    const burnup = buildDeepDiveBurnup(sample());
    expect(burnup.points[0]).toMatchObject({ pv: 0, ev: 0, ac: 0 });
    for (const key of ["pv", "ev", "ac"] as const) {
      const line = col(burnup, key) as number[];
      line.slice(1).forEach((value, i) => expect(value).toBeGreaterThanOrEqual(line[i]));
    }
  });

  test("the lines step on the days the entries fall on", () => {
    const burnup = buildDeepDiveBurnup(sample());
    expect(col(burnup, "pv").map((v) => Number(v!.toFixed(2)))).toEqual([0, 1.1, 1.1, 1.1, 1.8, 1.8, 1.8, 1.8, 1.8, 1.8, 1.8, 4.85, 4.85]);
    // EV: the done marker on its day. AC: the tasks' cost plus the epic's own 0.5 on day 4.
    expect(col(burnup, "ev").map((v) => Number(v!.toFixed(2)))).toEqual([0, 0, 0, 0, 0, 0, 2.3, 2.3, 2.3, 2.3, 2.3, 2.3, 5.3]);
    expect(col(burnup, "ac").map((v) => Number(v!.toFixed(2)))).toEqual([0, 0.4, 0.4, 0.4, 0.9, 2.1, 2.1, 2.1, 2.1, 2.1, 2.1, 2.1, 2.25]);
  });

  test("the last figures are the matrix model's in-range totals (own cost counted into AC)", () => {
    const input = sample();
    const matrix = buildDeepDiveMatrix(input, grainColumns(input, "month"));
    const ownCost = matrix.epics.reduce((sum, epic) => sum + (epic.ownCost?.inRange.costMd ?? 0), 0);
    const last = buildDeepDiveBurnup(input).points[DAYS - 1];
    expect(last.pv).toBeCloseTo(matrix.inRange.pvMd, 9);
    expect(last.ev).toBeCloseTo(matrix.inRange.evMd, 9);
    expect(last.ac).toBeCloseTo(matrix.inRange.costMd + ownCost, 9);
    expect(ownCost).toBeCloseTo(0.5, 9);
  });

  test("entries outside the range are left out, like the matrix leaves them out", () => {
    const input = report({
      tasks: [
        deepDiveTask("FLO-1", {
          pv: [
            { d: -1, md: 9 },
            { d: 0, md: 1 },
            { d: DAYS, md: 9 },
          ],
          cost: [{ d: DAYS + 4, a: 0, md: 9 }],
          done: { d: -3, evMd: 9 },
        }),
      ],
    });
    const last = buildDeepDiveBurnup(input).points[DAYS - 1];
    expect(last).toMatchObject({ pv: 1, ev: 0, ac: 0 });
  });

  test("says whether the epics' own cost is in the AC line", () => {
    expect(buildDeepDiveBurnup(sample()).includesEpicOwnCost).toBe(true);
    expect(buildDeepDiveBurnup(sample({ mode: "SPRINTS", epics: [deepDiveEpic("FLO-1")] })).includesEpicOwnCost).toBe(false);
  });
});

describe("buildDeepDiveBurnup — the as-of day", () => {
  const withAsOf = (asOfDay: string | null) =>
    sample({ range: { from: "2026-08-27", to: "2026-09-08", asOfDay } });

  test("earned value and cost stop after the as-of day; the plan runs on", () => {
    // 2026-08-31 is offset 4; the later cost on day 12 still extends the actuals to the end.
    const burnup = buildDeepDiveBurnup(
      sample({
        range: { from: "2026-08-27", to: "2026-09-08", asOfDay: "2026-08-31" },
        tasks: [deepDiveTask("FLO-11", { pv: [{ d: 1, md: 1 }, { d: 11, md: 2 }], cost: [{ d: 1, a: 0, md: 1 }], done: { d: 3, evMd: 1 } })],
        epics: [deepDiveEpic("FLO-1")],
      }),
    );
    expect(burnup.asOfDate).toBe("2026-08-31");
    expect(col(burnup, "ev").slice(0, 6)).toEqual([0, 0, 0, 1, 1, null]);
    expect(col(burnup, "ac").slice(4, 7)).toEqual([1, null, null]);
    expect(col(burnup, "pv").at(-1)).toBe(3);
  });

  test("an entry after the as-of day still counts: the actuals run to it", () => {
    const burnup = buildDeepDiveBurnup(withAsOf("2026-08-31"));
    // FLO-21 is done and logged on day 12, after the as-of day 4: the lines carry it to the end, never drop it.
    expect(burnup.points[12].ev).toBeCloseTo(5.3, 9);
    expect(burnup.points[12].ac).toBeCloseTo(2.25, 9);
    expect(col(burnup, "ev").every((v) => v !== null)).toBe(true);
  });

  test("an as-of day past the range's end changes nothing and is not marked", () => {
    const burnup = buildDeepDiveBurnup(withAsOf("2026-10-01"));
    expect(burnup.asOfDate).toBeNull();
    expect(col(burnup, "ev").every((v) => v !== null)).toBe(true);
  });

  test("an as-of day before the range's start leaves no actuals at all", () => {
    const burnup = buildDeepDiveBurnup(
      report({ range: { from: "2026-08-27", to: "2026-09-08", asOfDay: "2026-08-01" }, tasks: [deepDiveTask("FLO-1", { pv: [{ d: 2, md: 1 }] })] }),
    );
    expect(burnup.asOfDate).toBeNull();
    expect(col(burnup, "ev").every((v) => v === null)).toBe(true);
    expect(col(burnup, "ac").every((v) => v === null)).toBe(true);
    expect(burnup.points[12].pv).toBe(1);
    expect(burnup.empty).toBe(false);
  });
});

describe("buildDeepDiveBurnup — the epics' budget plan", () => {
  const budgets = (epics: ReturnType<typeof deepDiveEpic>[]) => buildDeepDiveBurnup(report({ epics }));
  const line = (burnup: DeepDiveBurnup) => col(burnup, "budget") as number[];

  test("no epic with a window and a budget means no line and no values", () => {
    const burnup = budgets([
      deepDiveEpic("FLO-1", { plannedStart: 0, plannedDue: 4 }),
      deepDiveEpic("FLO-2", { budgetMd: 5 }),
      deepDiveEpic("FLO-3", { plannedStart: 4, plannedDue: 0, budgetMd: 5 }),
      deepDiveEpic("FLO-4", { plannedStart: 0, plannedDue: 4, budgetMd: 0 }),
      deepDiveEpic(null),
    ]);
    expect(burnup.hasBudget).toBe(false);
    expect(col(burnup, "budget").every((v) => v === null)).toBe(true);
  });

  test("a budget spreads over the working days of its window with the running two-decimal rule", () => {
    // Window offsets 0..4 holds the working days 0, 1 and 4 (2 and 3 are the weekend): 3.33, 3.34, 3.33.
    const burnup = budgets([deepDiveEpic("FLO-1", { plannedStart: 0, plannedDue: 4, budgetMd: 10 })]);
    expect(burnup.hasBudget).toBe(true);
    expect(line(burnup).slice(0, 6)).toEqual([3.33, 6.67, 6.67, 6.67, 10, 10]);
    expect(line(burnup).at(-1)).toBe(10);
  });

  test("a half-cent rounds up, and the pieces still sum to the budget", () => {
    // 0.05 over the two working days 0 and 1: ROUND(0.025 * 100) = 3 hundredths, then the remaining 2.
    const burnup = budgets([deepDiveEpic("FLO-1", { plannedStart: 0, plannedDue: 1, budgetMd: 0.05 })]);
    expect(line(burnup).slice(0, 3)).toEqual([0.03, 0.05, 0.05]);
  });

  test("a window starting before the range keeps its full length: only the part inside the range is drawn", () => {
    // Offsets -3..4: Mon, Tue, Wed before the range are 3 working days, then 0, 1 and 4: n = 6, 2 MD a day.
    const burnup = budgets([deepDiveEpic("FLO-1", { plannedStart: -3, plannedDue: 4, budgetMd: 12 })]);
    expect(line(burnup).slice(0, 5)).toEqual([2, 4, 4, 4, 6]);
  });

  test("a weekend before the range does not count as a working day", () => {
    // Offsets -5..0: Sat, Sun, Mon, Tue, Wed are 3 working days, plus day 0: n = 4, so day 0 holds 8 / 4.
    const burnup = budgets([deepDiveEpic("FLO-1", { plannedStart: -5, plannedDue: 0, budgetMd: 8 })]);
    expect(line(burnup)[0]).toBe(2);
    expect(line(burnup).at(-1)).toBe(2);
  });

  test("a window ending after the range keeps its full length too (days past the end count Monday to Friday)", () => {
    // Offsets 8..20: inside 8, 11, 12; after: Wed 09-09 … Wed 09-16 hold 6 weekdays: n = 9, 1 MD a day.
    const burnup = budgets([deepDiveEpic("FLO-1", { plannedStart: 8, plannedDue: 20, budgetMd: 9 })]);
    expect(line(burnup)).toEqual([0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 2, 3]);
  });

  test("a window spanning the whole range and more shows its share of the range", () => {
    // Before: -3..-1 (3), after: 13..20 (6), inside: the 9 working days of the range → n = 18, 1 MD a day.
    const burnup = budgets([deepDiveEpic("FLO-1", { plannedStart: -3, plannedDue: 20, budgetMd: 18 })]);
    expect(line(burnup).at(-1)).toBe(9);
  });

  test("a window wholly outside the range, or inside only non-working days, draws nothing", () => {
    expect(budgets([deepDiveEpic("FLO-1", { plannedStart: -9, plannedDue: -2, budgetMd: 5 })]).hasBudget).toBe(false);
    expect(budgets([deepDiveEpic("FLO-1", { plannedStart: 14, plannedDue: 30, budgetMd: 5 })]).hasBudget).toBe(false);
    expect(budgets([deepDiveEpic("FLO-1", { plannedStart: 2, plannedDue: 3, budgetMd: 5 })]).hasBudget).toBe(false);
  });

  test("epics add up; an epic without a plan adds nothing", () => {
    const burnup = budgets([
      deepDiveEpic("FLO-1", { plannedStart: 0, plannedDue: 1, budgetMd: 4 }),
      deepDiveEpic("FLO-2", { plannedStart: 11, plannedDue: 12, budgetMd: 1 }),
      deepDiveEpic("FLO-3"),
    ]);
    expect(line(burnup)).toEqual([2, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4.5, 5]);
  });

  test("the budget line does not need any task", () => {
    const burnup = budgets([deepDiveEpic("FLO-1", { plannedStart: 0, plannedDue: 1, budgetMd: 2 })]);
    expect(burnup.empty).toBe(false);
    expect(col(burnup, "pv").every((v) => v === 0)).toBe(true);
  });

  test("the budget line is monotone", () => {
    const burnup = budgets([
      deepDiveEpic("FLO-1", { plannedStart: -4, plannedDue: 9, budgetMd: 7.77 }),
      deepDiveEpic("FLO-2", { plannedStart: 3, plannedDue: 40, budgetMd: 0.31 }),
    ]);
    line(burnup).slice(1).forEach((value, i) => expect(value).toBeGreaterThanOrEqual(line(burnup)[i]));
  });
});

describe("buildDeepDiveBurnup — empty and not derived", () => {
  test("nothing derived is no days at all, not a range of zeros", () => {
    const burnup = buildDeepDiveBurnup(
      report({ range: { from: "2026-08-27", to: "2026-09-08", asOfDay: null }, tasks: [deepDiveTask("FLO-1", { pv: [{ d: 0, md: 1 }] })] }),
    );
    expect(burnup).toMatchObject({ derived: false, points: [], hasBudget: false, asOfDate: null, empty: true });
  });

  test("a derived selection with no figures is one row of zeros per day and says it is empty", () => {
    const burnup = buildDeepDiveBurnup(report());
    expect(burnup.derived).toBe(true);
    expect(burnup.empty).toBe(true);
    expect(burnup.points).toHaveLength(DAYS);
    expect(burnup.points.every((p) => p.pv === 0 && p.ev === 0 && p.ac === 0 && p.budget === null)).toBe(true);
  });

  test("a one-day range is one point", () => {
    const burnup = buildDeepDiveBurnup(deepDiveReport({ tasks: [deepDiveTask("FLO-1", { pv: [{ d: 0, md: 2 }] })] }));
    expect(burnup.points).toHaveLength(1);
    expect(burnup.points[0]).toMatchObject({ date: "2026-09-01", pv: 2 });
    expect(burnup.empty).toBe(false);
  });
});
