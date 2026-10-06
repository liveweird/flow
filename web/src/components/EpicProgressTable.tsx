import { useTranslation } from "react-i18next";
import type { EvmRow } from "../utils/epicProgressReport";
import { formatMd } from "../utils/reportFormat";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import ScrollRegion from "./ScrollRegion";

/** The chart's numbers, newest day first: cumulative PV, EV and AC (and the original plan at EPIC level). */
export default function EpicProgressTable({ rows, withOriginal }: { rows: readonly EvmRow[]; withOriginal: boolean }) {
  const { t } = useTranslation();
  const columns: ColumnDef<EvmRow>[] = [
    { key: "day", header: t("reports.daily.day"), render: (row) => row.date },
    { key: "pv", header: t("reports.epicProgress.column.pv"), render: (row) => formatMd(row.pv), align: "right" },
    ...(withOriginal
      ? [
          {
            key: "pvOriginal",
            header: t("reports.epicProgress.column.pvOriginal"),
            render: (row: EvmRow) => (row.pvOriginal === null ? "—" : formatMd(row.pvOriginal)),
            align: "right" as const,
          },
        ]
      : []),
    { key: "ev", header: t("reports.epicProgress.column.ev"), render: (row) => formatMd(row.ev), align: "right" },
    { key: "ac", header: t("reports.epicProgress.column.ac"), render: (row) => formatMd(row.ac), align: "right" },
  ];
  return (
    <ScrollRegion label={t("reports.epicProgress.tableLabel")} minWidth={360} maxHeight={360}>
      <ColumnTable
        verticalSpacing={4}
        stickyHeader
        aria-label={t("reports.epicProgress.tableLabel")}
        columns={columns}
        rows={[...rows].reverse()}
        rowKey={(row) => row.date}
      />
    </ScrollRegion>
  );
}
