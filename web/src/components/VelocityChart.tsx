import "@mantine/charts/styles.css";
import { BarChart } from "@mantine/charts";
import { Box, useComputedColorScheme } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CHART_COLORS, FINAL_COLOR } from "../utils/chartColors";
import { formatMd } from "../utils/reportFormat";
import type { VelocityChartRow } from "../utils/velocityReport";

// Blue = plan/committed: initial is the committed blue, final the per-scheme deeper/lighter step
// (utils/chartColors.ts has the contrast table). Known limit: two same-hue blues side by side are
// ≥ 3:1 against the surface but only ΔE ≈ 13 from each other — the legend, the tooltip and the
// table beside the chart carry the identity, and the sprint-consistency charts avoid the pair.
const INITIAL_COLOR = CHART_COLORS.committed;

/** Initial vs final scope per sprint — a lazy chunk: recharts never enters the main bundle. */
export default function VelocityChart({ rows }: { rows: VelocityChartRow[] }) {
  const { t } = useTranslation();
  const scheme = useComputedColorScheme("light");
  const crowded = rows.length > 6;
  return (
    <Box role="group" aria-label={t("reports.velocity.chartLabel")}>
      <BarChart
        h={320}
        data={rows}
        dataKey="label"
        series={[
          { name: "initialMd", label: t("reports.velocity.series.initial"), color: INITIAL_COLOR },
          { name: "finalMd", label: t("reports.velocity.series.final"), color: FINAL_COLOR[scheme] },
        ]}
        withLegend
        legendProps={{ verticalAlign: "top", height: 36 }}
        yAxisLabel={t("reports.unitMd")}
        valueFormatter={formatMd}
        tickLine="y"
        barProps={{ maxBarSize: 28 }}
        xAxisProps={crowded ? { interval: 0, angle: -30, textAnchor: "end", height: 80 } : undefined}
      />
    </Box>
  );
}
