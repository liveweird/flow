import { useTranslation } from "react-i18next";
import type { EpicProgressTrendPoint } from "../api/reports";
import { formatIndex } from "../utils/reportFormat";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import ScrollRegion from "./ScrollRegion";

const index = (value: number | null) => (value === null ? "—" : formatIndex(value));

/** The trend chart's numbers, newest week first; a hidden index (too little planned or spent in its window) is a dash. */
export default function EpicProgressTrendTable({ points }: { points: readonly EpicProgressTrendPoint[] }) {
  const { t } = useTranslation();
  const label = t("reports.epicProgress.trend.tableLabel");
  const columns: ColumnDef<EpicProgressTrendPoint>[] = [
    { key: "day", header: t("reports.epicProgress.trend.column.day"), render: (point) => point.day },
    { key: "spi", header: t("reports.epicProgress.column.spi"), render: (point) => index(point.spi), align: "right" },
    { key: "cpi", header: t("reports.epicProgress.column.cpi"), render: (point) => index(point.cpi), align: "right" },
  ];
  return (
    <ScrollRegion label={label} minWidth={240} maxHeight={260}>
      <ColumnTable verticalSpacing={4} stickyHeader aria-label={label} columns={columns} rows={[...points].reverse()} rowKey={(point) => point.day} />
    </ScrollRegion>
  );
}
