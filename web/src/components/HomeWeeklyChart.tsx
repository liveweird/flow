import "@mantine/charts/styles.css";
import { BarChart, ChartTooltip } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { HomeTeam } from "../api/home";
import { CHART_COLORS } from "../utils/chartColors";
import { barDoubleClickProps, type ChartBarHit } from "../utils/chartDrill";
import { formatMd } from "../utils/reportFormat";

/**
 * One team's weekly delivery over the window: the man-days of the tasks it finished in each Monday-start week (teal, the delivered
 * colour), zero weeks included so the cadence reads. The tooltip names the full week and its item count; a double-click on a bar opens
 * that week's tasks (the Throughput drill). A lazy chunk: recharts never enters the main bundle.
 */
export default function HomeWeeklyChart({ team, onBarDoubleClick }: { team: HomeTeam; onBarDoubleClick?: (hit: ChartBarHit) => void }) {
  const { t } = useTranslation();
  const weeks = team.throughput.weeks;
  const data = weeks.map((week) => ({ id: week.bucketStart, week: week.bucketStart.slice(5), deliveredMd: week.deliveredMd }));
  const series = [{ name: "deliveredMd", label: t("home.throughput.series"), color: CHART_COLORS.delivered }];
  return (
    <Box
      role="group"
      aria-label={t("home.throughput.chartLabel", { team: team.name })}
      style={onBarDoubleClick ? { userSelect: "none" } : undefined}
    >
      <BarChart
        h={170}
        data={data}
        dataKey="week"
        series={series}
        valueFormatter={formatMd}
        tickLine="y"
        yAxisLabel={t("reports.unitMd")}
        xAxisProps={{ interval: "preserveStartEnd", minTickGap: 12 }}
        barProps={(bar) => ({ maxBarSize: 18, ...barDoubleClickProps(bar.name, onBarDoubleClick) })}
        tooltipProps={{
          content: ({ payload }) => {
            const week = weeks.find((candidate) => candidate.bucketStart === (payload?.[0]?.payload as { id?: string } | undefined)?.id);
            if (!week) return null;
            return (
              <ChartTooltip
                label={`${t("home.throughput.weekOf", { date: week.bucketStart })} · ${t("home.count.tasks", { count: week.deliveredItems })}`}
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
