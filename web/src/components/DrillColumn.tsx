import { createRef } from "react";
import type { TFunction } from "i18next";
import { ActionIcon, Group, Menu } from "@mantine/core";
import { IconListDetails } from "@tabler/icons-react";
import type { SprintBucket } from "../utils/reportDrill";
import type { ColumnDef } from "./ColumnTable";
import RowActionsMenu from "./RowActionsMenu";

/**
 * The trailing "Items" column of a table whose rows are bars of a chart: one icon button per row that opens the drill drawer
 * of that bar. It is the keyboard and screen-reader route to the drill (the chart's double-click is pointer-only), so its
 * accessible name carries the row: "Show the items of {{name}}". `nameOf` is the row's own words (a week, a person's slot);
 * `header` replaces the default "Items" where that word already names another column (a histogram's item counts).
 * `onDrill` also gets the pressed button, so focus can return to it even where a mouse click does not focus a button (Safari, Firefox).
 * `disabled` keeps the column (and the table's layout) but greys the buttons: the rows are kept data of a previous filter.
 */
export function drillColumn<T>(
  t: TFunction,
  nameOf: (row: T) => string,
  onDrill: (row: T, trigger: HTMLElement) => void,
  disabled = false,
  header = t("reports.drill.column"),
): ColumnDef<T> {
  return {
    key: "drill",
    header,
    align: "right",
    render: (row) => (
      <ActionIcon size="sm" disabled={disabled} aria-label={t("reports.drill.openAria", { name: nameOf(row) })} onClick={(event) => onDrill(row, event.currentTarget)}>
        <IconListDetails size={16} />
      </ActionIcon>
    ),
  };
}

/** More sprint buckets than this in one row bundle under the row-actions kebab (web/CLAUDE.md "Row actions"). */
const MAX_INLINE_BUCKETS = 2;

/**
 * The "Items" column of a sprint row: a sprint bar has one figure per bucket (Commitments draws two, Home three, Sprint
 * consistency seven), so the row gets ONE button per bucket — "Show the items of {{name}} · {{bucket}}" — or, past two, the
 * shared kebab menu with one entry per bucket. `onDrill` receives the row, the bucket and the pressed element (for the kebab menu, the
 * kebab button — a menu item is gone once the menu closes). `disabled` keeps the column but greys the buttons (see `drillColumn`).
 */
export function sprintDrillColumn<T>(
  t: TFunction,
  nameOf: (row: T) => string,
  buckets: readonly SprintBucket[],
  onDrill: (row: T, bucket: SprintBucket, trigger: HTMLElement) => void,
  disabled = false,
): ColumnDef<T> {
  return {
    key: "drill",
    header: t("reports.drill.column"),
    align: "right",
    render: (row) => {
      // Not a component, so no hook: the kebab button is captured in a plain ref; a menu item is unmounted once the menu closes.
      const kebab = createRef<HTMLButtonElement>();
      return buckets.length > MAX_INLINE_BUCKETS ? (
        <RowActionsMenu label={t("common.table.operationsAria", { name: nameOf(row) })} triggerRef={kebab} disabled={disabled}>
          <Menu.Label>{t("reports.drill.column")}</Menu.Label>
          {buckets.map((bucket) => (
            <Menu.Item
              key={bucket}
              leftSection={<IconListDetails size={16} />}
              onClick={(event) => onDrill(row, bucket, kebab.current ?? event.currentTarget)}
            >
              {t(`reports.drill.bucket.${bucket}`)}
            </Menu.Item>
          ))}
        </RowActionsMenu>
      ) : (
        <Group gap={4} justify="flex-end" wrap="nowrap">
          {buckets.map((bucket) => (
            <ActionIcon
              key={bucket}
              size="sm"
              disabled={disabled}
              aria-label={t("reports.drill.openBucketAria", { name: nameOf(row), bucket: t(`reports.drill.bucket.${bucket}`) })}
              onClick={(event) => onDrill(row, bucket, event.currentTarget)}
            >
              <IconListDetails size={16} />
            </ActionIcon>
          ))}
        </Group>
      );
    },
  };
}
