import "@mantine/charts/styles.css";
import { ChartTooltip, LineChart } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { HomeTeam } from "../api/home";
import { CHART_COLORS } from "../utils/chartColors";
import { dotDoubleClickProps, type ChartBarHit } from "../utils/chartDrill";
import { cssColor } from "../utils/homeTrend";
import { PRED_SHARES, predSeries } from "../utils/homePred";
import { formatPercent } from "../utils/reportFormat";

/**
 * One team's PRED curve (the share of items whose actual landed within ±10 / 25 / 50 % of the estimate at start): the tasks as a
 * solid blue line, the epics as a DASHED gray one — the cycle-time trend's exact pairing, hue alone would not tell them apart. A line
 * is missing (a gap, never a zero) where the sample is under the minimum. The tooltip names how many items are within each share;
 * the table beside carries every number and a double-click on a point opens its items (the table's figures are the keyboard route).
 * A lazy chunk: recharts never enters the main bundle.
 */
export default function HomePredChart({
  team,
  onDotDoubleClick,
}: {
  team: HomeTeam;
  /** A double-click on a point: the share (`10`, `25` or `50`) as the row id and the line (`tasks` or `epics`). */
  onDotDoubleClick?: (hit: ChartBarHit) => void;
}) {
  const { t } = useTranslation();
  const data = PRED_SHARES.map((share) => ({
    id: String(share),
    share: t("home.accuracy.share", { percent: share }),
    tasks: predSeries(team, "tasks", share).value,
    epics: predSeries(team, "epics", share).value,
  }));
  const series = [
    { name: "tasks", label: t("home.accuracy.series.tasks"), color: CHART_COLORS.trendMedian },
    { name: "epics", label: t("home.accuracy.series.epics"), color: CHART_COLORS.trendTail, strokeDasharray: "6 4" },
  ];
  // A line's dots are redrawn here only to make them double-clickable: the handler closes over the line (no `dataKey` to trust).
  const lineProps = (line: { name: string; color?: string }) =>
    onDotDoubleClick
      ? {
          dot: { r: 3, strokeWidth: 1, fill: cssColor(line.color ?? "gray.6"), stroke: cssColor(line.color ?? "gray.6"), ...dotDoubleClickProps(line.name, onDotDoubleClick) },
          activeDot: { r: 5, fill: cssColor(line.color ?? "gray.6"), stroke: cssColor(line.color ?? "gray.6"), ...dotDoubleClickProps(line.name, onDotDoubleClick) },
        }
      : {};
  return (
    <Box
      role="group"
      aria-label={t("home.accuracy.chartLabel", { team: team.name })}
      style={onDotDoubleClick ? { userSelect: "none" } : undefined}
    >
      <LineChart
        h={200}
        data={data}
        dataKey="share"
        series={series}
        curveType="linear"
        connectNulls={false}
        withDots
        withLegend
        legendProps={{ verticalAlign: "top", height: 36 }}
        yAxisProps={{ domain: [0, 1] }}
        valueFormatter={formatPercent}
        tickLine="y"
        lineProps={lineProps}
        tooltipProps={{
          content: ({ label, payload }) => {
            const share = PRED_SHARES.find((candidate) => t("home.accuracy.share", { percent: candidate }) === label);
            const detailed = series.map((line) => {
              const figure = share === undefined ? undefined : predSeries(team, line.name === "tasks" ? "tasks" : "epics", share);
              const within = figure ? ` (${figure.count} / ${figure.n})` : "";
              return { ...line, label: `${line.label}${within}` };
            });
            return <ChartTooltip label={label} payload={(payload ?? []).filter((item) => item.value != null)} series={detailed} valueFormatter={formatPercent} />;
          },
        }}
      />
    </Box>
  );
}
