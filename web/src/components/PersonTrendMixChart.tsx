import "@mantine/charts/styles.css";
import { AreaChart, ChartTooltip } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CATEGORY_PALETTE } from "../utils/chartColors";
import { formatPercent } from "../utils/reportFormat";

type MixRow = Record<string, string | number | null>;

/**
 * The person's work mix as a stacked area of SHARES: each window's delivered man-days split over the keys (activity types or work categories),
 * always summing to 100% — the man-days themselves are in the table beside it. The bands wear the categorical palette by position, biggest
 * first at the bottom; identity never rests on colour (the legend, the tooltip and the table name every band). A window that delivered nothing
 * or has nothing derived has `null` shares, so the area breaks there. A lazy chunk: recharts never enters the main bundle.
 */
export default function PersonTrendMixChart({
  rows,
  names,
  label,
  axisLabel,
  partialAt,
}: {
  /** One row per window: `label` (the window's end) and `s0`, `s1`… the shares (0..1) or null. */
  rows: readonly MixRow[];
  /** Each series' name, in order (series `s<i>` is `names[i]`). */
  names: readonly string[];
  label: string;
  axisLabel: string;
  /** Which windows only partly lie in the derived span (the tooltip says "partial"). */
  partialAt: readonly boolean[];
}) {
  const { t } = useTranslation();
  const series = names.map((name, index) => ({ name: `s${index}`, label: name, color: CATEGORY_PALETTE[index % CATEGORY_PALETTE.length] }));
  return (
    <Box role="group" aria-label={label}>
      <AreaChart
        h={260}
        data={rows as MixRow[]}
        dataKey="label"
        series={series}
        type="stacked"
        curveType="linear"
        connectNulls={false}
        withDots={false}
        fillOpacity={0.7}
        withLegend
        legendProps={{ verticalAlign: "top", height: 36 }}
        yAxisLabel={axisLabel}
        yAxisProps={{ domain: [0, 1] }}
        valueFormatter={formatPercent}
        tickLine="y"
        xAxisProps={{ minTickGap: 24, ...(rows.length > 8 ? { angle: -30, textAnchor: "end", height: 70 } : {}) }}
        tooltipProps={{
          content: ({ label: end, payload }) => {
            const index = rows.findIndex((row) => row.label === end);
            return (
              <ChartTooltip
                label={partialAt[index] === true ? `${String(end)} · ${t("reports.personTrend.coverage.PARTIAL")}` : end}
                payload={payload ?? []}
                series={series}
                valueFormatter={formatPercent}
              />
            );
          },
        }}
      />
    </Box>
  );
}
