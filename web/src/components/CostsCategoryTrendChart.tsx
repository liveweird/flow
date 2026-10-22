import "@mantine/charts/styles.css";
import { LineChart } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { TrendSeries } from "../utils/costsCategories";
import { formatPercent } from "../utils/reportFormat";

const percent = (value: number) => formatPercent(value / 100);

/**
 * Each work category's cumulative share of everything logged, week by week: one line per category on ONE 0–100 % axis, in the colours
 * the targets table's swatches wear (a hue the palette already used is dashed, so form tells the repeat apart). The right end of the
 * lines is the period's whole mix. A week with nothing logged yet has no share — a gap, never a zero (`connectNulls` off). Two or more
 * lines get the legend; the tooltip names every category and the weekly table beside the chart carries every number. A lazy chunk: recharts never enters the main bundle.
 */
export default function CostsCategoryTrendChart({
  rows,
  series,
}: {
  rows: ReadonlyArray<Record<string, string | number | null>>;
  series: readonly TrendSeries[];
}) {
  const { t } = useTranslation();
  return (
    <Box role="group" aria-label={t("reports.costs.categories.trend.chartLabel")}>
      <LineChart
        h={300}
        data={[...rows]}
        dataKey="week"
        series={series.map((line) => ({
          name: line.name,
          label: line.label,
          color: line.color,
          ...(line.dashed ? { strokeDasharray: "6 4" } : {}),
        }))}
        curveType="linear"
        connectNulls={false}
        withDots={rows.length <= 40}
        withLegend={series.length >= 2}
        legendProps={{ verticalAlign: "top", height: 48 }}
        yAxisLabel={t("reports.costs.categories.trend.axis")}
        yAxisProps={{ domain: [0, 100], allowDecimals: false }}
        valueFormatter={percent}
        tickLine="y"
        xAxisProps={{ minTickGap: 48 }}
      />
    </Box>
  );
}
