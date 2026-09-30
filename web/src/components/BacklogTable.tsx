import { useTranslation } from "react-i18next";
import { Table } from "@mantine/core";
import type { BacklogRow } from "../utils/backlogReport";
import { formatMd } from "../utils/reportFormat";
import ScrollRegion from "./ScrollRegion";

/** The trend chart's numbers, newest day first: man-days and item count. */
export default function BacklogTable({ rows }: { rows: readonly BacklogRow[] }) {
  const { t } = useTranslation();
  return (
    <ScrollRegion label={t("reports.backlog.tableLabel")} minWidth={320} maxHeight={360}>
      <Table verticalSpacing={4} stickyHeader aria-label={t("reports.backlog.tableLabel")}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t("reports.daily.day")}</Table.Th>
            <Table.Th ta="right">{t("reports.backlog.column.md")}</Table.Th>
            <Table.Th ta="right">{t("reports.backlog.column.items")}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {[...rows].reverse().map((row) => (
            <Table.Tr key={row.day}>
              <Table.Td>{row.day}</Table.Td>
              <Table.Td ta="right">{formatMd(row.md)}</Table.Td>
              <Table.Td ta="right">{row.items}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </ScrollRegion>
  );
}
