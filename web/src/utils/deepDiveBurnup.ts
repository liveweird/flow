import type { DeepDiveReport } from "../api/reports";
import { grainColumns } from "./deepDiveCalendar";
import { DAY_MS, isoDateToEpochMillis } from "./isoDate";
import { roundScaled } from "./reportFormat";

/**
 * The Deep dive's burn-up: a `DeepDiveReport` (sparse daily series, day offsets from `range.from`) turned into
 * ONE cumulative row per day of the range — plan (PV), earned value (EV), cost (AC) and, optionally, the epics'
 * budget plan. Pure client-side arithmetic over the response the matrix already uses.
 *
 * Every line starts at 0 on `range.from` and counts only what falls INSIDE the shown range (whole-life totals
 * live in the matrix's summary table), so a line's last figure is the matrix model's `inRange` total for that
 * layer. Sums stay in plain numbers and are rounded only for display (`formatFigure`); the budget plan is summed
 * in whole hundredths (its rule is a rounding rule), so it is exact to two decimals.
 *
 *  - PV: the tasks' `pv` entries. EV: each task's `done.evMd` on its done day. AC: the tasks' `cost` entries
 *    plus the epics' own worklogs (`ownCost` — the server sends it in EPICS mode only).
 *  - EV and AC are ACTUALS: they end on the last day the data is current for (`range.asOfDay`, or a later day
 *    that still holds an entry) and are `null` after it — a line that stays flat over days that have not
 *    happened yet would read as "nothing got done". PV and the budget plan are plans and run to the end.
 *  - The budget plan: each epic with a planned window and a budget spreads it evenly over the working days of
 *    its window with the report-15 rule (`ROUND(total * i / n, 2)` running, so the pieces sum to the budget
 *    exactly); a window reaching beyond the range keeps its full length, and the line shows the part that falls
 *    inside the range. For the days OUTSIDE the shown range this ASSUMES a Monday–Friday week and no holidays:
 *    the configured weekend and holidays (`nonWorkingDays`) are only known inside the range.
 */

interface BurnupPoint {
  /** Offset from `range.from`. */
  day: number;
  /** ISO date. */
  date: string;
  nonWorking: boolean;
  /** Cumulative man-days from `range.from` to this day, inclusive. */
  pv: number;
  /** `null` after the last day the actuals are current for. */
  ev: number | null;
  ac: number | null;
  /** The cumulative budget plan; `null` on every day when no epic has one (`DeepDiveBurnup.hasBudget`). */
  budget: number | null;
}

export interface DeepDiveBurnup {
  /** False when nothing has derived (`range.asOfDay` null): no days, not a range of zeros. */
  derived: boolean;
  points: BurnupPoint[];
  hasBudget: boolean;
  /** `range.asOfDay` when it falls inside the range (where the actuals end), else `null`. */
  asOfDate: string | null;
  /** Some epic has worklogs logged on itself, and the AC line includes them. */
  includesEpicOwnCost: boolean;
  /** Nothing to plot: no plan, no earned value, no cost and no budget line. */
  empty: boolean;
}

const isDay = (d: number, days: number) => Number.isInteger(d) && d >= 0 && d < days;

/** Hundredths of `value`, rounded by the one figure rule (`roundScaled`, as `formatFigure` does). */
const hundredths = (value: number) => roundScaled(value, 2);

/** How many Monday-to-Friday days lie before the epoch-day-indexed `m` (Monday = 0 after the +3 shift; floor-mod safe for negatives). */
const weekdaysBefore = (m: number) => 5 * Math.floor(m / 7) + Math.min(((m % 7) + 7) % 7, 5);

/** Monday-to-Friday days among the epoch days `fromEpochDay`..`toEpochDay`, both inclusive; 0 for an empty span. */
function weekdaysBetween(fromEpochDay: number, toEpochDay: number): number {
  return toEpochDay < fromEpochDay ? 0 : weekdaysBefore(toEpochDay + 4) - weekdaysBefore(fromEpochDay + 3);
}

/**
 * One epic's budget plan inside the range, as hundredths per day offset (index = offset). `null` when the epic
 * has no budget, no usable window, or its window holds no working day to spread over.
 */
