import type { CSSProperties } from "react";
import type { TFunction } from "i18next";
import { CHART_COLORS, DEEP_DIVE_FILL_ALPHA } from "./chartColors";
import { epochMillisToIsoDate, isoDateToEpochMillis } from "./isoDate";
import {
  formatFigure,
  type MatrixCell,
  type MatrixEpic,
  type MatrixRow,
  type TimeColumn,
} from "./deepDiveMatrix";

/** Which of the matrix's layers are drawn (execution carries the ◆ done marker and its EV figure). */
export interface DeepDiveLayers {
  pv: boolean;
  exec: boolean;
  cost: boolean;
}

/** A theme shade as the CSS variable Mantine publishes for it: `flow.6` → `var(--mantine-color-flow-6)`. */
const cssVar = (shade: string) => `var(--mantine-color-${shade.replace(".", "-")})`;

/**
 * The matrix's layer colours as CSS custom properties (set on the grid and on the legend; `theme.module.css`'s
 * `dd*` classes read them), so `chartColors.ts` stays the one place a hue is chosen.
 */
export const LAYER_VARS = {
  "--dd-pv": cssVar(CHART_COLORS.deepDivePlan),
  "--dd-exec": cssVar(CHART_COLORS.deepDiveExecution),
  "--dd-cost": cssVar(CHART_COLORS.deepDiveCost),
  "--dd-done": cssVar(CHART_COLORS.deepDiveDone),
  "--dd-window": cssVar(CHART_COLORS.deepDiveWindow),
  "--dd-hatch": cssVar(CHART_COLORS.deepDiveHatch),
  "--dd-fill": `${Math.round(DEEP_DIVE_FILL_ALPHA * 100)}%`,
} as CSSProperties;

/** The share of the cell's height a figure fills against the scale's maximum: 0 for nothing, else clamped to 1..100 (the stylesheet adds a minimum height so a tiny figure still shows). */
export function barPercent(value: number, max: number): number {
  if (value <= 0 || max <= 0) return 0;
  return Math.min(100, Math.max(1, (value / max) * 100));
}

/** The text of one cell for the layers shown: the tooltip renders the lines, the cell's `aria-label` joins them. */
export interface CellFacts {
  pv: string | null;
  exec: string | null;
  ev: string | null;
  done: string | null;
  cost: string | null;
  authors: string[];
}

/** A cell holds something in a layer that is shown (the shared empty cell is cheap to label). */
export function cellHasFigures(cell: MatrixCell, layers: DeepDiveLayers): boolean {
  return (
    (layers.pv && cell.pvMd > 0) ||
    (layers.exec && (cell.execTaskDays > 0 || cell.evMd > 0 || cell.done.length > 0)) ||
    (layers.cost && cell.costMd > 0)
  );
}

export function cellFacts(
  cell: MatrixCell,
  layers: DeepDiveLayers,
  t: TFunction,
): CellFacts {
  const facts: CellFacts = {
    pv: null,
    exec: null,
    ev: null,
    done: null,
    cost: null,
    authors: [],
  };
  if (layers.pv)
    facts.pv = t("reports.deepDive.matrix.tip.pv", {
      value: formatFigure(cell.pvMd),
    });
  if (layers.exec) {
    facts.exec = t("reports.deepDive.matrix.tip.exec", {
      value: formatFigure(cell.execTaskDays),
    });
    facts.ev = t("reports.deepDive.matrix.tip.ev", {
      value: formatFigure(cell.evMd),
    });
    if (cell.done.length > 0)
      facts.done = t("reports.deepDive.matrix.tip.done", {
        keys: cell.done.map((marker) => marker.taskKey).join(", "),
      });
  }
  if (layers.cost) {
    facts.cost = t("reports.deepDive.matrix.tip.cost", {
      value: formatFigure(cell.costMd),
    });
    facts.authors = cell.cost.map((author) =>
      t("reports.deepDive.matrix.tip.author", {
        name: author.name ?? t("reports.deepDive.matrix.tip.unknownAuthor"),
        value: formatFigure(author.md),
      }),
    );
  }
  return facts;
}

/** The facts as one sentence-like string: figures separated by `; `, the author list in parentheses after the cost. */
export function factsSummary(facts: CellFacts): string {
  const parts: string[] = [];
  if (facts.pv !== null) parts.push(facts.pv);
  if (facts.exec !== null) parts.push(facts.exec);
  if (facts.ev !== null) parts.push(facts.ev);
  if (facts.done !== null) parts.push(facts.done);
  if (facts.cost !== null)
    parts.push(
      facts.authors.length > 0
        ? `${facts.cost} (${facts.authors.join(", ")})`
        : facts.cost,
    );
  return parts.join("; ");
}

