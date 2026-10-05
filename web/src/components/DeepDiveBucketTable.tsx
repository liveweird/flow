import { memo } from "react";
import { useTranslation } from "react-i18next";
import { Table } from "@mantine/core";
import { bucketLayers, rowLabel, type DeepDiveLayers, type GridRow } from "../utils/deepDiveCell";
import { formatFigure, type MatrixCell, type TimeColumn } from "../utils/deepDiveMatrix";
import classes from "../theme.module.css";
import ScrollRegion from "./ScrollRegion";

const FIGURE: Record<ReturnType<typeof bucketLayers>[number], (cell: MatrixCell) => number> = {
  pv: (cell) => cell.pvMd,
  exec: (cell) => cell.execTaskDays,
  ev: (cell) => cell.evMd,
  cost: (cell) => cell.costMd,
};

/**
 * The grid's numbers as plain text: one line per visible row and shown layer, one column per visible time column
 * (what the bars draw, no more — so it follows the drill state), behind the `DailyTableDisclosure`.
 */
const DeepDiveBucketTable = memo(function DeepDiveBucketTable({
  rows,
  columns,
  layers,
}: {
  rows: readonly GridRow[];
  columns: readonly TimeColumn[];
  layers: DeepDiveLayers;
}) {
  const { t } = useTranslation();
  const label = t("reports.deepDive.matrix.buckets.title");
  const shown = bucketLayers(layers);
  return (
    <ScrollRegion label={label} minWidth={260 + columns.length * 72} maxHeight="60vh">
      <Table stickyHeader className={classes.heatTable} aria-label={label}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th scope="col" className={classes.heatStickyCol} miw={240}>
              {t("reports.deepDive.matrix.itemColumn")}
            </Table.Th>
            {columns.map((column) => (
              <Table.Th key={column.id} scope="col" ta="right">
                {column.label}
              </Table.Th>
            ))}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.flatMap(({ row }) =>
            shown.map((layer) => (
              <tr key={`${row.id}|${layer}`}>
                <th scope="row" className={`${classes.heatStickyCol} ${classes.ddPlainCell}`}>
                  {t("reports.deepDive.matrix.buckets.itemLayer", {
                    item: rowLabel(row, t),
                    layer: t(`reports.deepDive.matrix.layer.${layer}`),
                  })}
                </th>
                {row.cells.map((cell, index) => (
                  <td key={columns[index].id} className={`${classes.ddPlainCell} ${classes.ddNumber}`}>
                    {formatFigure(FIGURE[layer](cell))}
                  </td>
                ))}
              </tr>
            )),
          )}
        </Table.Tbody>
      </Table>
    </ScrollRegion>
  );
});

export default DeepDiveBucketTable;
