import type { DeepDiveEpic, DeepDiveReport, DeepDiveTask } from "../api/reports";
import {
  accAt,
  addCost,
  addTotals,
  cellsOf,
  inRangeOf,
  knownAuthor,
  mergeInto,
  noTotals,
  scaleOf,
  sum,
  type Acc,
  type LayerScale,
  type LayerTotals,
  type MatrixRow,
} from "./deepDiveAggregate";
import { calendarOf, type TimeColumn } from "./deepDiveCalendar";
import { isDerived, noteOf, type DeepDiveNote } from "./deepDiveNote";

/**
 * The Deep dive's matrix model: a `DeepDiveReport` (sparse DAILY series per task and layer, day
 * offsets from `range.from`) turned into time columns (`deepDiveCalendar.ts`) × epic/task rows of
 * per-layer cells (`deepDiveAggregate.ts`). All the aggregation is the client's job (the server sends
 * days, so drilling month → week → day never refetches). This file holds the epic grouping, the planned
 * windows and the assembly (`buildDeepDiveMatrix`).
 */

/**
 * The epic's planned window (an outline, never summed) mapped onto the columns it spans. A window
 * lying ENTIRELY outside the range is still returned, with `firstColumn` and `lastColumn` both `-1`
 * (nothing to draw) so the page can say "planned before/after the range": entirely before has
 * `startsBeforeRange` set and `endsAfterRange` not, entirely after has `endsAfterRange` set and
 * `startsBeforeRange` not. A missing or inverted window is `MatrixEpic.window === null`.
 */
export interface EpicWindow {
  startDay: number;
  dueDay: number;
  /** Indexes into `columns` of the first and last column the window touches within the range; `-1` when it touches none. */
  firstColumn: number;
  lastColumn: number;
  /** The window starts before / ends after the range (the outline is open at that edge). */
  startsBeforeRange: boolean;
  endsAfterRange: boolean;
}

export interface MatrixEpic {
  /** `null` = the "(no epic)" row. */
  key: string | null;
  summary: string | null;
  budgetMd: number | null;
  /** The epic row: exactly the sum of its tasks (the epic's own worklogs are NOT in it — see `ownCost`). */
  row: MatrixRow;
  /** The epic's own worklogs as an "(on the epic)" line; `null` when there are none (EPICS mode only). */
  ownCost: MatrixRow | null;
  tasks: MatrixRow[];
  window: EpicWindow | null;
}

export interface DeepDiveMatrix {
  mode: DeepDiveReport["mode"];
  range: DeepDiveReport["range"];
  columns: TimeColumn[];
  epics: MatrixEpic[];
  /** Over every row (epic rows included). */
  scale: LayerScale;
  /** Over task and own-cost rows only — the scale for an expanded epic's detail lines. */
  taskScale: LayerScale;
  /** The sum over all tasks, in the range and whole-life (own cost excluded). */
  inRange: LayerTotals;
  totals: LayerTotals;
  /** The whole-life man-days logged on the epics themselves. */
  ownCostTotalMd: number;
  /** False when the selection has not derived (`range.asOfDay` null): an empty answer, not a range of zeros. */
  derived: boolean;
  /** The server's `note` with its kind; `null` when absent. */
  note: DeepDiveNote;
  quality: DeepDiveReport["quality"];
}

/** The columns' index per day offset (`-1` for a day no column holds) — one lookup per series entry. */
function columnOfDays(
  columns: readonly TimeColumn[],
  days: number,
): Int32Array {
  const lookup = new Int32Array(days).fill(-1);
  columns.forEach((col, index) => {
    for (
      let d = Math.max(0, col.fromDay);
      d <= Math.min(days - 1, col.toDay);
      d += 1
    )
      lookup[d] = index;
  });
  return lookup;
}

function epicWindowOf(
  epic: DeepDiveEpic,
  lookup: Int32Array,
): EpicWindow | null {
  const { plannedStart: startDay, plannedDue: dueDay } = epic;
  if (startDay === null || dueDay === null || startDay > dueDay) return null;
  const last = lookup.length - 1;
  const outside = dueDay < 0 || startDay > last;
  return {
    startDay,
    dueDay,
    firstColumn: outside ? -1 : lookup[Math.max(startDay, 0)],
    lastColumn: outside ? -1 : lookup[Math.min(dueDay, last)],
    startsBeforeRange: startDay < 0,
    endsAfterRange: dueDay > last,
  };
}

interface EpicBuilder {
  key: string | null;
  summary: string | null;
  budgetMd: number | null;
  window: EpicWindow | null;
  ownCost: DeepDiveEpic["ownCost"];
  tasks: MatrixRow[];
  taskAccs: Array<Array<Acc | undefined>>;
}

/**
 * Aggregates a report over a partition of its days (`grainColumns`/`expandedColumns`). Epic rows are
 * built from their tasks' accumulators, so "an epic row equals the sum of its tasks" holds by construction.
 */
