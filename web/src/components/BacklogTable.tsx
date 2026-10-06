import { useTranslation } from "react-i18next";
import type { BacklogRow } from "../utils/backlogReport";
import { formatMd } from "../utils/reportFormat";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import ScrollRegion from "./ScrollRegion";

/** The trend chart's numbers, newest day first: man-days and item count. */
export default function BacklogTable({ rows }: { rows: readonly BacklogRow[] }) {
  const { t } = useTranslation();
  const columns: ColumnDef<BacklogRow>[] = [
    { key: "day", header: t("reports.daily.day"), render: (row) => row.day },
    { key: "md", header: t("reports.backlog.column.md"), render: (row) => formatMd(row.md), align: "right" },
    { key: "items", header: t("reports.backlog.column.items"), render: (row) => row.items, align: "right" },
  ];
  return (
    <ScrollRegion label={t("reports.backlog.tableLabel")} minWidth={320} maxHeight={360}>
      <ColumnTable
        verticalSpacing={4}
        stickyHeader
        aria-label={t("reports.backlog.tableLabel")}
        columns={columns}
        rows={[...rows].reverse()}
        rowKey={(row) => row.day}
      />
    </ScrollRegion>
  );
}
