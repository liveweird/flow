import { useTranslation } from "react-i18next";
import { throughputCumulativeColumns, throughputFigureColumns, type ThroughputSeries } from "../utils/throughputReport";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import { drillColumn } from "./DrillColumn";
import ScrollRegion from "./ScrollRegion";

type Row = ThroughputSeries["rows"][number];

/**
 * The chart's numbers: one compact row per week/month (the bucket's first day, MD, items) — and, in the cumulative view,
 * the running totals the areas are drawn from. With `onDrill` each row also has an Items button that opens the tasks behind it
 * (the keyboard route to the chart's double-click).
 */
export default function ThroughputBucketTable({
  series,
  bucket,
  cumulative,
  onDrill,
}: {
  series: ThroughputSeries;
  bucket: "WEEK" | "MONTH";
  cumulative: boolean;
  onDrill?: (row: Row, trigger: HTMLElement) => void;
}) {
  const { t } = useTranslation();
  const figures = [
    ...throughputFigureColumns<Row>(t, (row) => row),
    ...(cumulative ? throughputCumulativeColumns<Row>(t, (row) => row) : []),
  ].map((column) => ({ ...column, align: "right" as const }));
  const columns: ColumnDef<Row>[] = [
    { key: "bucket", header: t(`reports.throughput.column.bucket${bucket}`), render: (row) => row.label },
    ...figures,
    ...(onDrill ? [drillColumn<Row>(t, (row) => row.label, onDrill)] : []),
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