export function buildDeepDiveMatrix(
  report: DeepDiveReport,
  columns: readonly TimeColumn[],
): DeepDiveMatrix {
  const days = calendarOf(report).days;
  const lookup = columnOfDays(columns, days);
  const at = (d: number) =>
    Number.isInteger(d) && d >= 0 && d < days ? lookup[d] : -1;
  const slot = (accs: Array<Acc | undefined>, d: number): Acc | undefined => {
    const index = at(d);
    return index < 0 ? undefined : accAt(accs, index);
  };

  const builders = new Map<string, EpicBuilder>();
  const builderFor = (key: string | null, epic?: DeepDiveEpic): EpicBuilder => {
    const id = key ?? "";
    let builder = builders.get(id);
    if (builder === undefined) {
      builder = {
        key,
        summary: epic?.summary ?? null,
        budgetMd: epic?.budgetMd ?? null,
        window: epic === undefined ? null : epicWindowOf(epic, lookup),
        ownCost: epic?.ownCost ?? null,
        tasks: [],
        taskAccs: [],
      };
      builders.set(id, builder);
    }
    return builder;
  };
  for (const epic of report.epics) builderFor(epic.key, epic);

  const rowTotals = (task: DeepDiveTask): LayerTotals => ({ ...task.totals });
  for (const task of report.tasks) {
    const accs: Array<Acc | undefined> = Array.from({ length: columns.length });
    for (const e of task.pv) {
      const acc = slot(accs, e.d);
      if (acc) acc.pv += e.md;
    }
    for (const e of task.exec) {
      const acc = slot(accs, e.d);
      if (acc) acc.exec += e.td;
    }
    for (const e of task.cost) {
      const acc = slot(accs, e.d);
      if (acc) addCost(acc, knownAuthor(report.authors, e.a), e.md);
    }
    if (task.done !== null) {
      const acc = slot(accs, task.done.d);
      if (acc) {
        acc.ev += task.done.evMd;
        acc.done.push({
          taskKey: task.key,
          day: task.done.d,
          evMd: task.done.evMd,
        });
      }
    }
    const cells = cellsOf(accs, report.authors);
    const builder = builderFor(task.epicKey);
    builder.taskAccs.push(accs);
    builder.tasks.push({
      id: `task:${task.key}`,
      kind: "task",
      epicKey: task.epicKey,
      taskKey: task.key,
      summary: task.summary,
      cells,
      inRange: inRangeOf(cells),
      totals: rowTotals(task),
      plan: {
        planBasisMd: task.planBasisMd,
        planSource: task.planSource,
        noPlanReason: task.noPlanReason,
      },
    });
  }

  const epics: MatrixEpic[] = [...builders.values()].map((b) => {
    const accs: Array<Acc | undefined> = Array.from({ length: columns.length });
    for (const taskAcc of b.taskAccs) {
      taskAcc.forEach((acc, index) => {
        if (acc !== undefined) mergeInto(accAt(accs, index), acc);
      });
    }
    const cells = cellsOf(accs, report.authors);
    const totals = noTotals();
    for (const task of b.tasks) addTotals(totals, task.totals);
    let ownCost: MatrixRow | null = null;
    if (b.ownCost !== null) {
      const ownAccs: Array<Acc | undefined> = Array.from({
        length: columns.length,
      });
      for (const e of b.ownCost.cost) {
        const acc = slot(ownAccs, e.d);
        if (acc) addCost(acc, knownAuthor(report.authors, e.a), e.md);
      }
      const ownCells = cellsOf(ownAccs, report.authors);
      ownCost = {
        id: `own:${b.key ?? ""}`,
        kind: "epicOwnCost",
        epicKey: b.key,
        taskKey: null,
        summary: null,
        cells: ownCells,
        inRange: inRangeOf(ownCells),
        totals: { ...noTotals(), costMd: b.ownCost.totalMd },
        plan: null,
      };
    }
    return {
      key: b.key,
      summary: b.summary,
      budgetMd: b.budgetMd,
      row: {
        id: `epic:${b.key ?? ""}`,
        kind: "epic",
        epicKey: b.key,
        taskKey: null,
        summary: b.summary,
        cells,
        inRange: inRangeOf(cells),
        totals,
        plan: null,
      },
      ownCost,
      tasks: b.tasks,
      window: b.window,
    };
  });

  const detailRows = epics.flatMap((epic) =>
    epic.ownCost === null ? epic.tasks : [...epic.tasks, epic.ownCost],
  );
  const inRange = noTotals();
  const totals = noTotals();
  for (const epic of epics) {
    addTotals(inRange, epic.row.inRange);
    addTotals(totals, epic.row.totals);
  }
  return {
    mode: report.mode,
    range: report.range,
    columns: [...columns],
    epics,
    scale: scaleOf([...epics.map((epic) => epic.row), ...detailRows]),
    taskScale: scaleOf(detailRows),
    inRange,
    totals,
    ownCostTotalMd: sum(epics.map((epic) => epic.ownCost?.totals.costMd ?? 0)),
    derived: isDerived(report),
    note: noteOf(report),
    quality: report.quality,
  };
}