function epicBudgetPlan(
  epic: DeepDiveReport["epics"][number],
  days: number,
  fromEpochDay: number,
  nonWorking: ReadonlySet<number>,
): number[] | null {
  const { plannedStart: start, plannedDue: due, budgetMd } = epic;
  if (start === null || due === null || start > due || budgetMd === null || budgetMd <= 0) return null;
  const last = days - 1;
  // Working days of the window before the range, inside it (listed) and after it: n is their total.
  const before = start < 0 ? weekdaysBetween(fromEpochDay + start, fromEpochDay + Math.min(due, -1)) : 0;
  const after = due > last ? weekdaysBetween(fromEpochDay + Math.max(start, days), fromEpochDay + due) : 0;
  const inside: number[] = [];
  for (let d = Math.max(start, 0); d <= Math.min(due, last); d += 1) if (!nonWorking.has(d)) inside.push(d);
  // A window with no working day inside the range draws nothing (it lies outside it, or holds only non-working days).
  if (inside.length === 0) return null;
  const n = before + inside.length + after;
  const plan = new Array<number>(days).fill(0);
  const cumulative = (i: number) => hundredths((budgetMd * i) / n);
  inside.forEach((d, k) => {
    const i = before + k + 1;
    plan[d] = cumulative(i) - cumulative(i - 1);
  });
  return plan;
}

/** The cumulative sums of a per-day delta list. */
function running(deltas: readonly number[]): number[] {
  let total = 0;
  return deltas.map((delta) => {
    total += delta;
    return total;
  });
}

export function buildDeepDiveBurnup(report: DeepDiveReport): DeepDiveBurnup {
  const asOf = report.range.asOfDay;
  if (asOf === null) return { derived: false, points: [], hasBudget: false, asOfDate: null, includesEpicOwnCost: false, empty: true };

  const columns = grainColumns(report, "day");
  const days = columns.length;
  const fromEpochDay = Math.round(isoDateToEpochMillis(report.range.from) / DAY_MS);
  const nonWorking = new Set(report.nonWorkingDays);

  const pv = new Array<number>(days).fill(0);
  const ev = new Array<number>(days).fill(0);
  const ac = new Array<number>(days).fill(0);
  let lastActual = -1;
  const addActual = (series: number[], d: number, md: number) => {
    if (!isDay(d, days)) return;
    series[d] += md;
    lastActual = Math.max(lastActual, d);
  };
  for (const task of report.tasks) {
    for (const e of task.pv) if (isDay(e.d, days)) pv[e.d] += e.md;
    for (const e of task.cost) addActual(ac, e.d, e.md);
    if (task.done !== null) addActual(ev, task.done.d, task.done.evMd);
  }
  let includesEpicOwnCost = false;
  for (const epic of report.epics) {
    if (epic.ownCost === null) continue;
    includesEpicOwnCost = true;
    for (const e of epic.ownCost.cost) addActual(ac, e.d, e.md);
  }

  const budgetDeltas = new Array<number>(days).fill(0);
  let hasBudget = false;
  for (const epic of report.epics) {
    const plan = epicBudgetPlan(epic, days, fromEpochDay, nonWorking);
    if (plan === null) continue;
    hasBudget = true;
    plan.forEach((cents, d) => {
      budgetDeltas[d] += cents;
    });
  }

  const pvLine = running(pv);
  const evLine = running(ev);
  const acLine = running(ac);
  const budgetLine = running(budgetDeltas).map((cents) => cents / 100);
  // The actuals end on the as-of day when it is in the range; beyond the range's end (a closed range) they run to the
  // end, before its start they have not begun (ISO dates compare as text).
  const asOfDay = columns.findIndex((col) => col.fromDate === asOf);
  const asOfEnd = asOf < report.range.from ? -1 : asOfDay >= 0 ? asOfDay : days - 1;
  const actualsEnd = Math.max(asOfEnd, lastActual);

  const points = columns.map((col, d): BurnupPoint => ({
    day: d,
    date: col.fromDate,
    nonWorking: col.nonWorking,
    pv: pvLine[d],
    ev: d <= actualsEnd ? evLine[d] : null,
    ac: d <= actualsEnd ? acLine[d] : null,
    budget: hasBudget ? budgetLine[d] : null,
  }));
  const finalPoint = points[days - 1];
  const empty = !hasBudget && finalPoint.pv === 0 && (finalPoint.ev ?? 0) === 0 && (finalPoint.ac ?? 0) === 0;
  return { derived: true, points, hasBudget, asOfDate: asOfDay >= 0 ? asOf : null, includesEpicOwnCost, empty };
}
