import "@mantine/charts/styles.css";
import { BarChart } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CHART_COLORS } from "../utils/chartColors";
import { formatMd } from "../utils/reportFormat";
import type { SprintConsistencyChartRow } from "../utils/sprintConsistencyReport";

export type SprintConsistencyChartKind = "scope" | "partition" | "changes";

/**
 * The sprint-consistency compositions, one question per chart (a 14-series chart answers none):
 *  - `scope`     grouped bars, committed (blue) vs delivered (teal) — say/do;
 *  - `partition` stacked bars, final = carried over + delivered + dropped, so the bar's height IS the
 *                final scope. Order bottom to top puts teal between orange and red: adjacent
 *                segments then always differ in hue (orange ↔ red are only ΔE ≈ 6 apart) and the
 *                2px surface stroke separates them further;
 *  - `changes`   grouped bars, added (orange) vs removed (gray).
 * Colours are the app vocabulary (`utils/chartColors.ts`, every ratio pinned by a test). A lazy
 * chunk: recharts never enters the main bundle.
 */
export default function SprintConsistencyChart({
  kind,
  rows,
}: {
  kind: SprintConsistencyChartKind;
  rows: SprintConsistencyChartRow[];
}) {
  const { t } = useTranslation();
  const series = {
    scope: [
      { name: "committedMd", label: t("reports.sprintConsistency.series.committed"), color: CHART_COLORS.committed },
      { name: "deliveredMd", label: t("reports.sprintConsistency.series.delivered"), color: CHART_COLORS.delivered },
    ],
    partition: [
      { name: "carriedOverMd", label: t("reports.sprintConsistency.series.carriedOver"), color: CHART_COLORS.carriedOver },
      { name: "deliveredMd", label: t("reports.sprintConsistency.series.delivered"), color: CHART_COLORS.delivered },
      { name: "droppedMd", label: t("reports.sprintConsistency.series.dropped"), color: CHART_COLORS.dropped },
    ],
    changes: [
      { name: "addedMd", label: t("reports.sprintConsistency.series.added"), color: CHART_COLORS.added },
      { name: "removedMd", label: t("reports.sprintConsistency.series.removed"), color: CHART_COLORS.removed },
    ],
  }[kind];
  const stacked = kind === "partition";
  return (
    <Box role="group" aria-label={t(`reports.sprintConsistency.${kind}Label`)}>
      <BarChart
        h={300}
        data={rows}
        dataKey="label"
        type={stacked ? "stacked" : "default"}
        series={series}
        withLegend
        legendProps={{ verticalAlign: "top", height: 36 }}
        yAxisLabel={t("reports.unitMd")}
        valueFormatter={formatMd}
        tickLine="y"
        // The stroke is the card's own background (Paper = --mantine-color-body, white / dark paper), so
        // it reads as a 2px gap between segments in both schemes.
        barProps={stacked ? { stroke: "var(--mantine-color-body)", strokeWidth: 2 } : { maxBarSize: 28 }}
        xAxisProps={rows.length > 6 ? { interval: 0, angle: -30, textAnchor: "end", height: 80 } : undefined}
      />
    </Box>
  );
}
