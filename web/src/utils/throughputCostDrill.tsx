import type { TFunction } from "i18next";
import type { ColumnDef } from "../components/ColumnTable";
import { drillFigureColumn } from "./drillFigure";
import { throughputCostColumn, type ThroughputFigures } from "./throughputReport";

/**
 * The "Actual cost (MD)" column of a Throughput table, right-aligned. With `onOpen` its figure is a button that opens the tasks the
 * time was logged on (the keyboard route to the chart's cost-bar double-click), named "<figure> — Actual cost (MD): show the items of
 * <row> · Actual cost"; a row with no cost (nothing to list), `disabled` (kept data of a previous filter) or no handler is plain text.
 */
export function throughputCostDrillColumn<T>(
  t: TFunction,
  pick: (row: T) => Pick<ThroughputFigures, "actualCostMd">,
  rowName: (row: T) => string,
  onOpen: ((row: T, trigger: HTMLElement) => void) | undefined,
  disabled: boolean | undefined,
): ColumnDef<T> {
  const column = { ...throughputCostColumn<T>(t, pick), align: "right" as const };
  return onOpen
    ? drillFigureColumn(column, {
        label: (row, figure, header) => t("reports.drill.figureAria", { figure, column: header, name: `${rowName(row)} · ${t("reports.drill.actualCost")}` }),
        count: (row) => (pick(row).actualCostMd > 0 ? 1 : 0),
        onOpen,
        disabled,
      })
    : column;
}
