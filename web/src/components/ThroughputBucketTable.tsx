import { useTranslation } from "react-i18next";
import { throughputCumulativeColumns, throughputFigureColumns, type ThroughputSeries } from "../utils/throughputReport";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import ScrollRegion from "./ScrollRegion";

type Row = ThroughputSeries["rows"][number];

/**
 * The chart's numbers: one compact row per week/month (the bucket's first day, MD, items) — and, in the cumulative view,
 * the running totals the areas are drawn from.
 */
export default function ThroughputBucketTable({
  series,
  bucket,
  cumulative,
}: {
  series: ThroughputSeries;
  bucket: "WEEK" | "MONTH";
  cumulative: boolean;
}) {
  const { t } = useTranslation();
  const figures = [
    ...throughputFigureColumns<Row>(t, (row) => row),
    ...(cumulative ? throughputCumulativeColumns<Row>(t, (row) => row) : []),
  ].map((column) => ({ ...column, align: "right" as const }));
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
