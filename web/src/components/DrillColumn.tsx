import type { TFunction } from "i18next";
import { ActionIcon } from "@mantine/core";
import { IconListDetails } from "@tabler/icons-react";
import type { ColumnDef } from "./ColumnTable";

/**
 * The trailing "Items" column of a table whose rows are bars of a chart: one icon button per row that opens the drill drawer
 * of that bar. It is the keyboard and screen-reader route to the drill (the chart's double-click is pointer-only), so its
 * accessible name carries the row: "Show the items of {{name}}". `nameOf` is the row's own words (a week, a person's slot).
 * `onDrill` also gets the pressed button, so focus can return to it even where a mouse click does not focus a button (Safari, Firefox).
 */
export function drillColumn<T>(
  t: TFunction,
  nameOf: (row: T) => string,
  onDrill: (row: T, trigger: HTMLElement) => void,
): ColumnDef<T> {
  return {
    key: "drill",
    header: t("reports.drill.column"),
    align: "right",
    render: (row) => (
      <ActionIcon size="sm" aria-label={t("reports.drill.openAria", { name: nameOf(row) })} onClick={(event) => onDrill(row, event.currentTarget)}>
        <IconListDetails size={16} />
      </ActionIcon>
    ),
  };
}
