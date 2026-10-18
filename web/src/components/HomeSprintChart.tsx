import "@mantine/charts/styles.css";
import { BarChart, ChartTooltip } from "@mantine/charts";
import { Box, useComputedColorScheme } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CHART_COLORS, FINAL_COLOR } from "../utils/chartColors";
import { barDoubleClickProps, type ChartBarHit } from "../utils/chartDrill";
import { formatDate } from "../utils/formatDate";
import type { HomeSprintRow } from "../utils/homeOverview";
import { formatMd } from "../utils/reportFormat";

/**
 * Each team's last closed sprint: its capacity (a neutral gray reference bar, no items behind it) beside the initial (committed,
 * blue) and final scope (the per-scheme deeper blue) and what it delivered (teal) — the same series colours and the same known
 * initial/final same-hue limit as the Commitments chart (`utils/chartColors.ts`); the tooltip names the sprint and its closing day
 * and the chart's table carries every number. A lazy chunk: recharts never enters the main bundle.
 */
export default function HomeSprintChart({
  rows,
  timeZone,
  onBarDoubleClick,
}: {
  rows: HomeSprintRow[];
  timeZone: string;
  /** A double-click on a bar: the row's `id` and the series (`initialMd`, `finalMd` or `deliveredMd`; the capacity bar has no items). */
  onBarDoubleClick?: (hit: ChartBarHit) => void;
}) {
  const { t } = useTranslation();
  const scheme = useComputedColorScheme("light");
  const crowded = rows.length > 5;
  const series = [
    { name: "capacityMd", label: t("home.sprints.series.capacity"), color: CHART_COLORS.capacity },
    { name: "initialMd", label: t("home.sprints.series.initial"), color: CHART_COLORS.committed },
    { name: "finalMd", label: t("home.sprints.series.final"), color: FINAL_COLOR[scheme] },
    { name: "deliveredMd", label: t("home.sprints.series.delivered"), color: CHART_COLORS.delivered },
  ];
  return (
    <Box
      role="group"
      aria-label={t("home.sprints.chartLabel")}
      // A double-click on a drillable bar must not select the axis labels around it.
      style={onBarDoubleClick ? { userSelect: "none" } : undefined}
    >
      <BarChart
        h={260}
        data={rows}
        dataKey="team"
        series={series}
        withLegend
        legendProps={{ verticalAlign: "top", height: 36 }}
        yAxisLabel={t("reports.unitMd")}
        valueFormatter={formatMd}
        tickLine="y"
        barProps={(bar) => ({
          maxBarSize: 24,
          ...(bar.name === "capacityMd" ? {} : barDoubleClickProps(bar.name, onBarDoubleClick)),
        })}
        xAxisProps={crowded ? { interval: 0, angle: -30, textAnchor: "end", height: 70 } : undefined}
        tooltipProps={{
          content: ({ label, payload }) => {
            const row = rows.find((candidate) => candidate.team === label);
            const closed = row ? ` · ${row.sprint} (${t("home.sprints.closedOn", { date: formatDate(row.completedAt, "—", timeZone) })})` : "";
            return (
              <ChartTooltip
                label={`${String(label)}${closed}`}
                payload={(payload ?? []).filter((item) => item.value != null)}
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
