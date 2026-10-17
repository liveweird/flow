import "@mantine/charts/styles.css";
import { BarChart } from "@mantine/charts";
import { Box, useComputedColorScheme } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CHART_COLORS, FINAL_COLOR } from "../utils/chartColors";
import { barDoubleClickProps, type ChartBarHit } from "../utils/chartDrill";
import type { OverviewTeamRow } from "../utils/homeOverview";
import { formatMd } from "../utils/reportFormat";

/**
 * Each team's last closed sprint: initial (committed, blue) and final scope (the per-scheme deeper
 * blue) beside what it delivered (teal) — the same series colours and the same known
 * initial/final same-hue limit as the Commitments chart (`utils/chartColors.ts`); the table beside the
 * chart carries every number. A lazy chunk: recharts never enters the main bundle.
 */
export default function HomeCommitmentsChart({
  rows,
  onBarDoubleClick,
}: {
  rows: OverviewTeamRow[];
  /** A double-click on a bar: the row's `id` and the series (`initialMd`, `finalMd` or `deliveredMd`). */
  onBarDoubleClick?: (hit: ChartBarHit) => void;
}) {
  const { t } = useTranslation();
  const scheme = useComputedColorScheme("light");
  const crowded = rows.length > 5;
  return (
    <Box
      role="group"
      aria-label={t("home.commitments.chartLabel")}
      // A double-click on a drillable bar must not select the axis labels around it.
      style={onBarDoubleClick ? { userSelect: "none" } : undefined}
    >
      <BarChart
        h={260}
        data={rows}
        dataKey="team"
        series={[
          { name: "initialMd", label: t("home.commitments.series.initial"), color: CHART_COLORS.committed },
          { name: "finalMd", label: t("home.commitments.series.final"), color: FINAL_COLOR[scheme] },
          { name: "deliveredMd", label: t("home.commitments.series.delivered"), color: CHART_COLORS.delivered },
        ]}
        withLegend
        legendProps={{ verticalAlign: "top", height: 36 }}
        yAxisLabel={t("reports.unitMd")}
        valueFormatter={formatMd}
        tickLine="y"
        barProps={(bar) => ({ maxBarSize: 24, ...barDoubleClickProps(bar.name, onBarDoubleClick) })}
        xAxisProps={crowded ? { interval: 0, angle: -30, textAnchor: "end", height: 70 } : undefined}
      />
    </Box>
  );
}
