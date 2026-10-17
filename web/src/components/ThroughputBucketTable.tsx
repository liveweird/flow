import { useTranslation } from "react-i18next";
import { throughputCumulativeColumns, throughputFigureColumns, type ThroughputSeries } from "../utils/throughputReport";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import { drillFigureColumn } from "../utils/drillFigure";
import ScrollRegion from "./ScrollRegion";

type Row = ThroughputSeries["rows"][number];

/**
 * The chart's numbers: one compact row per week/month (the bucket's first day, MD, items) — and, in the cumulative view,
 * the running totals the areas are drawn from. With `onDrill` a row's Delivered figures are buttons that open the tasks
 * behind it (the keyboard route to the chart's double-click).
 */
export default function ThroughputBucketTable({
  series,
  bucket,
  cumulative,
  onDrill,
  drillDisabled,
}: {
  series: ThroughputSeries;
  bucket: "WEEK" | "MONTH";
  cumulative: boolean;
  onDrill?: (row: Row, trigger: HTMLElement) => void;
  /** The rows are kept data of a previous filter: the figures are plain text, not buttons. */
  drillDisabled?: boolean;
}) {
  const { t } = useTranslation();
  // Both Delivered figures of a week/month open its tasks; the running totals are sums over many slots and list nothing.
  const delivered = throughputFigureColumns<Row>(t, (row) => row).map((column) => {
    const right = { ...column, align: "right" as const };
    return onDrill
      ? drillFigureColumn(right, {
          label: (row, figure, column) => t("reports.drill.figureAria", { figure, column, name: row.label }),
          count: (row) => row.deliveredItems,
          onOpen: onDrill,
          disabled: drillDisabled,
        })
      : right;
  });
  const figures = [
    ...delivered,
    ...(cumulative ? throughputCumulativeColumns<Row>(t, (row) => row).map((column) => ({ ...column, align: "right" as const })) : []),
  ];
  const columns: ColumnDef<Row>[] = [
    { key: "bucket", header: t(`reports.throughput.column.bucket${bucket}`), render: (row) => row.label },
    ...figures,
  ];
  return (
    <ScrollRegion label={t("reports.throughput.bucketTableLabel")} minWidth={cumulative ? 520 : 320}>
      <ColumnTable
        verticalSpacing={4}
        aria-label={t("reports.throughput.bucketTableLabel")}
        columns={columns}
        rows={series.rows}
        rowKey={(row) => row.label}
      />
    </ScrollRegion>
  );
}
