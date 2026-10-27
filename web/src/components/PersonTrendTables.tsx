import { useTranslation } from "react-i18next";
import type { PersonTrendMixSeries, PersonTrendReport, PersonTrendRow, ReportFilters } from "../api/reports";
import { mixKeyLabel } from "../utils/comparePeopleReport";
import { figureText, type TrendChartPoint } from "../utils/personTrendReport";
import { formatMd, formatPercent } from "../utils/reportFormat";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import ScrollRegion from "./ScrollRegion";

/** A row of the table: the window's index and its end, so every column reads its own series at the same position. */
interface WindowRow {
  index: number;
  end: string;
}

/**
 * The numbers behind one trend chart, one row per window: its end, the figure(s), the sample and the window's coverage in words — the table
 * carries what the dot's shape and the line's gaps say. A figure withheld under the minimum sample says so in its own cell.
 */
export function PersonTrendFigureTable({
  rows,
  seriesLabels,
  pointsOf,
  minSampleSize,
  label,
}: {
  rows: readonly PersonTrendRow[];
  /** The column header of each row's figure. */
  seriesLabels: readonly string[];
  /** The row's chart points (gaps applied), index-aligned with the windows. */
  pointsOf: (row: PersonTrendRow) => readonly TrendChartPoint[];
  minSampleSize: number;
  label: string;
}) {
  const { t } = useTranslation();
  const first = pointsOf(rows[0]);
  const windows: WindowRow[] = first.map((point, index) => ({ index, end: point.label }));
  const columns: ColumnDef<WindowRow>[] = [
    { key: "end", header: t("reports.personTrend.table.end"), render: (row) => row.end },
    ...rows.map((row, position) => ({
      key: row.key,
      header: seriesLabels[position],
      align: "right" as const,
      render: (window: WindowRow) => {
        const point = pointsOf(row)[window.index];
        return point.hidden && point.coverage !== "NONE" ? `${figureText(row, point)} (${t("reports.personTrend.belowMin", { min: minSampleSize })})` : figureText(row, point);
      },
    })),
    { key: "n", header: t("reports.personTrend.table.n"), align: "right", render: (row) => first[row.index].n },
    { key: "window", header: t("reports.personTrend.table.window"), render: (row) => t(`reports.personTrend.coverage.${first[row.index].coverage}`) },
  ];
  return (
    <ScrollRegion label={label} minWidth={420} maxHeight={360}>
      <ColumnTable verticalSpacing={4} aria-label={label} columns={columns} rows={windows} rowKey={(row) => row.end} />
    </ScrollRegion>
  );
}

/**
 * The numbers behind a work-mix chart: per window its share and man-days under every key (`45% · 3.2 MD`), a dash for a window that delivered
 * nothing, and the window's coverage in words.
 */
export function PersonTrendMixTable({
  by,
  series,
  windows,
  domains,
  label,
}: {
  by: "activityType" | "workCategory";
  series: readonly PersonTrendMixSeries[];
  windows: PersonTrendReport["windows"];
  domains: ReportFilters["domains"];
  label: string;
}) {
  const { t } = useTranslation();
  const columns: ColumnDef<number>[] = [
    { key: "end", header: t("reports.personTrend.table.end"), render: (index) => windows[index].end },
    ...series.map((item) => ({
      key: item.key,
      header: mixKeyLabel(by, item.key, t, domains),
      align: "right" as const,
      render: (index: number) => {
        const point = item.points[index];
        return point?.share === undefined ? "—" : `${formatPercent(point.share)} · ${formatMd(point.md)} ${t("reports.unitMd")}`;
      },
    })),
    { key: "window", header: t("reports.personTrend.table.window"), render: (index) => t(`reports.personTrend.coverage.${windows[index].coverage}`) },
  ];
  return (
    <ScrollRegion label={label} minWidth={420} maxHeight={360}>
      <ColumnTable verticalSpacing={4} aria-label={label} columns={columns} rows={windows.map((_window, index) => index)} rowKey={(index) => String(index)} />
    </ScrollRegion>
  );
}
