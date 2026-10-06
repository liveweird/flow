import type { DeepDiveReport, DeepDiveTask } from "../api/reports";
import { compareIssueKeys } from "./deepDiveFilter";

/**
 * The Deep dive's cells and sums: the per-layer cell and row shapes of the matrix and the accumulators that
 * turn a task's sparse daily series into cells over a partition of columns (`deepDiveCalendar.ts`). Sums stay
 * in plain numbers and are rounded only for display (`formatFigure` in `reportFormat.ts`).
 */

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

/** The maxima the colour scales are measured against: PV and AC share ONE man-day scale, execution has its own. */
export interface LayerScale {
  mdMax: number;
  execMax: number;
}

// ---- aggregation ------------------------------------------------------------------------

export interface Acc {
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
export function accAt(accs: Array<Acc | undefined>, index: number): Acc {
  const existing = accs[index];
  if (existing !== undefined) return existing;
  const created = newAcc();
  accs[index] = created;
  return created;
}

export function addCost(acc: Acc, author: number | null, md: number) {
  acc.cost.set(author, (acc.cost.get(author) ?? 0) + md);
}

export function mergeInto(target: Acc, source: Acc) {
  target.pv += source.pv;
  target.exec += source.exec;
  target.ev += source.ev;
  target.done.push(...source.done);
  for (const [author, md] of source.cost) addCost(target, author, md);
}

export const sum = (values: Iterable<number>) => {
  let total = 0;
  for (const value of values) total += value;
  return total;
};

/** The author index when `authors` lists it, else `null` (no author, out of range, negative, fractional). */
export function knownAuthor(
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

export const noTotals = (): LayerTotals => ({
  pvMd: 0,
  execTaskDays: 0,
  evMd: 0,
  costMd: 0,
});

export function addTotals(target: LayerTotals, add: LayerTotals) {
  target.pvMd += add.pvMd;
  target.execTaskDays += add.execTaskDays;
  target.evMd += add.evMd;
  target.costMd += add.costMd;
}

export function cellsOf(
  accs: ReadonlyArray<Acc | undefined>,
  authors: DeepDiveReport["authors"],
): MatrixCell[] {
  return accs.map((acc) =>
    acc === undefined ? EMPTY_CELL : toCell(acc, authors),
  );
}

export function inRangeOf(cells: readonly MatrixCell[]): LayerTotals {
  const totals = noTotals();
  for (const cell of cells) addTotals(totals, cell);
  return totals;
}

export function scaleOf(rows: Iterable<MatrixRow>): LayerScale {
  const scale: LayerScale = { mdMax: 0, execMax: 0 };
  for (const row of rows) {
    for (const cell of row.cells) {
      scale.mdMax = Math.max(scale.mdMax, cell.pvMd, cell.costMd);
      scale.execMax = Math.max(scale.execMax, cell.execTaskDays);
    }
  }
  return scale;
}
