import { memo, useCallback, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { CSSProperties, FocusEvent, KeyboardEvent } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Button, Group, Stack, Table, Text, Title, UnstyledButton, VisuallyHidden } from "@mantine/core";
import { IconChevronDown, IconChevronRight } from "@tabler/icons-react";
import type { DeepDiveReport } from "../api/reports";
import { useDeepDiveTooltip } from "../hooks/useDeepDiveTooltip";
import {
  LAYER_VARS,
  barPercent,
  bucketLayers,
  cellFacts,
  cellHasFigures,
  cellTitle,
  columnCaption,
  epicPlanText,
  factsSummary,
  rowLabel,
  visibleRows,
  type DeepDiveLayers,
  type GridRow,
} from "../utils/deepDiveCell";
import { headerModel, type HeaderCell } from "../utils/deepDiveHeader";
import {
  buildDeepDiveMatrix,
  expandedColumns,
  grainColumns,
  type DeepDiveMatrix as MatrixModel,
  type LayerScale,
  type MatrixCell,
  type TimeColumn,
} from "../utils/deepDiveMatrix";
import classes from "../theme.module.css";
import DailyTableDisclosure from "./DailyTableDisclosure";
import DeepDiveBucketTable from "./DeepDiveBucketTable";
import DeepDiveCellTooltip from "./DeepDiveCellTooltip";
import DeepDiveLegend from "./DeepDiveLegend";
import DeepDiveSummaryTable from "./DeepDiveSummaryTable";
import ScrollRegion from "./ScrollRegion";

/** More visible cells (rows × columns) than this and the grid is not drawn: the page asks for a collapse instead. */
const DEFAULT_MAX_CELLS = 25_000;
/** The item column's width (the stylesheet's `--dd-item-width`) and the narrowest each kind of time column gets. */
const ITEM_COLUMN_WIDTH = 280;
const COLUMN_WIDTH = { month: 84, week: 84, day: 52 } as const;

const NO_EXPANSION: ReadonlySet<string> = new Set();

/**
 * The grid's keyboard position. Body rows are 1…n; the header rows above are 0, -1, -2 from the bottom up (so a drill
 * that adds a header row never renumbers the body); column 0 is the item column, time columns are 1…n.
 */
interface GridPos {
  r: number;
  c: number;
}

interface Bounds {
  rMin: number;
  rMax: number;
  cMax: number;
}

const parsePos = (element: HTMLElement): { r: number; c0: number; c1: number } | null => {
  const raw = element.dataset.gridPos;
  if (raw === undefined) return null;
  const [r, c0] = raw.split(",").map(Number);
  return { r, c0, c1: Number(element.dataset.gridEnd ?? c0) };
};

/** The roving tab stop: every navigable element carries its position (and the last column a spanning header covers), exactly one has `tabIndex` 0. */
const roving = (r: number, c: number, active: boolean, end: number = c) => ({
  "data-grid-pos": `${r},${c}`,
  "data-grid-end": end,
  tabIndex: active ? 0 : -1,
});

/** The element at a grid position: an exact body cell, or the header cell of that row that COVERS the column. */
function cellAt(table: HTMLElement, r: number, c: number): HTMLElement | null {
  if (r >= 1) return table.querySelector<HTMLElement>(`[data-grid-pos="${r},${c}"]`);
  for (const element of table.querySelectorAll<HTMLElement>(`thead [data-grid-pos^="${r},"]`)) {
    const pos = parsePos(element);
    if (pos !== null && c >= pos.c0 && c <= pos.c1) return element;
  }
  return null;
}

/** Steps from a position until something is there (header rows have holes where a cell spans down) or the grid ends. */
function scan(table: HTMLElement, r: number, c: number, dr: number, dc: number, b: Bounds): HTMLElement | null {
  let row = r;
  let col = c;
  while (row >= b.rMin && row <= b.rMax && col >= 0 && col <= b.cMax) {
    const found = cellAt(table, row, col);
    if (found !== null) return found;
    row += dr;
    col += dc;
  }
  return null;
}

