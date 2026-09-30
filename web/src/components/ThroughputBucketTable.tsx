import { useTranslation } from "react-i18next";
import { Table } from "@mantine/core";
import { formatMd } from "../utils/reportFormat";
import type { ThroughputChartRow } from "../utils/throughputReport";
import ScrollRegion from "./ScrollRegion";

/** The chart's numbers: one compact row per week/month (the bucket's first day, MD, items). */
export default function ThroughputBucketTable({ rows, bucket }: { rows: ThroughputChartRow[]; bucket: "WEEK" | "MONTH" }) {
  const { t } = useTranslation();
  return (
    <ScrollRegion label={t("reports.throughput.bucketTableLabel")} minWidth={320}>
      <Table verticalSpacing={4} aria-label={t("reports.throughput.bucketTableLabel")}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t(`reports.throughput.column.bucket${bucket}`)}</Table.Th>
            <Table.Th ta="right">{t("reports.throughput.column.deliveredMd")}</Table.Th>
            <Table.Th ta="right">{t("reports.throughput.column.deliveredItems")}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map((row) => (
            <Table.Tr key={row.label}>
              <Table.Td>{row.label}</Table.Td>
              <Table.Td ta="right">{formatMd(row.deliveredMd)}</Table.Td>
              <Table.Td ta="right">{row.deliveredItems}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </ScrollRegion>
  );
}
