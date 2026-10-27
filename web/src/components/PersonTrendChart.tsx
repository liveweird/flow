import "@mantine/charts/styles.css";
import { ChartTooltip, LineChart } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CHART_COLORS } from "../utils/chartColors";
import { isPartial, pointTitle, type TrendChartPoint } from "../utils/personTrendReport";
import type { ValueFormat } from "../utils/reportFormat";
import { partialDot } from "./PartialDot";

/**
 * One row of Person trend: the person's figure at the end of each 90-day window, one line. A hidden figure, a figure with no denominator and a
 * window with nothing derived are `null` and `connectNulls` is off, so the line BREAKS there — a gap, never a zero. A window that only partly
 * lies in the derived span is a HOLLOW dot (`PartialDot`), and the tooltip says "partial" beside the sample. One series, so no legend: the card
 * title names it. A lazy chunk: recharts never enters the main bundle.
 */
export default function PersonTrendChart({
  points,
  seriesLabel,
  axisLabel,
  format,
  minSampleSize,
  domain,
}: {
  points: readonly TrendChartPoint[];
  /** The tooltip's name for the line (the row's label). */
  seriesLabel: string;
  axisLabel: string;
  format: ValueFormat;
  minSampleSize: number;
  /** A fixed y range (a share is 0..1, so an all-zero line does not stretch the axis to 400% and a ratio never overshoots 100%); absent = the library's own. */
  domain?: readonly [number, number];
}) {
  const { t } = useTranslation();
  const series = [{ name: "value", label: seriesLabel, color: CHART_COLORS.trendMedian }];
  const rows = points.map((point) => ({ label: point.label, value: point.value }));
  return (
    <Box role="group" aria-label={seriesLabel}>
      <LineChart
        h={240}
        data={rows}
        dataKey="label"
        series={series}
        curveType="linear"
        connectNulls={false}
        withDots
        lineProps={{ dot: partialDot(CHART_COLORS.trendMedian, points.map(isPartial)) }}
        yAxisLabel={axisLabel}
        valueFormatter={format}
        tickLine="y"
        yAxisProps={domain === undefined ? undefined : { domain: [...domain] }}
        xAxisProps={{ minTickGap: 24, ...(points.length > 8 ? { angle: -30, textAnchor: "end", height: 70 } : {}) }}
        tooltipProps={{
          content: ({ label, payload }) => {
            // The point comes from the HOVERED LABEL, not the payload: a gap has no plottable value, so recharts may hand over an empty payload for it.
            const point = points.find((candidate) => candidate.label === label);
            return (
              <ChartTooltip
                label={point === undefined ? label : pointTitle(point, minSampleSize, t)}
                payload={payload ?? []}
                series={series}
                valueFormatter={format}
              />
            );
          },
        }}
      />
    </Box>
  );
}