function navigate(table: HTMLElement, from: HTMLElement, key: string, ctrl: boolean, b: Bounds): HTMLElement | null {
  const pos = parsePos(from);
  if (pos === null) return null;
  switch (key) {
    case "ArrowRight":
      return scan(table, pos.r, pos.c1 + 1, 0, 1, b);
    case "ArrowLeft":
      return scan(table, pos.r, pos.c0 - 1, 0, -1, b);
    case "ArrowDown":
      return scan(table, pos.r + 1, pos.c0, 1, 0, b);
    case "ArrowUp":
      return scan(table, pos.r - 1, pos.c0, -1, 0, b);
    case "Home":
      return ctrl ? cellAt(table, b.rMin, 0) : scan(table, pos.r, 0, 0, 1, b);
    case "End":
      return ctrl ? cellAt(table, b.rMax, b.cMax) : scan(table, pos.r, b.cMax, 0, -1, b);
    default:
      return null;
  }
}

/** Where focus goes once a drill has re-rendered the grid (the control that was pressed may be gone). */
type PendingFocus =
  | { kind: "firstChild"; id: string }
  | { kind: "header"; id: string }
  | { kind: "roving" };

/** One cached row list per matrix: opening an epic reuses every other row object, so their memoised renders hold. */
const ROW_CACHES = new WeakMap<MatrixModel, Map<string, GridRow>>();

function LayerBars({ cell, layers, scale }: { cell: MatrixCell; layers: DeepDiveLayers; scale: LayerScale }) {
  const pv = layers.pv ? barPercent(cell.pvMd, scale.mdMax) : 0;
  const exec = layers.exec ? barPercent(cell.execTaskDays, scale.execMax) : 0;
  const cost = layers.cost ? barPercent(cell.costMd, scale.mdMax) : 0;
  return (
    <>
      {pv > 0 && <span className={`${classes.ddBar} ${classes.ddBarPv}`} data-layer="pv" style={{ height: `${pv}%` }} />}
      {exec > 0 && <span className={`${classes.ddBar} ${classes.ddBarExec}`} data-layer="exec" style={{ height: `${exec}%` }} />}
      {cost > 0 && <span className={`${classes.ddBar} ${classes.ddBarCost}`} data-layer="cost" style={{ height: `${cost}%` }} />}
      {layers.exec && cell.done.length > 0 && (
        <span className={classes.ddDone} data-layer="done">
          <span className={classes.ddDoneMark}>◆</span>
          {cell.done.length > 1 && <span className={classes.ddDoneCount}>{cell.done.length}</span>}
        </span>
      )}
    </>
  );
}

interface RowProps {
  gridRow: GridRow;
  /** The row's grid row (body rows are 1…). */
  r: number;
  columns: readonly TimeColumn[];
  /** Per column: `<label>, <span>[ (non-working)]` — the part of a cell's label after the row. */
  captions: readonly string[];
  rangeFrom: string;
  layers: DeepDiveLayers;
  scale: LayerScale;
  /** The grid column holding the tab stop when it is in this row, else -1. */
  activeCol: number;
  onToggle: (rowId: string) => void;
}

function RowHeader({
  gridRow,
  r,
  label,
  rangeFrom,
  activeCol,
  onToggle,
}: Pick<RowProps, "gridRow" | "r" | "rangeFrom" | "activeCol" | "onToggle"> & { label: string }) {
  const { t } = useTranslation();
  const epicWindow = gridRow.detail ? null : gridRow.epic.window;
  // An epic planned entirely outside the shown range has no column to outline: say so in words.
  const hint =
    epicWindow === null || epicWindow.firstColumn >= 0
      ? null
      : t(epicWindow.endsAfterRange ? "reports.deepDive.matrix.plannedAfter" : "reports.deepDive.matrix.plannedBefore");
  const plan = gridRow.detail || gridRow.row.kind !== "epic" ? null : epicPlanText(gridRow.epic, rangeFrom, t);
  const navigation = roving(r, 0, activeCol === 0);
  return (
    <th scope="row" role="rowheader" className={`${classes.heatStickyCol} ${classes.ddRowHeader}`}>
      {gridRow.expandable ? (
        <UnstyledButton
          className={classes.ddToggle}
          aria-expanded={gridRow.expanded}
          onClick={() => onToggle(gridRow.row.id)}
          {...navigation}
        >
          {gridRow.expanded ? <IconChevronDown size={14} aria-hidden /> : <IconChevronRight size={14} aria-hidden />}
          <span className={classes.ddItem} title={label}>
            {label}
          </span>
        </UnstyledButton>
      ) : (
        <span className={`${classes.ddItem} ${gridRow.detail ? classes.ddDetail : ""}`} title={label} {...navigation}>
          {label}
        </span>
      )}
      {plan !== null && (
        <Text size="xs" c="dimmed">
          {plan}
        </Text>
      )}
      {hint !== null && (
        <Text size="xs" c="dimmed">
          {hint}
        </Text>
      )}
    </th>
  );
}

