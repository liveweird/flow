import { useTranslation } from "react-i18next";
import { formatMd } from "../utils/reportFormat";
import type { ThroughputChartRow } from "../utils/throughputReport";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import ScrollRegion from "./ScrollRegion";

/** The chart's numbers: one compact row per week/month (the bucket's first day, MD, items). */
export default function ThroughputBucketTable({ rows, bucket }: { rows: ThroughputChartRow[]; bucket: "WEEK" | "MONTH" }) {
  const { t } = useTranslation();
  const columns: ColumnDef<ThroughputChartRow>[] = [
    { key: "bucket", header: t(`reports.throughput.column.bucket${bucket}`), render: (row) => row.label },
    {
      key: "md",
      header: t("reports.throughput.column.deliveredMd"),
      render: (row) => formatMd(row.deliveredMd),
      align: "right",
    },
    {
      key: "items",
      header: t("reports.throughput.column.deliveredItems"),
      render: (row) => row.deliveredItems,
      align: "right",
    },
  ];
  return (
    <ScrollRegion label={t("reports.throughput.bucketTableLabel")} minWidth={320}>
      <ColumnTable
        verticalSpacing={4}
        aria-label={t("reports.throughput.bucketTableLabel")}
        columns={columns}
        rows={rows}
        rowKey={(row) => row.label}
      />
    </ScrollRegion>
  );
}
