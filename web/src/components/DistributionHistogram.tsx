import "@mantine/charts/styles.css";
import { BarChart, type BarChartSeries } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CHART_COLORS } from "../utils/chartColors";

export interface HistogramRow {
  /** The bucket's range, already formatted ("0.50–0.75"; the open tail "≥ 12"). */
  label: string;
  count: number;
  /** The final open bucket (the long tail): plotted as its own gray series, so colour, legend and tooltip all name it. */
  open?: boolean;
}

/**
 * A distribution's histogram: item count per equal-width range. One series, so no legend; blue
 * (the estimate/plan side of the vocabulary). When the response ends in the open long-tail bucket, that bar is a second,
 * neutral gray series (stacked, so each range keeps one full-width bar) and a legend names both. A lazy chunk — recharts never
 * enters the main bundle.
 */
export default function DistributionHistogram({
  rows,
  name,
  xAxisLabel,
}: {
  rows: HistogramRow[];
  name: string;
  xAxisLabel: string;
}) {
  const { t } = useTranslation();
  const hasTail = rows.some((row) => row.open);
  // A series a range does not belong to is absent (undefined), never 0: the tooltip lists only the bar's own series.
  const data = hasTail
    ? rows.map((row) => ({ label: row.label, count: row.open ? undefined : row.count, tail: row.open ? row.count : undefined }))
    : rows;
  const series: BarChartSeries[] = [{ name: "count", label: t("reports.distribution.count"), color: CHART_COLORS.estimate }];
  if (hasTail) series.push({ name: "tail", label: t("reports.distribution.tailSeries"), color: CHART_COLORS.longTail });
  return (
    <Box role="group" aria-label={`${t("reports.distribution.histogramLabel")} — ${name}`}>
      <BarChart
        h={220}
        data={data}
        dataKey="label"
        type="stacked"
        withLegend={hasTail}
        series={series}
        xAxisLabel={xAxisLabel}
        yAxisLabel={t("reports.distribution.yAxis")}
        tickLine="y"
        barProps={{ maxBarSize: 40 }}
        xAxisProps={rows.length > 6 ? { interval: 0, angle: -30, textAnchor: "end", height: 70 } : undefined}
      />
    </Box>
  );
}
