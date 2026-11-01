import "@mantine/charts/styles.css";
import { BarChart } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { CodeActivityReport } from "../api/reports";
import { CHART_COLORS } from "../utils/chartColors";
import { barDoubleClickProps, type ChartBarHit } from "../utils/chartDrill";

/**
 * The merge requests merged in each Monday-start week of the period (teal, the delivered colour), zero weeks included so the cadence reads;
 * ONE series, so no legend (the card title names it). The numbers table beside it is the page's. A lazy chunk: recharts never enters the
 * main bundle. A double-click on a bar lists that week's merge requests (`onBarDoubleClick`, the drill).
 */
export default function CodeWeeklyChart({
  weeks,
  onBarDoubleClick,
}: {
  weeks: CodeActivityReport["flow"]["weekly"];
  onBarDoubleClick?: (hit: ChartBarHit) => void;
}) {
  const { t } = useTranslation();
  const data = weeks.map((week) => ({ id: week.bucketStart, week: week.bucketStart, merged: week.merged }));
  const series = [{ name: "merged", label: t("reports.codeActivity.weekly.series"), color: CHART_COLORS.delivered }];
  return (
    <Box role="group" aria-label={t("reports.codeActivity.weekly.chartLabel")} style={onBarDoubleClick ? { userSelect: "none" } : undefined}>
      <BarChart
        h={240}
        data={data}
        dataKey="week"
        series={series}
        tickLine="y"
        yAxisLabel={t("reports.codeActivity.weekly.yAxis")}
        xAxisProps={{ interval: "preserveStartEnd", minTickGap: 24 }}
        barProps={(bar) => ({ maxBarSize: 28, ...barDoubleClickProps(bar.name, onBarDoubleClick) })}
      />
    </Box>
  );
}
