import type {
  DeepDiveEpic,
  DeepDiveReport,
  DeepDiveTask,
} from "../api/reports";
import { compareIssueKeys } from "./deepDiveFilter";
import { epochMillisToIsoDate, isoDateToEpochMillis } from "./isoDate";

/**
 * The Deep dive's matrix model: a `DeepDiveReport` (sparse DAILY series per task and layer, day
 * offsets from `range.from`) turned into time columns × epic/task rows of per-layer cells. All the
 * aggregation is the client's job (the server sends days, so drilling month → week → day never
 * refetches): sums stay in plain numbers and are rounded only for display (`formatFigure`).
 *
 * Time columns always PARTITION the range's days. A month column expands into ISO weeks CLIPPED to
 * the month (a week crossing a month or year boundary is two columns, one per side) and a week into
 * its days; `expandedColumns` mixes the three grains per an expansion set, `grainColumns` is the
 * uniform case. `buildDeepDiveMatrix` aggregates over any such partition.
 */

const DAY_MS = 86_400_000;

export type DeepDiveGrain = "month" | "week" | "day";

export interface TimeColumn {
  /** Unique within a partition and stable across drills: `month:2026-09`, `week:<first day>`, `day:<date>`. */
  id: string;
  kind: DeepDiveGrain;
  /** The id of the column this one expands from (`null` for a month). */
  parentId: string | null;
  /** First and last day, as inclusive offsets from `range.from`. */
  fromDay: number;
  toDay: number;
  fromDate: string;
  toDate: string;
  /** A language-neutral caption: `2026-09`, the ISO week `2026-W37`, or the date. */
  label: string;
  /** The number of days the column spans. */
  days: number;
  /** True only when EVERY day of the column is a non-working day (the client hatches it). */
  nonWorking: boolean;
  /** True for a month or week that spans more than one day (what a drill header may open). */
  expandable: boolean;
}

/** The part of a report the time columns depend on. */
type CalendarInput = Pick<DeepDiveReport, "range" | "nonWorkingDays">;

interface Calendar {
  fromEpochDay: number;
  days: number;
  nonWorking: ReadonlySet<number>;
}

function calendarOf(input: CalendarInput): Calendar {
  const fromEpochDay = Math.round(
    isoDateToEpochMillis(input.range.from) / DAY_MS,
  );
  const toEpochDay = Math.round(isoDateToEpochMillis(input.range.to) / DAY_MS);
  return {
    fromEpochDay,
    days: Math.max(1, toEpochDay - fromEpochDay + 1),
    nonWorking: new Set(input.nonWorkingDays),
  };
}

const dateOf = (cal: Calendar, offset: number) =>
  epochMillisToIsoDate((cal.fromEpochDay + offset) * DAY_MS);

/** Monday = 0 … Sunday = 6 (epoch day 0, 1970-01-01, was a Thursday). */
const weekdayOf = (cal: Calendar, offset: number) =>
  (((cal.fromEpochDay + offset + 3) % 7) + 7) % 7;

/** The ISO week (`2026-W37`) a date belongs to: the year and number of the Thursday of its Monday-start week. */
function isoWeekLabel(cal: Calendar, offset: number): string {
  const thursday = cal.fromEpochDay + offset + (3 - weekdayOf(cal, offset));
  const year = new Date(thursday * DAY_MS).getUTCFullYear();
  const dayOfYear = thursday - Date.UTC(year, 0, 1) / DAY_MS;
  return `${year}-W${String(Math.floor(dayOfYear / 7) + 1).padStart(2, "0")}`;
}

function column(
  cal: Calendar,
  kind: DeepDiveGrain,
  parentId: string | null,
  fromDay: number,
  toDay: number,
): TimeColumn {
  const fromDate = dateOf(cal, fromDay);
  let nonWorking = true;
  for (let d = fromDay; d <= toDay && nonWorking; d += 1)
    nonWorking = cal.nonWorking.has(d);
  const days = toDay - fromDay + 1;
  const label =
    kind === "month"
      ? fromDate.slice(0, 7)
      : kind === "week"
        ? isoWeekLabel(cal, fromDay)
        : fromDate;
  const id = kind === "month" ? `month:${label}` : `${kind}:${fromDate}`;
  return {
    id,
    kind,
    parentId,
    fromDay,
    toDay,
    fromDate,
    toDate: dateOf(cal, toDay),
    label,
    days,
    nonWorking,
    expandable: kind !== "day" && days > 1,
  };
}

function monthColumns(cal: Calendar): TimeColumn[] {
  const columns: TimeColumn[] = [];
  for (let start = 0; start < cal.days;) {
    const [year, month] = dateOf(cal, start).split("-").map(Number);
    // Date.UTC(year, month, 0) is the last day of the 1-based `month`.
    const lastOfMonth = Date.UTC(year, month, 0) / DAY_MS - cal.fromEpochDay;
    const end = Math.min(cal.days - 1, lastOfMonth);
    columns.push(column(cal, "month", null, start, end));
    start = end + 1;
  }
  return columns;
}

