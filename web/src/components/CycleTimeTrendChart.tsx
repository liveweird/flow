import "@mantine/charts/styles.css";
import { ChartTooltip, LineChart } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CHART_COLORS } from "../utils/chartColors";
import { dotClickProps } from "../utils/chartDrill";
import type { CycleTrendRow } from "../utils/cycleTimeReport";
import { cssColor } from "../utils/homeTrend";
import { formatDays } from "../utils/reportFormat";
import { partialDot } from "./PartialDot";

/**
 * The cycle-time trend: median (solid blue) and p90 (DASHED gray) in working days per week/month.
 * Hue alone would not separate them (the legend swatches are hue-only): the dash, the table's column
 * headers beside the chart and the tooltip carry identity too. A bucket below the minimum sample has
 * `null` values and `connectNulls` is off, so the line BREAKS there — a gap, never a zero; the
 * dots keep an isolated point visible between two gaps. A lazy chunk: recharts never enters the
 * main bundle. `height` lets the Home overview draw the same chart smaller. Person trend reuses it for its cycle-time chart: a row marked `partial`
 * draws hollow dots (`PartialDot`) and says so in the tooltip; `onPointClick` makes a dot of either line clickable (the line it is on, `p50` or `p90`,
 * and the row's `label`), a gap drawing none.
 */
export default function CycleTimeTrendChart({
  rows,
  height = 280,
  onPointClick,
}: {
  rows: CycleTrendRow[];
  height?: number;
  onPointClick?: (hit: { label: string; series: "p50" | "p90" }) => void;
}) {
  const { t } = useTranslation();
  const series = [
    { name: "p50", label: t("reports.cycleTime.series.p50"), color: CHART_COLORS.trendMedian },
    { name: "p90", label: t("reports.cycleTime.series.p90"), color: CHART_COLORS.trendTail, strokeDasharray: "6 4" },
  ];
  const hit = (label: string, name: string) => {
    const line = name === "p90" ? "p90" : "p50";
    if (rows.find((row) => row.label === label)?.[line] != null) onPointClick?.({ label, series: line });
  };
  const lineProps =
    onPointClick === undefined && !rows.some((row) => row.partial)
      ? undefined
      : (item: { name: string; color?: string }) => {
          const color = item.color ?? CHART_COLORS.trendMedian;
          const partialAt = rows.map((row) => row.partial === true);
          return {
            dot: partialDot(color, partialAt, onPointClick && ((index) => hit(rows[index].label, item.name))),
            ...(onPointClick && {
              activeDot: { fill: cssColor(color), stroke: cssColor(color), ...dotClickProps(item.name, ({ rowId }) => hit(rowId, item.name)) },
            }),
          };
        };
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
        lineProps={lineProps}
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
                      }${row.partial ? `, ${t("reports.personTrend.coverage.PARTIAL")}` : ""}`
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
