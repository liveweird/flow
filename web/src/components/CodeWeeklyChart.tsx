import "@mantine/charts/styles.css";
import { BarChart } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { CodeActivityReport } from "../api/reports";
import { CHART_COLORS } from "../utils/chartColors";

/**
 * The merge requests merged in each Monday-start week of the period (teal, the delivered colour), zero weeks included so the cadence reads;
 * ONE series, so no legend (the card title names it). The numbers table beside it is the page's. A lazy chunk: recharts never enters the
 * main bundle.
 */
export default function CodeWeeklyChart({ weeks }: { weeks: CodeActivityReport["flow"]["weekly"] }) {
  const { t } = useTranslation();
  const data = weeks.map((week) => ({ week: week.bucketStart, merged: week.merged }));
  const series = [{ name: "merged", label: t("reports.codeActivity.weekly.series"), color: CHART_COLORS.delivered }];
  return (
    <Box role="group" aria-label={t("reports.codeActivity.weekly.chartLabel")}>
      <BarChart
        h={240}
        data={data}
        dataKey="week"
        series={series}
        tickLine="y"
        yAxisLabel={t("reports.codeActivity.weekly.yAxis")}
        xAxisProps={{ interval: "preserveStartEnd", minTickGap: 24 }}
        barProps={{ maxBarSize: 28 }}
      />
    </Box>
  );
}
