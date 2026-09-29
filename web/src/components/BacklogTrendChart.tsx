import "@mantine/charts/styles.css";
import { AreaChart, ChartTooltip } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { BacklogRow } from "../utils/backlogReport";
import { CHART_COLORS } from "../utils/chartColors";
import { formatMd } from "../utils/reportFormat";

/**
 * The estimated backlog in man-days at the end of each day — one series, so no legend (the card
 * title names it). The tooltip adds the item count, so a backlog of many small items is never
 * mistaken for a small one. A lazy chunk: recharts never enters the main bundle.
 */
export default function BacklogTrendChart({ rows }: { rows: BacklogRow[] }) {
  const { t } = useTranslation();
  const series = [{ name: "md", label: t("reports.backlog.series.backlog"), color: CHART_COLORS.backlog }];
  return (
    <Box role="group" aria-label={t("reports.backlog.chartLabel")}>
      <AreaChart
        h={280}
        data={rows}
        dataKey="day"
        series={series}
        curveType="linear"
        withDots={false}
        fillOpacity={0.3}
        yAxisLabel={t("reports.unitMd")}
        valueFormatter={formatMd}
        tickLine="y"
        xAxisProps={{ minTickGap: 48 }}
        tooltipProps={{
          content: ({ label, payload }) => {
            const row = payload?.[0]?.payload as BacklogRow | undefined;
            return (
              <ChartTooltip
                label={row ? `${String(label)} · ${t("reports.items", { count: row.items })}` : label}
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