function children(cal: Calendar, parent: TimeColumn): TimeColumn[] {
  if (parent.kind === "day") return [];
  const out: TimeColumn[] = [];
  for (let start = parent.fromDay; start <= parent.toDay;) {
    const end =
      parent.kind === "week"
        ? start
        : Math.min(parent.toDay, start + (6 - weekdayOf(cal, start)));
    out.push(
      column(
        cal,
        parent.kind === "month" ? "week" : "day",
        parent.id,
        start,
        end,
      ),
    );
    start = end + 1;
  }
  return out;
}

function partition(
  cal: Calendar,
  shouldExpand: (column: TimeColumn) => boolean,
): TimeColumn[] {
  const out: TimeColumn[] = [];
  const visit = (col: TimeColumn) => {
    // `expandable` is only what a drill header may open; a uniform grain still reaches its own kind on a one-day month.
    if (col.kind !== "day" && shouldExpand(col))
      children(cal, col).forEach(visit);
    else out.push(col);
  };
  monthColumns(cal).forEach(visit);
  return out;
}

/** One grain throughout: months, ISO weeks clipped to their month, or single days. */
export function grainColumns(
  report: CalendarInput,
  grain: DeepDiveGrain,
): TimeColumn[] {
  return partition(
    calendarOf(report),
    (col) => grain === "day" || (grain === "week" && col.kind === "month"),
  );
}

/** The drill state's partition: months, with the columns whose id is in `expanded` replaced by their children. */
export function expandedColumns(
  report: CalendarInput,
  expanded: ReadonlySet<string>,
): TimeColumn[] {
  return partition(calendarOf(report), (col) => expanded.has(col.id));
}

// ---- cells ------------------------------------------------------------------------------

export interface DoneMarker {
  taskKey: string;
  /** Day offset of `done_at` from `range.from`. */
  day: number;
  evMd: number;
}

/**
 * One author's man-days in a cell. An unknown author — no author, or an index `authors` lacks (or a
 * negative one) — is normalized BEFORE summing to `authorIndex: null, name: null`, so a cell holds at
 * most one "unknown" entry.
 */
export interface AuthorCost {
  authorIndex: number | null;
  name: string | null;
  md: number;
}

/** Read-only: cells are shared (`EMPTY_CELL`), so mutating one is a compile error. */
export interface MatrixCell {
  readonly pvMd: number;
  readonly execTaskDays: number;
  readonly evMd: number;
  /** The EV markers that fall in the cell (an epic's cell lists its tasks'), by day then issue key. */
  readonly done: readonly DoneMarker[];
  readonly costMd: number;
  /** Per author, biggest first (unknown last on a tie); authors merge across the cell's days. */
  readonly cost: readonly AuthorCost[];
}

/** The shared cell for "nothing here" — deep-frozen; never mutate it. */
export const EMPTY_CELL: MatrixCell = Object.freeze({
  pvMd: 0,
  execTaskDays: 0,
  evMd: 0,
  done: Object.freeze<DoneMarker[]>([]),
  costMd: 0,
  cost: Object.freeze<AuthorCost[]>([]),
});

export interface LayerTotals {
  pvMd: number;
  execTaskDays: number;
  evMd: number;
  costMd: number;
}

export type MatrixRowKind = "epic" | "task" | "epicOwnCost";

export interface MatrixRow {
  /** `epic:<key>` (`epic:` for the no-epic row), `task:<key>`, `own:<epic key>`. */
  id: string;
  kind: MatrixRowKind;
  epicKey: string | null;
  taskKey: string | null;
  summary: string | null;
  /** One cell per column of the partition the matrix was built over. */
  readonly cells: readonly MatrixCell[];
  /** The sum of the row's cells — what the shown range holds. */
  inRange: LayerTotals;
  /** Whole-life, in or out of the range: the server's task totals (an epic row: their sum). */
  totals: LayerTotals;
  /** Tasks only: the plan basis the task's PV spreads and why it may have none. */
  plan: Pick<
    DeepDiveTask,
    "planBasisMd" | "planSource" | "noPlanReason"
  > | null;
}

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

/** The maxima the colour scales are measured against: PV and AC share ONE man-day scale, execution has its own. */
export interface LayerScale {
  mdMax: number;
  execMax: number;
}

export type DeepDiveNoteKind = "RANGE_CLAMPED" | "NOT_DERIVED" | "OTHER";

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
  note: { kind: DeepDiveNoteKind; text: string } | null;
  quality: DeepDiveReport["quality"];
}

// ---- aggregation ------------------------------------------------------------------------

interface Acc {
  pv: number;
  exec: number;
  ev: number;
  done: DoneMarker[];
  cost: Map<number | null, number>;
}

