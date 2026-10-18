import type { TFunction } from "i18next";
import type { ColumnDef } from "../components/ColumnTable";
import { drillFigureColumn } from "./drillFigure";
import type { HomeFigureRow } from "./homeOverview";

interface FigureColumnsOptions {
  t: TFunction;
  format: (value: number) => string;
  /** The first column's header: "Team", or "Measure" for a table of lines. */
  labelHeader: string;
  valueHeader: string;
  /** The header of the drill figure column beside the value ("Items", "Items within"); unused when [valueIsItems]. */
  itemsHeader?: string;
  /** The value IS the number of items (a count): it becomes the drill figure and no second column is drawn. */
  valueIsItems?: boolean;
  /** The Previous and Trend columns (a figure with a trend). */
  withTrend?: boolean;
  /** The sample column (a PRED's "of N"), for figures whose drill count is not the sample. */
  withSample?: boolean;
  /** The window's length in days, for the "Previous N days" headers. */
  days: number;
  /** What a drill figure's accessible name says it opens: "Alpha · Median cycle time". */
  nameOf: (row: HomeFigureRow) => string;
  onOpen: (row: HomeFigureRow, trigger: HTMLElement) => void;
}

/**
 * The columns of a Home chart's hidden table (screen readers read it, keyboard users reveal it with Tab): who, the figure, the items
 * behind it as the drill button, and — where the figure has a trend — the previous window's value and the verdict as TEXT. A hidden
 * figure (under the minimum sample) says so in words and still lists its items.
 */
export function figureColumns(options: FigureColumnsOptions): ColumnDef<HomeFigureRow>[] {
  const { t, format, valueIsItems = false, withTrend = false, withSample = false } = options;
  const valueText = (row: HomeFigureRow): string => {
    if (row.value !== null) return format(row.value);
    return row.hidden ? t("home.table.belowMinimum") : "—";
  };
  const previousText = (row: HomeFigureRow): string => {
    const previous = row.previous;
    if (previous === null) return "—";
    if (previous.value !== null) return format(previous.value);
    return previous.hidden ? t("home.table.belowMinimum") : "—";
  };
  const drillable = (column: ColumnDef<HomeFigureRow>): ColumnDef<HomeFigureRow> =>
    drillFigureColumn(column, {
      label: (row, figure, header) => t("reports.drill.figureAria", { figure, column: header, name: options.nameOf(row) }),
      count: (row) => row.count,
      onOpen: options.onOpen,
    });
  const label: ColumnDef<HomeFigureRow> = { key: "label", header: options.labelHeader, render: (row) => row.label };
  const value: ColumnDef<HomeFigureRow> = { key: "value", header: options.valueHeader, render: valueText, align: "right" };
  const columns = valueIsItems ? [label, drillable(value)] : [label, value];
  if (!valueIsItems) {
    columns.push(
      drillable({ key: "items", header: options.itemsHeader ?? t("home.table.items"), render: (row) => String(row.count), align: "right" }),
    );
  }
  if (withSample) columns.push({ key: "sample", header: t("home.table.sample"), render: (row) => String(row.n), align: "right" });
  if (withTrend) {
    columns.push(
      {
        key: "previous",
        header: t("home.table.previous", { days: options.days }),
        render: previousText,
        align: "right",
      },
      { key: "trend", header: t("home.table.trend", { days: options.days }), render: (row) => (row.trend ? t(`home.trend.${row.trend.tone}`) : "—") },
    );
  }
  return columns;
}
