import { useTranslation } from "react-i18next";
import { Table } from "@mantine/core";
import type { EvmRow } from "../utils/epicProgressReport";
import { formatMd } from "../utils/reportFormat";
import ScrollRegion from "./ScrollRegion";

/** The chart's numbers, newest day first: cumulative PV, EV and AC (and the original plan at EPIC level). */
export default function EpicProgressTable({ rows, withOriginal }: { rows: readonly EvmRow[]; withOriginal: boolean }) {
  const { t } = useTranslation();
  return (
    <ScrollRegion label={t("reports.epicProgress.tableLabel")} minWidth={360} maxHeight={360}>
      <Table verticalSpacing={4} stickyHeader aria-label={t("reports.epicProgress.tableLabel")}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t("reports.daily.day")}</Table.Th>
            <Table.Th ta="right">{t("reports.epicProgress.column.pv")}</Table.Th>
            {withOriginal && <Table.Th ta="right">{t("reports.epicProgress.column.pvOriginal")}</Table.Th>}
            <Table.Th ta="right">{t("reports.epicProgress.column.ev")}</Table.Th>
            <Table.Th ta="right">{t("reports.epicProgress.column.ac")}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {[...rows].reverse().map((row) => (
            <Table.Tr key={row.date}>
              <Table.Td>{row.date}</Table.Td>
              <Table.Td ta="right">{formatMd(row.pv)}</Table.Td>
              {withOriginal && <Table.Td ta="right">{row.pvOriginal === null ? "—" : formatMd(row.pvOriginal)}</Table.Td>}
              <Table.Td ta="right">{formatMd(row.ev)}</Table.Td>
              <Table.Td ta="right">{formatMd(row.ac)}</Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </ScrollRegion>
  );
}