const MatrixBodyRow = memo(function MatrixBodyRow({
  gridRow,
  r,
  columns,
  captions,
  rangeFrom,
  layers,
  scale,
  activeCol,
  onToggle,
}: RowProps) {
  const { t } = useTranslation();
  const label = rowLabel(gridRow.row, t);
  const none = t("reports.deepDive.matrix.tip.none");
  const epicWindow = gridRow.detail ? null : gridRow.epic.window;
  const { first, last } =
    epicWindow !== null && epicWindow.firstColumn >= 0
      ? { first: epicWindow.firstColumn, last: epicWindow.lastColumn }
      : { first: -1, last: -1 };
  return (
    <tr>
      <RowHeader gridRow={gridRow} r={r} label={label} rangeFrom={rangeFrom} activeCol={activeCol} onToggle={onToggle} />
      {columns.map((column, index) => {
        const cell = gridRow.row.cells[index];
        const inWindow = epicWindow !== null && index >= first && index <= last;
        const outline = inWindow
          ? `${classes.ddOutline} ${index === first && !epicWindow.startsBeforeRange ? classes.ddOutlineStart : ""} ${
              index === last && !epicWindow.endsAfterRange ? classes.ddOutlineEnd : ""
            }`
          : null;
        const summary = cellHasFigures(cell, layers) ? factsSummary(cellFacts(cell, layers, t)) : none;
        return (
          <td
            key={column.id}
            role="gridcell"
            className={`${classes.ddTd} ${column.nonWorking ? classes.ddHatched : ""}`}
            aria-label={`${label}, ${captions[index]}: ${summary}`}
            data-cell=""
            data-row={r - 1}
            data-col={index}
            {...roving(r, index + 1, activeCol === index + 1)}
          >
            <span className={classes.ddCell} aria-hidden>
              <LayerBars cell={cell} layers={layers} scale={scale} />
              {outline !== null && <span className={outline} data-window="" />}
            </span>
          </td>
        );
      })}
    </tr>
  );
});

function HeaderCellView({
  cell,
  active,
  onDrill,
}: {
  cell: HeaderCell;
  active: boolean;
  onDrill: (column: TimeColumn, expanding: boolean) => void;
}) {
  const { t } = useTranslation();
  const { column } = cell;
  const focus = { ...roving(cell.r, cell.c, active, cell.c + cell.colSpan - 1), "data-header-id": column.id };
  if (cell.role === "group") {
    // An open month or week: stays on screen as the control that closes it, spanning the columns it produced.
    return (
      <th
        scope="colgroup"
        role="columnheader"
        colSpan={cell.colSpan}
        className={`${classes.ddColHead} ${classes.ddGroupHead}`}
      >
        <UnstyledButton
          className={classes.ddToggle}
          aria-expanded
          aria-label={t("reports.deepDive.matrix.collapseColumn", { label: column.label })}
          onClick={() => onDrill(column, false)}
          {...focus}
        >
          <IconChevronDown size={12} aria-hidden />
          {column.label}
        </UnstyledButton>
      </th>
    );
  }
  const className = `${classes.ddColHead} ${column.nonWorking ? classes.ddHatched : ""}`;
  const style = { minWidth: COLUMN_WIDTH[column.kind] };
  if (column.expandable) {
    return (
      <th scope="col" role="columnheader" rowSpan={cell.rowSpan} className={className} style={style}>
        <UnstyledButton
          className={classes.ddToggle}
          aria-expanded={false}
          aria-label={t(column.kind === "month" ? "reports.deepDive.matrix.expandWeeks" : "reports.deepDive.matrix.expandDays", {
            label: column.label,
          })}
          onClick={() => onDrill(column, true)}
          {...focus}
        >
          <IconChevronRight size={12} aria-hidden />
          {column.label}
        </UnstyledButton>
      </th>
    );
  }
  return (
    <th scope="col" role="columnheader" rowSpan={cell.rowSpan} className={className} style={style} aria-label={column.label} {...focus}>
      <span aria-hidden>{column.kind === "day" ? column.label.slice(5) : column.label}</span>
    </th>
  );
}

