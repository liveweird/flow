import "@mantine/charts/styles.css";
import { ChartTooltip, LineChart } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CHART_COLORS } from "../utils/chartColors";
import type { CycleTrendRow } from "../utils/cycleTimeReport";
import { formatDays } from "../utils/reportFormat";

/**
 * The cycle-time trend: median (solid blue) and p90 (DASHED gray) in working days per week/month.
 * Hue alone would not separate them (the legend swatches are hue-only): the dash, the table's column
 * headers beside the chart and the tooltip carry identity too. A bucket below the minimum sample has
 * `null` values and `connectNulls` is off, so the line BREAKS there — a gap, never a zero; the
 * dots keep an isolated point visible between two gaps. A lazy chunk: recharts never enters the
 * main bundle. `height` lets the Home overview draw the same chart smaller.
 */
export default function CycleTimeTrendChart({ rows, height = 280 }: { rows: CycleTrendRow[]; height?: number }) {
  const { t } = useTranslation();
  const series = [
    { name: "p50", label: t("reports.cycleTime.series.p50"), color: CHART_COLORS.trendMedian },
    { name: "p90", label: t("reports.cycleTime.series.p90"), color: CHART_COLORS.trendTail, strokeDasharray: "6 4" },
  ];
  return (
    <Box role="group" aria-label={t("reports.cycleTime.trendLabel")}>
      <LineChart
        h={height}
        data={rows}
        dataKey="label"
        series={series}
        curveType="linear"
        connectNulls={false}
        withDots
        withLegend
        legendProps={{ verticalAlign: "top", height: 36 }}
        yAxisLabel={t("reports.cycleTime.workingAxis")}
        valueFormatter={formatDays}
        tickLine="y"
        xAxisProps={rows.length > 8 ? { angle: -30, textAnchor: "end", height: 70 } : undefined}
        tooltipProps={{
          content: ({ label, payload }) => {
            // The row comes from the HOVERED LABEL, not the payload: a hidden bucket has no plottable
            // value, so recharts may hand over an empty payload for it — and it is exactly the bucket
            // whose n the reader needs.
            const row = rows.find((candidate) => candidate.label === label);
            const hidden = row !== undefined && row.p50 === null && row.p90 === null;
            return (
              <ChartTooltip
                label={
                  row
                    ? `${String(label)} · ${t("reports.items", { count: row.n })}${
                        hidden && row.n > 0 ? `, ${t("reports.cycleTime.belowMinimum")}` : ""
                      }`
                    : label
                }
                payload={payload ?? []}
                series={series}
                valueFormatter={formatDays}
              />
            );
          },
        }}
      />
    </Box>
  );
}
