import "@mantine/charts/styles.css";
import { LineChart } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CHART_COLORS } from "../utils/chartColors";
import type { EvmRow } from "../utils/epicProgressReport";
import { formatMd } from "../utils/reportFormat";

/**
 * The cumulative curves in man-days — planned value (blue), earned value (teal), actual cost
 * (gray) — one calendar day per point, ISO dates on the x axis. At EPIC level the epic's FIRST
 * baseline is drawn too (`pvOriginal`, the same plan blue but DASHED: the legend swatches are
 * hue-only, so the dash, the tooltip and the table beside the chart carry identity); it is where
 * a re-plan shows as a gap. A day the original plan has no curve for is a null and `connectNulls`
 * is off — a gap, never a zero. A lazy chunk: recharts never enters the main bundle.
 */
export default function EpicProgressChart({ rows, withOriginal }: { rows: EvmRow[]; withOriginal: boolean }) {
  const { t } = useTranslation();
  const series = [
    { name: "pv", label: t("reports.epicProgress.series.pv"), color: CHART_COLORS.evmPlanned },
    ...(withOriginal
      ? [
          {
            name: "pvOriginal",
            label: t("reports.epicProgress.series.pvOriginal"),
            color: CHART_COLORS.evmOriginalPlan,
            strokeDasharray: "6 4",
          },
        ]
      : []),
    { name: "ev", label: t("reports.epicProgress.series.ev"), color: CHART_COLORS.evmEarned },
    { name: "ac", label: t("reports.epicProgress.series.ac"), color: CHART_COLORS.evmActual },
  ];
  return (
    <Box role="group" aria-label={t("reports.epicProgress.chartLabel")}>
      <LineChart
        h={300}
        data={rows}
        dataKey="date"
        series={series}
        curveType="linear"
        connectNulls={false}
        withDots={false}
        withLegend
        legendProps={{ verticalAlign: "top", height: 36 }}
        yAxisLabel={t("reports.unitMd")}
        valueFormatter={formatMd}
        tickLine="y"
        xAxisProps={{ minTickGap: 48 }}
      />
    </Box>
  );
}
