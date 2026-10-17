import type { DeepDiveReport, DeepDiveTask } from "../api/reports";
import { compareIssueKeys } from "./deepDiveFilter";

/**
 * The Deep dive's cells and sums: the per-layer cell and row shapes of the matrix and the accumulators that
 * turn a task's sparse daily series into cells over a partition of columns (`deepDiveCalendar.ts`). Sums stay
 * in plain numbers and are rounded only for display (`formatFigure` in `reportFormat.ts`).
 */

/**
 * A task that was worked on in a cell but earned nothing there (Approximate EV, A40): an open task (`OPEN`) or one done without an
 * estimate (`UNESTIMATED`), with the number of in-progress working days it has in the cell. The cell draws a hollow mark for it.
 */
export interface UnearnedMarker {
  taskKey: string;
  state: "OPEN" | "UNESTIMATED";
  days: number;
}

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
  /** Approximate EV (A40): the finished tasks' EV spread over their in-progress working days, as it falls in the cell. */
  readonly approxEvMd: number;
  readonly evMd: number;
  /** The EV markers that fall in the cell (an epic's cell lists its tasks'), by day then issue key. */
  readonly done: readonly DoneMarker[];
  /** The tasks worked on here that earn nothing (open, or done without an estimate), most days first, then by issue key. */
  readonly unearned: readonly UnearnedMarker[];
  readonly costMd: number;
  /** Per author, biggest first (unknown last on a tie); authors merge across the cell's days. */
  readonly cost: readonly AuthorCost[];
}

/** The shared cell for "nothing here" — deep-frozen; never mutate it. */
export const EMPTY_CELL: MatrixCell = Object.freeze({
  pvMd: 0,
  approxEvMd: 0,
  evMd: 0,
  done: Object.freeze<DoneMarker[]>([]),
  unearned: Object.freeze<UnearnedMarker[]>([]),
  costMd: 0,
  cost: Object.freeze<AuthorCost[]>([]),
});

export interface LayerTotals {
  pvMd: number;
  approxEvMd: number;
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

/** The maximum the bars are measured against: plan, Approximate EV and cost share ONE man-day scale (A40). */
export interface LayerScale {
  mdMax: number;
}

// ---- aggregation ------------------------------------------------------------------------

export interface Acc {
  pv: number;
  aev: number;
  ev: number;
  done: DoneMarker[];
  /** Per task key: the unearned marker being counted up (the state is the task's, so it never changes). */
  unearned: Map<string, UnearnedMarker>;
  cost: Map<number | null, number>;
}

const newAcc = (): Acc => ({
  pv: 0,
  aev: 0,
  ev: 0,
  done: [],
  unearned: new Map(),
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

/** Counts one more in-progress day of an unearning task into the accumulator. */
export function addUnearned(acc: Acc, taskKey: string, state: UnearnedMarker["state"]) {
  const marker = acc.unearned.get(taskKey);
  if (marker === undefined) acc.unearned.set(taskKey, { taskKey, state, days: 1 });
  else marker.days += 1;
}

export function mergeInto(target: Acc, source: Acc) {
  target.pv += source.pv;
  target.aev += source.aev;
  target.ev += source.ev;
  target.done.push(...source.done);
  // A task sits in one epic and its days are counted once, so epic cells never see the same task key twice per column.
  for (const marker of source.unearned.values()) {
    const into = target.unearned.get(marker.taskKey);
    if (into === undefined) target.unearned.set(marker.taskKey, { ...marker });
    else into.days += marker.days;
  }
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
    approxEvMd: acc.aev,
    evMd: acc.ev,
    done: [...acc.done].sort(
      (a, b) => a.day - b.day || compareIssueKeys(a.taskKey, b.taskKey),
    ),
    unearned: [...acc.unearned.values()].sort(
      (a, b) => b.days - a.days || compareIssueKeys(a.taskKey, b.taskKey),
    ),
    costMd: sum(acc.cost.values()),
    cost,
  };
}

export const noTotals = (): LayerTotals => ({
  pvMd: 0,
  approxEvMd: 0,
  evMd: 0,
  costMd: 0,
});

export function addTotals(target: LayerTotals, add: LayerTotals) {
  target.pvMd += add.pvMd;
  target.approxEvMd += add.approxEvMd;
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
  const scale: LayerScale = { mdMax: 0 };
  for (const row of rows) {
    for (const cell of row.cells) {
      scale.mdMax = Math.max(scale.mdMax, cell.pvMd, cell.approxEvMd, cell.costMd);
    }
  }
  return scale;
}