const newAcc = (): Acc => ({
  pv: 0,
  exec: 0,
  ev: 0,
  done: [],
  cost: new Map(),
});

/** The accumulator of column `index`, created on first touch. */
function accAt(accs: Array<Acc | undefined>, index: number): Acc {
  const existing = accs[index];
  if (existing !== undefined) return existing;
  const created = newAcc();
  accs[index] = created;
  return created;
}

function addCost(acc: Acc, author: number | null, md: number) {
  acc.cost.set(author, (acc.cost.get(author) ?? 0) + md);
}

function mergeInto(target: Acc, source: Acc) {
  target.pv += source.pv;
  target.exec += source.exec;
  target.ev += source.ev;
  target.done.push(...source.done);
  for (const [author, md] of source.cost) addCost(target, author, md);
}

const sum = (values: Iterable<number>) => {
  let total = 0;
  for (const value of values) total += value;
  return total;
};

/** The author index when `authors` lists it, else `null` (no author, out of range, negative, fractional). */
function knownAuthor(
  authors: DeepDiveReport["authors"],
  index: number | null,
): number | null {
  return index !== null &&
    Number.isInteger(index) &&
    index >= 0 &&
    index < authors.length
    ? index
    : null;
}

/** An author index's display name: the listed name, else the account id; `null` when unknown. */
function authorName(
  authors: DeepDiveReport["authors"],
  index: number | null,
): string | null {
  const author = index === null ? undefined : authors[index];
  if (author === undefined) return null;
  return author.displayName.trim() !== ""
    ? author.displayName
    : author.accountId;
}

function toCell(acc: Acc, authors: DeepDiveReport["authors"]): MatrixCell {
  const cost = [...acc.cost].map(([authorIndex, md]) => ({
    authorIndex,
    name: authorName(authors, authorIndex),
    md,
  }));
  cost.sort(
    (a, b) => b.md - a.md || (a.name ?? "￿").localeCompare(b.name ?? "￿"),
  );
  return {
    pvMd: acc.pv,
    execTaskDays: acc.exec,
    evMd: acc.ev,
    done: [...acc.done].sort(
      (a, b) => a.day - b.day || compareIssueKeys(a.taskKey, b.taskKey),
    ),
    costMd: sum(acc.cost.values()),
    cost,
  };
}

const noTotals = (): LayerTotals => ({
  pvMd: 0,
  execTaskDays: 0,
  evMd: 0,
  costMd: 0,
});

function addTotals(target: LayerTotals, add: LayerTotals) {
  target.pvMd += add.pvMd;
  target.execTaskDays += add.execTaskDays;
  target.evMd += add.evMd;
  target.costMd += add.costMd;
}

function cellsOf(
  accs: ReadonlyArray<Acc | undefined>,
  authors: DeepDiveReport["authors"],
): MatrixCell[] {
  return accs.map((acc) =>
    acc === undefined ? EMPTY_CELL : toCell(acc, authors),
  );
}

function inRangeOf(cells: readonly MatrixCell[]): LayerTotals {
  const totals = noTotals();
  for (const cell of cells) addTotals(totals, cell);
  return totals;
}

function scaleOf(rows: Iterable<MatrixRow>): LayerScale {
  const scale: LayerScale = { mdMax: 0, execMax: 0 };
  for (const row of rows) {
    for (const cell of row.cells) {
      scale.mdMax = Math.max(scale.mdMax, cell.pvMd, cell.costMd);
      scale.execMax = Math.max(scale.execMax, cell.execTaskDays);
    }
  }
  return scale;
}

/** Whether the SELECTED connection has derived (`meta.derivedAt` spans every connection in scope, so it cannot say). */
const isDerived = (report: DeepDiveReport) => report.range.asOfDay !== null;

function noteOf(report: DeepDiveReport): DeepDiveMatrix["note"] {
  if (report.note === null) return null;
  if (report.note.startsWith("RANGE_CLAMPED"))
    return { kind: "RANGE_CLAMPED", text: report.note };
  const notDerived = !isDerived(report) && report.tasks.length === 0;
  return { kind: notDerived ? "NOT_DERIVED" : "OTHER", text: report.note };
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

/**
 * A man-day / task-day figure for display: rounded half-up to `digits` decimals (the only place the
 * exact sums are rounded), trailing zeros dropped, `.` decimal and no grouping so it is locale-stable.
 * The rounding is done on the decimal-scaled value (cleaned of binary noise at 6 places) rather than
 * with `toFixed`, so a sum like 0.3509 + 0.1441 (0.49499999…) still rounds as the decimal 0.495 does.
 */
export function formatFigure(value: number, digits = 2): string {
  const scale = 10 ** digits;
  const rounded =
    Math.round(Number((Math.abs(value) * scale).toFixed(6))) / scale;
  return String(value < 0 && rounded > 0 ? -rounded : rounded);
}