/** `2026-09-01` for a one-day column, else `2026-09-01 to 2026-09-30`. */
function columnSpan(column: TimeColumn, t: TFunction): string {
  return column.fromDate === column.toDate
    ? column.fromDate
    : t("reports.deepDive.matrix.tip.span", {
        from: column.fromDate,
        to: column.toDate,
      });
}

/** The tooltip's date line and the cell label's date part: the span, then "non-working" for a column that is entirely so. */
export function columnCaption(column: TimeColumn, t: TFunction): string {
  const span = columnSpan(column, t);
  return column.nonWorking
    ? `${span} (${t("reports.deepDive.matrix.tip.nonWorking")})`
    : span;
}

/** The first line of a cell's label and tooltip: the row, then the column. */
export const cellTitle = (rowText: string, columnLabel: string) =>
  `${rowText}, ${columnLabel}`;

/** What a row is called in the grid: the epic's key and summary, the task's, or the fixed "(no epic)" / "(on the epic)" captions. */
export function rowLabel(row: MatrixRow, t: TFunction): string {
  if (row.kind === "epicOwnCost") return t("reports.deepDive.matrix.ownCost");
  const key = row.kind === "epic" ? row.epicKey : row.taskKey;
  if (key === null) return t("reports.deepDive.matrix.noEpic");
  return row.summary === null || row.summary === ""
    ? key
    : `${key} ${row.summary}`;
}

/** One line of the visible grid: an epic row, or one of its detail rows when the epic is open. */
export interface GridRow {
  row: MatrixRow;
  epic: MatrixEpic;
  /** True for the task and own-cost lines of an open epic (they use the tasks' own scale). */
  detail: boolean;
  /** Epic rows with something to open (tasks or own cost). */
  expandable: boolean;
  expanded: boolean;
}

/**
 * The epic rows, each followed by its tasks and own-cost line when its id is in `expandedEpics`. With a `cache`
 * (keyed by row id and flags) a row object is reused while the same `MatrixRow` is on the same lines, so opening
 * one epic leaves the memoised rows of every other one untouched.
 */
export function visibleRows(
  epics: readonly MatrixEpic[],
  expandedEpics: ReadonlySet<string>,
  cache: Map<string, GridRow> = new Map(),
): GridRow[] {
  const line = (
    row: MatrixRow,
    epic: MatrixEpic,
    detail: boolean,
    expandable: boolean,
    expanded: boolean,
  ): GridRow => {
    const key = `${row.id}|${detail}|${expandable}|${expanded}`;
    const hit = cache.get(key);
    if (hit !== undefined && hit.row === row && hit.epic === epic) return hit;
    const created = { row, epic, detail, expandable, expanded };
    cache.set(key, created);
    return created;
  };
  const rows: GridRow[] = [];
  for (const epic of epics) {
    const expandable = epic.tasks.length > 0 || epic.ownCost !== null;
    const expanded = expandable && expandedEpics.has(epic.row.id);
    rows.push(line(epic.row, epic, false, expandable, expanded));
    if (!expanded) continue;
    for (const task of epic.tasks) rows.push(line(task, epic, true, false, false));
    if (epic.ownCost !== null) rows.push(line(epic.ownCost, epic, true, false, false));
  }
  return rows;
}

/** The ISO date `offset` days after `from` (an epic's planned window is sent as offsets from `range.from`). */
export function dayDate(from: string, offset: number): string {
  return epochMillisToIsoDate(isoDateToEpochMillis(from) + offset * 86_400_000);
}

/** An epic's planned window as dates, `null` when it has none (or an inverted one). */
export function epicWindowDates(epic: MatrixEpic, rangeFrom: string): { from: string; to: string } | null {
  return epic.window === null
    ? null
    : { from: dayDate(rangeFrom, epic.window.startDay), to: dayDate(rangeFrom, epic.window.dueDay) };
}

/** The dimmed line under an epic's name: its planned window and budget, or `null` when it has neither. */
export function epicPlanText(epic: MatrixEpic, rangeFrom: string, t: TFunction): string | null {
  const parts: string[] = [];
  const window = epicWindowDates(epic, rangeFrom);
  if (window !== null) parts.push(t("reports.deepDive.matrix.planned", window));
  if (epic.budgetMd !== null) parts.push(t("reports.deepDive.matrix.budget", { value: formatFigure(epic.budgetMd) }));
  return parts.length === 0 ? null : parts.join(" · ");
}

/** The table lines written for the layers shown: execution brings its earned-value line along (as in the tooltip). */
export function bucketLayers(layers: DeepDiveLayers): Array<"pv" | "exec" | "ev" | "cost"> {
  const out: Array<"pv" | "exec" | "ev" | "cost"> = [];
  if (layers.pv) out.push("pv");
  if (layers.exec) out.push("exec", "ev");
  if (layers.cost) out.push("cost");
  return out;
}