/**
 * The Deep dive matrix: epics (drilling into their tasks) down, time (months drilling into ISO weeks, then days)
 * across, and in each cell the shown layers as stacked semi-transparent bars — plan (PV) full width, execution
 * two thirds, cost (AC) one third — with a ◆ on the day a task was done and a dashed outline over an epic's planned
 * window. The model and every sum live in `utils/deepDiveMatrix.ts`; this draws it. Drill state is local: the
 * page owns only the layer toggles, so a different `report` (a new selection) should arrive under a new `key`.
 *
 * It is a real `<table role="grid">` in a `ScrollRegion` (sticky header rows and first column — the cost matrix's
 * `.heatTable`), with a roving tab stop: one Tab stop for the grid, arrows (and Home/End, Ctrl for first/last row
 * or column) between cells, headers that open are buttons with `aria-expanded`. An open month or week stays in the
 * header as a spanning cell above its columns — its button, `aria-expanded="true"`, closes it — and a drill moves
 * focus (to the first new column, or back to the header just closed) and says what is shown in a polite live region.
 * Every cell's `aria-label` carries its numbers; the sighted tooltip (hover or focus, Escape closes it) shows the same,
 * plus the author list. The text alternatives sit below: the legend, the summary table and the visible figures behind
 * a disclosure. Beyond `maxCells` visible cells the grid is replaced by a notice, so the DOM stays bounded.
 */
