import "@mantine/charts/styles.css";
import { ChartTooltip, LineChart } from "@mantine/charts";
import { Box } from "@mantine/core";
import type { TelemetryRange } from "../api/telemetry";
import { CATEGORY_PALETTE } from "../utils/chartColors";
import { formatTrendValue, type TrendUnit } from "../utils/telemetryTrend";

export interface TrendLine {
  label: string;
}

/**
 * One small line chart of a panel section's trend: every line shares ONE unit (one axis), a legend from two lines on, colours from
 * the categorical palette (a line is a role, a schema, a percentile: no meaning of its own, so the legend, the tooltip and the
 * numbers table below carry the identity). A lazy chunk: recharts never enters the main bundle. `rows` are `chartRows` (the
 * formatted time as `label`, `s<i>` per line).
 */
export default function TelemetryTrendChart({
  rows,
  lines,
  unit,
  range,
  ariaLabel,
}: {
  rows: Record<string, string | number | undefined>[];
  lines: TrendLine[];
  unit: TrendUnit;
  range: TelemetryRange;
  ariaLabel: string;
}) {
  const series = lines.map((line, index) => ({
    name: `s${index}`,
    label: line.label,
    color: CATEGORY_PALETTE[index % CATEGORY_PALETTE.length] ?? "flow.6",
  }));
  // A day reads as clock times (with the date once the window crosses midnight), a week or three months as dates; the tooltip
  // carries the full stamp and says it is UTC.
  const first = rows[0]?.label;
  const last = rows[rows.length - 1]?.label;
  const crossesMidnight = typeof first === "string" && typeof last === "string" && first.slice(0, 10) !== last.slice(0, 10);
  const tick = (value: string) => (range === "24h" ? (crossesMidnight ? value.slice(5) : value.slice(11)) : value.slice(5, 10));
  const format = (value: number) => formatTrendValue(unit, value);
  return (
    <Box role="group" aria-label={ariaLabel}>
      <LineChart
        h={180}
        data={rows}
        dataKey="label"
        series={series}
        curveType="linear"
        withDots={false}
        connectNulls={false}
        withLegend={series.length >= 2}
        legendProps={{ verticalAlign: "bottom", height: 24 }}
        tickLine="y"
        xAxisProps={{ minTickGap: 40, tickFormatter: tick }}
        yAxisProps={{ width: 64 }}
        valueFormatter={format}
        tooltipProps={{
          content: ({ label, payload }) => (
            <ChartTooltip label={`${String(label)} UTC`} payload={payload ?? []} series={series} valueFormatter={format} />
          ),
        }}
      />
    </Box>
  );
}
