import "@mantine/charts/styles.css";
import { LineChart } from "@mantine/charts";
import { Box, useComputedColorScheme } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { EpicProgressTrendPoint } from "../api/reports";
import { AS_OF_COLOR, CHART_COLORS } from "../utils/chartColors";
import { formatIndex } from "../utils/reportFormat";

/**
 * SPI and CPI per week end on ONE index axis (never a second axis next to the man-day chart): SPI in the plan blue, CPI in the
 * neutral cost gray — the legend, the dash on CPI, the tooltip and the table beside the chart carry identity — with a labelled
 * reference line at 1.0 (on plan / on budget). A week whose index is hidden (too little planned or spent in its window) is a null:
 * `connectNulls` is off, so it is a gap, never a zero. A lazy chunk: recharts never enters the main bundle.
 */
export default function EpicProgressTrendChart({ points }: { points: readonly EpicProgressTrendPoint[] }) {
  const { t } = useTranslation();
  const scheme = useComputedColorScheme("light");
  const series = [
    { name: "spi", label: t("reports.epicProgress.trend.series.spi"), color: CHART_COLORS.evmTrendSpi },
    { name: "cpi", label: t("reports.epicProgress.trend.series.cpi"), color: CHART_COLORS.evmTrendCpi, strokeDasharray: "6 4" },
  ];
  return (
    <Box role="group" aria-label={t("reports.epicProgress.trend.chartLabel")}>
      <LineChart
        h={260}
        data={points.map((point) => ({ day: point.day, spi: point.spi, cpi: point.cpi }))}
        dataKey="day"
        series={series}
        referenceLines={[
          {
            y: 1,
            color: AS_OF_COLOR[scheme],
            strokeDasharray: "2 4",
            label: t("reports.epicProgress.trend.target"),
            labelPosition: "insideBottomRight" as const,
          },
        ]}
        curveType="linear"
        connectNulls={false}
        withDots
        withLegend
        legendProps={{ verticalAlign: "top", height: 36 }}
        yAxisLabel={t("reports.epicProgress.trend.unit")}
        valueFormatter={formatIndex}
        tickLine="y"
        xAxisProps={{ minTickGap: 48 }}
      />
    </Box>
  );
}