export default function DeepDiveMatrix({
  report,
  layers,
  maxCells = DEFAULT_MAX_CELLS,
}: {
  report: DeepDiveReport;
  layers: DeepDiveLayers;
  /** The visible-cell ceiling (rows × columns); the default is the documented 25,000. */
  maxCells?: number;
}) {
  const { t } = useTranslation();
  const [openColumns, setOpenColumns] = useState<ReadonlySet<string>>(NO_EXPANSION);
  const [openEpics, setOpenEpics] = useState<ReadonlySet<string>>(NO_EXPANSION);
  const [active, setActive] = useState<GridPos>({ r: 1, c: 0 });
  const [announcement, setAnnouncement] = useState("");
  const tableRef = useRef<HTMLTableElement>(null);
  const pendingFocus = useRef<PendingFocus | null>(null);
  const { tip, tipRef, gridProps, tipProps } = useDeepDiveTooltip();

  const { pv, exec, cost } = layers;
  // A page may pass a fresh object each render; the memoised rows compare this one.
  const shown = useMemo<DeepDiveLayers>(() => ({ pv, exec, cost }), [pv, exec, cost]);

  const columns = useMemo(() => expandedColumns(report, openColumns), [report, openColumns]);
  const matrix = useMemo(() => buildDeepDiveMatrix(report, columns), [report, columns]);
  const rows = useMemo(() => {
    const cache = ROW_CACHES.get(matrix) ?? new Map<string, GridRow>();
    ROW_CACHES.set(matrix, cache);
    return visibleRows(matrix.epics, openEpics, cache);
  }, [matrix, openEpics]);
  const lineage = useMemo(
    () => new Map([...grainColumns(report, "month"), ...grainColumns(report, "week")].map((c) => [c.id, c])),
    [report],
  );
  const header = useMemo(() => headerModel(columns, lineage), [columns, lineage]);
  const captions = useMemo(() => columns.map((c) => `${c.label}, ${columnCaption(c, t)}`), [columns, t]);

  const toggleEpic = useCallback((id: string) => {
    setOpenEpics((prev) => {
      const next = new Set(prev);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  }, []);
  const drill = useCallback(
    (column: TimeColumn, expanding: boolean) => {
      setOpenColumns((prev) => {
        const next = new Set(prev);
        if (expanding) next.add(column.id);
        else next.delete(column.id);
        return next;
      });
      pendingFocus.current = expanding ? { kind: "firstChild", id: column.id } : { kind: "header", id: column.id };
      setAnnouncement(
        expanding
          ? t(column.kind === "month" ? "reports.deepDive.matrix.announce.weeks" : "reports.deepDive.matrix.announce.days", {
              label: column.label,
            })
          : t("reports.deepDive.matrix.announce.collapsed", { label: column.label }),
      );
    },
    [t],
  );
  const collapseColumns = () => {
    setOpenColumns(NO_EXPANSION);
    pendingFocus.current = { kind: "roving" };
    setAnnouncement(t("reports.deepDive.matrix.announce.months"));
  };
  const collapseRows = () => {
    setOpenEpics(NO_EXPANSION);
    pendingFocus.current = { kind: "roving" };
    setAnnouncement(t("reports.deepDive.matrix.announce.rows"));
  };

  // A drill replaces the header control that was pressed, so focus is put back by hand once the grid has rendered.
  useLayoutEffect(() => {
    const pending = pendingFocus.current;
    const table = tableRef.current;
    if (pending === null || table === null) return;
    pendingFocus.current = null;
    let id: string | null = null;
    if (pending.kind === "header") id = pending.id;
    else if (pending.kind === "firstChild") id = columns.find((c) => c.parentId === pending.id)?.id ?? null;
    const target =
      id === null
        ? table.querySelector<HTMLElement>('[data-grid-pos][tabindex="0"]')
        : table.querySelector<HTMLElement>(`[data-header-id="${id}"]`);
    target?.focus();
  });

  const headerCells = header.rows.flat();
  const bounds: Bounds = { rMin: header.cornerRow, rMax: rows.length, cMax: columns.length };
  const isValid = (pos: GridPos) =>
    pos.r >= 1
      ? pos.r <= rows.length && pos.c <= columns.length
      : (pos.r === header.cornerRow && pos.c === 0) || headerCells.some((h) => h.r === pos.r && h.c === pos.c);
  const activePos: GridPos = isValid(active) ? active : { r: rows.length > 0 ? 1 : header.cornerRow, c: 0 };

  const cells = rows.length * columns.length;
  const tooLarge = cells > maxCells;
  const drilledColumns = columns.some((c) => c.kind !== "month");
  const drilledRows = rows.some((row) => row.expanded);
  const bucketCells = cells * bucketLayers(shown).length;

  const onKeyDown = (event: KeyboardEvent<HTMLTableElement>) => {
    const from = (event.target as HTMLElement).closest<HTMLElement>("[data-grid-pos]");
    const table = tableRef.current;
    if (from === null || table === null || event.altKey || event.metaKey) return;
    const to = navigate(table, from, event.key, event.ctrlKey, bounds);
    if (!["ArrowRight", "ArrowLeft", "ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    to?.focus();
  };
  const onFocus = (event: FocusEvent<HTMLTableElement>) => {
    const element = (event.target as HTMLElement).closest<HTMLElement>("[data-grid-pos]");
    const pos = element === null ? null : parsePos(element);
    if (pos !== null) setActive((prev) => (prev.r === pos.r && prev.c === pos.c0 ? prev : { r: pos.r, c: pos.c0 }));
    gridProps.onFocus(event);
  };

  if (matrix.epics.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        {t("reports.deepDive.matrix.empty")}
      </Text>
    );
  }

  const tipRow = tip === null ? undefined : rows[tip.row];
  const tipColumn = tip === null ? undefined : columns[tip.col];
  const tipCell = tipRow?.row.cells[tip?.col ?? 0];
  const gridLabel = t("reports.deepDive.matrix.gridLabel");
  const tableStyle = { ...LAYER_VARS, "--dd-head-rows": header.depth } as CSSProperties;

  return (
    <Stack gap="md">
      <VisuallyHidden role="status" aria-live="polite">
        {announcement}
      </VisuallyHidden>
      {(drilledColumns || drilledRows) && (
        <Group gap="xs" wrap="wrap" role="group" aria-label={t("reports.deepDive.matrix.collapseLabel")}>
          {drilledColumns && (
            <Button variant="subtle" size="compact-xs" onClick={collapseColumns}>
              {t("reports.deepDive.matrix.collapseAllColumns")}
            </Button>
          )}
          {drilledRows && (
            <Button variant="subtle" size="compact-xs" onClick={collapseRows}>
              {t("reports.deepDive.matrix.collapseAllRows")}
            </Button>
          )}
        </Group>
      )}

      {tooLarge ? (
        <Alert color="orange" variant="light">
          {t(drilledColumns || drilledRows ? "reports.deepDive.matrix.tooLarge" : "reports.deepDive.matrix.tooLargeNarrow", {
            rows: rows.length,
            columns: columns.length,
            cells,
          })}
        </Alert>
      ) : (
        <ScrollRegion
          label={gridLabel}
          minWidth={ITEM_COLUMN_WIDTH + columns.reduce((sum, c) => sum + COLUMN_WIDTH[c.kind], 0)}
          maxHeight="70vh"
        >
          <Table
            ref={tableRef}
            stickyHeader
            role="grid"
            aria-label={gridLabel}
            aria-rowcount={rows.length + header.depth}
            aria-colcount={columns.length + 1}
            className={`${classes.heatTable} ${classes.ddTable}`}
            style={tableStyle}
            onKeyDown={onKeyDown}
            onFocus={onFocus}
            onBlur={gridProps.onBlur}
            onMouseOver={gridProps.onMouseOver}
            onMouseLeave={gridProps.onMouseLeave}
          >
            <Table.Thead>
              {header.rows.map((headerRow, k) => (
                <tr key={k}>
                  {k === 0 && (
                    <th
                      scope="col"
                      role="columnheader"
                      rowSpan={header.depth}
                      className={`${classes.heatStickyCol} ${classes.ddColHead} ${classes.ddCornerHead}`}
                      {...roving(header.cornerRow, 0, activePos.r === header.cornerRow && activePos.c === 0)}
                    >
                      {t("reports.deepDive.matrix.itemColumn")}
                    </th>
                  )}
                  {headerRow.map((cell) => (
                    <HeaderCellView
                      key={`${cell.role}:${cell.column.id}`}
                      cell={cell}
                      active={activePos.r === cell.r && activePos.c === cell.c}
                      onDrill={drill}
                    />
                  ))}
                </tr>
              ))}
            </Table.Thead>
            <Table.Tbody>
              {rows.map((gridRow, index) => (
                <MatrixBodyRow
                  key={gridRow.row.id}
                  gridRow={gridRow}
                  r={index + 1}
                  columns={columns}
                  captions={captions}
                  rangeFrom={matrix.range.from}
                  layers={shown}
                  scale={gridRow.detail ? matrix.taskScale : matrix.scale}
                  activeCol={activePos.r === index + 1 ? activePos.c : -1}
                  onToggle={toggleEpic}
                />
              ))}
            </Table.Tbody>
          </Table>
        </ScrollRegion>
      )}

      {tip !== null && tipRow !== undefined && tipColumn !== undefined && tipCell !== undefined && (
        <DeepDiveCellTooltip
          tip={tip}
          tipRef={tipRef}
          title={cellTitle(rowLabel(tipRow.row, t), tipColumn.label)}
          span={columnCaption(tipColumn, t)}
          facts={cellHasFigures(tipCell, shown) ? cellFacts(tipCell, shown, t) : null}
          tipProps={tipProps}
        />
      )}

      <DeepDiveLegend />

      <Stack gap="xs">
        <Title order={4} size="h5">
          {t("reports.deepDive.matrix.summary.title")}
        </Title>
        <Text size="xs" c="dimmed">
          {t("reports.deepDive.matrix.summary.description")}
        </Text>
        <DeepDiveSummaryTable matrix={matrix} />
      </Stack>

      {!tooLarge &&
        (bucketCells > maxCells ? (
          <Text size="sm" c="dimmed">
            {t("reports.deepDive.matrix.buckets.tooLarge", { cells: bucketCells })}
          </Text>
        ) : (
          <DailyTableDisclosure
            showLabel={t("reports.deepDive.matrix.buckets.show")}
            hideLabel={t("reports.deepDive.matrix.buckets.hide")}
          >
            <DeepDiveBucketTable rows={rows} columns={columns} layers={shown} />
          </DailyTableDisclosure>
        ))}
    </Stack>
  );
}
