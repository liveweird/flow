import "@mantine/charts/styles.css";
import { BarChart, ChartTooltip } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CHART_COLORS } from "../utils/chartColors";
import { formatMd } from "../utils/reportFormat";
import type { ThroughputChartRow } from "../utils/throughputReport";

/**
 * Delivered MD per week/month — teal = delivered, one series, so no legend (the card title names
 * it). A lazy chunk: recharts never enters the main bundle. The tooltip adds the item count, so
 * an unestimated task (an item worth 0 MD) is never invisible.
 */
export default function ThroughputChart({ rows }: { rows: ThroughputChartRow[] }) {
  const { t } = useTranslation();
  const series = [{ name: "deliveredMd", label: t("reports.throughput.series.delivered"), color: CHART_COLORS.delivered }];
  return (
    <Box role="group" aria-label={t("reports.throughput.chartLabel")}>
      <BarChart
        h={300}
        data={rows}
        dataKey="label"
        series={series}
        yAxisLabel={t("reports.unitMd")}
        valueFormatter={formatMd}
        tickLine="y"
        barProps={{ maxBarSize: 28 }}
        xAxisProps={rows.length > 8 ? { angle: -30, textAnchor: "end", height: 70 } : undefined}
        tooltipProps={{
          content: ({ label, payload }) => {
            const row = payload?.[0]?.payload as ThroughputChartRow | undefined;
            return (
              <ChartTooltip
                label={row ? `${String(label)} · ${t("reports.items", { count: row.deliveredItems })}` : label}
                payload={payload ?? []}
                series={series}
                valueFormatter={formatMd}
              />
            );
          },
        }}
      />
    </Box>
  );
}
