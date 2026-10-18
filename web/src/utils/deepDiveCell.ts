import type { CSSProperties } from "react";
import type { TFunction } from "i18next";
import { CHART_COLORS, DEEP_DIVE_FILL_ALPHA } from "./chartColors";
import { addDays } from "./isoDate";
import { type MatrixCell, type MatrixRow } from "./deepDiveAggregate";
import { type TimeColumn } from "./deepDiveCalendar";
import { type MatrixEpic } from "./deepDiveMatrix";
import { formatFigure } from "./reportFormat";

/** Which of the matrix's layers are drawn (Approximate EV carries the ◆ done marker, its EV figure and the hollow "worked, nothing earned" mark). */
export interface DeepDiveLayers {
  pv: boolean;
  aev: boolean;
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
  "--dd-aev": cssVar(CHART_COLORS.deepDiveEv),
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

/** One label/value line of a tooltip section (label left, figure right). */
interface TipRow {
  label: string;
  value: string;
}

/**
 * One headed block of the tooltip: the heading with its figure on the right (`null` when the block is only a list),
 * then detail rows. Sections are the tooltip's structure, built for the hovered cell only; the strings of `CellFacts` are the label's sentence.
 */
export interface TipSection {
  id: "pv" | "aev" | "ev" | "unearned" | "cost";
  heading: string;
  value: string | null;
  rows: TipRow[];
}

/** The text of one cell for the layers shown: the cell's `aria-label` joins the lines (the tooltip draws `cellSections` instead). */
export interface CellFacts {
  pv: string | null;
  aev: string | null;
  ev: string | null;
  done: string | null;
  unearned: string | null;
  cost: string | null;
  authors: string[];
}

/** A cell holds something in a layer that is shown (the shared empty cell is cheap to label). */
export function cellHasFigures(cell: MatrixCell, layers: DeepDiveLayers): boolean {
  return (
    (layers.pv && cell.pvMd > 0) ||
    (layers.aev && (cell.approxEvMd > 0 || cell.evMd > 0 || cell.done.length > 0 || cell.unearned.length > 0)) ||
    (layers.cost && cell.costMd > 0)
  );
}

/**
 * The tooltip's sections for the layers shown, in the order Plan, Approximate EV, EV (+ the done tasks), Worked-not-earned,
 * Cost (+ per author). The figures are the ones `cellFacts` words for the label (not built there: that runs for every visible cell); a layer that is off contributes nothing,
 * and the lists (done, unearned, authors) add rows only when they hold something.
 */
export function cellSections(cell: MatrixCell, layers: DeepDiveLayers, t: TFunction): TipSection[] {
  const md = (value: number) => t("reports.deepDive.matrix.tip.md", { value: formatFigure(value) });
  const sections: TipSection[] = [];
  if (layers.pv) sections.push({ id: "pv", heading: t("reports.deepDive.matrix.layer.pv"), value: md(cell.pvMd), rows: [] });
  if (layers.aev) {
    sections.push({ id: "aev", heading: t("reports.deepDive.matrix.layer.aev"), value: md(cell.approxEvMd), rows: [] });
    sections.push({
      id: "ev",
      heading: t("reports.deepDive.matrix.layer.ev"),
      value: md(cell.evMd),
      rows:
        cell.done.length === 0
          ? []
          : [{ label: t("reports.deepDive.matrix.tip.doneLabel"), value: cell.done.map((marker) => marker.taskKey).join(", ") }],
    });
    if (cell.unearned.length > 0)
      sections.push({
        id: "unearned",
        heading: t("reports.deepDive.matrix.tip.unearnedHeading"),
        value: null,
        rows: cell.unearned.map((marker) => ({
          label: marker.taskKey,
          value: t("reports.deepDive.matrix.tip.unearnedDetail", {
            state: t(`reports.deepDive.matrix.tip.state.${marker.state}`),
            count: marker.days,
          }),
        })),
      });
  }
  if (layers.cost)
    sections.push({
      id: "cost",
      heading: t("reports.deepDive.matrix.layer.cost"),
      value: md(cell.costMd),
      rows: cell.cost.map((author) => ({
        label: author.name ?? t("reports.deepDive.matrix.tip.unknownAuthor"),
        value: md(author.md),
      })),
    });
  return sections;
}

export function cellFacts(
  cell: MatrixCell,
  layers: DeepDiveLayers,
  t: TFunction,
): CellFacts {
  const facts: CellFacts = {
    pv: null,
    aev: null,
    ev: null,
    done: null,
    unearned: null,
    cost: null,
    authors: [],
  };
  if (layers.pv)
    facts.pv = t("reports.deepDive.matrix.tip.pv", {
      value: formatFigure(cell.pvMd),
    });
  if (layers.aev) {
    facts.aev = t("reports.deepDive.matrix.tip.aev", {
      value: formatFigure(cell.approxEvMd),
    });
    facts.ev = t("reports.deepDive.matrix.tip.ev", {
      value: formatFigure(cell.evMd),
    });
    if (cell.done.length > 0)
      facts.done = t("reports.deepDive.matrix.tip.done", {
        keys: cell.done.map((marker) => marker.taskKey).join(", "),
      });
    if (cell.unearned.length > 0)
      facts.unearned = t("reports.deepDive.matrix.tip.unearned", {
        items: cell.unearned
          .map((marker) =>
            t("reports.deepDive.matrix.tip.unearnedItem", {
              key: marker.taskKey,
              state: t(`reports.deepDive.matrix.tip.state.${marker.state}`),
              count: marker.days,
            }),
          )
          .join(", "),
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
  if (facts.aev !== null) parts.push(facts.aev);
  if (facts.ev !== null) parts.push(facts.ev);
  if (facts.done !== null) parts.push(facts.done);
  if (facts.unearned !== null) parts.push(facts.unearned);
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

/** The Jira key a row stands for — an epic's, a task's — or null for the "(no epic)" and "(on the epic)" lines. */
export function rowIssueKey(row: MatrixRow): string | null {
  if (row.kind === "epicOwnCost") return null;
  return row.kind === "epic" ? row.epicKey : row.taskKey;
}

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
  return addDays(from, offset);
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

/** The table lines written for the layers shown: Approximate EV brings its earned-value line along (as in the tooltip). */
export function bucketLayers(layers: DeepDiveLayers): Array<"pv" | "aev" | "ev" | "cost"> {
  const out: Array<"pv" | "aev" | "ev" | "cost"> = [];
  if (layers.pv) out.push("pv");
  if (layers.aev) out.push("aev", "ev");
  if (layers.cost) out.push("cost");
  return out;
}
