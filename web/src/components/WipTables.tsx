import { useTranslation } from "react-i18next";
import type { WipPoint } from "../api/reports";
import { wipBandSummary, type WipBand } from "../utils/wipReport";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import ScrollRegion from "./ScrollRegion";

/** The chart's text alternative: per band, the latest day's count, the daily average and the peak. */
export function WipSummaryTable({ series, bands }: { series: readonly WipPoint[]; bands: readonly WipBand[] }) {
  const { t } = useTranslation();
  const latestDay = series.at(-1)?.day ?? "";
  const summaries = bands.map((band) => ({ band, summary: wipBandSummary(series, band.key) }));
  type Row = (typeof summaries)[number];
  const columns: ColumnDef<Row>[] = [
    { key: "band", header: t("reports.wip.column.band"), render: ({ band }) => band.label },
    {
      key: "latest",
      header: t("reports.wip.column.latest", { day: latestDay }),
      render: ({ summary }) => summary.latest,
      align: "right",
    },
    { key: "average", header: t("reports.wip.column.average"), render: ({ summary }) => summary.average, align: "right" },
    { key: "peak", header: t("reports.wip.column.peak"), render: ({ summary }) => summary.peak, align: "right" },
  ];
  return (
    <ScrollRegion label={t("reports.wip.summaryLabel")} minWidth={320}>
      <ColumnTable
        verticalSpacing={4}
        aria-label={t("reports.wip.summaryLabel")}
        columns={columns}
        rows={summaries}
        rowKey={({ band }) => band.name}
      />
    </ScrollRegion>
  );
}

/** Every day of the series, newest first: the day and each band's end-of-day count. */
export function WipDailyTable({ series, bands }: { series: readonly WipPoint[]; bands: readonly WipBand[] }) {
  const { t } = useTranslation();
  const columns: ColumnDef<WipPoint>[] = [
    { key: "day", header: t("reports.daily.day"), render: (point) => point.day },
    ...bands.map((band) => ({
      key: band.name,
      header: band.label,
      render: (point: WipPoint) => point.counts[band.key] ?? 0,
      align: "right" as const,
    })),
  ];
  return (
    <ScrollRegion label={t("reports.wip.dailyLabel")} minWidth={320} maxHeight={360}>
      <ColumnTable
        verticalSpacing={4}
        stickyHeader
        aria-label={t("reports.wip.dailyLabel")}
        columns={columns}
        rows={[...series].reverse()}
        rowKey={(point) => point.day}
      />
    </ScrollRegion>
  );
}
