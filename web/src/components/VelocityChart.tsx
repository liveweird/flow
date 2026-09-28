import "@mantine/charts/styles.css";
import { BarChart } from "@mantine/charts";
import { Box, useComputedColorScheme } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { formatMd } from "../utils/reportFormat";
import type { VelocityChartRow } from "../utils/velocityReport";

// The app's colour vocabulary: blue = plan/committed. Both steps clear WCAG 1.4.11 (≥ 3:1 for
// graphics) against every surface a chart sits on — white and the #f5f7fb canvas in the light
// scheme, the #2e2e2e paper and #1f1f1f canvas in the dark one:
//   initial  flow.6 (#228be6)  3.56 white · 3.32 canvas   | 3.82 paper · 4.63 canvas
//   final    flow.8 (#1971c2)  5.02 white · 4.68 canvas   (light scheme)
//            flow.4 (#4dabf7)  5.49 paper · 6.66 canvas   (dark scheme — flow.8 would be 2.70:1)
const INITIAL_COLOR = "flow.6";
const FINAL_COLOR = { light: "flow.8", dark: "flow.4" } as const;

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
