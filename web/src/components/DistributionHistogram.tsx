import "@mantine/charts/styles.css";
import { BarChart } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CHART_COLORS } from "../utils/chartColors";

export interface HistogramRow {
  /** The bucket's range, already formatted ("0.50–0.75"). */
  label: string;
  count: number;
}

/**
 * A distribution's histogram: item count per equal-width range. One series, so no legend; blue
 * (the estimate/plan side of the vocabulary). A lazy chunk — recharts never enters the main bundle.
 */
export default function DistributionHistogram({
  rows,
  name,
  xAxisLabel,
}: {
  rows: HistogramRow[];
  name: string;
  xAxisLabel: string;
}) {
  const { t } = useTranslation();
  return (
    <Box role="group" aria-label={`${t("reports.distribution.histogramLabel")} — ${name}`}>
      <BarChart
        h={220}
        data={rows}
        dataKey="label"
        series={[{ name: "count", label: t("reports.distribution.count"), color: CHART_COLORS.estimate }]}
        xAxisLabel={xAxisLabel}
        yAxisLabel={t("reports.distribution.yAxis")}
        tickLine="y"
        barProps={{ maxBarSize: 40 }}
        xAxisProps={rows.length > 6 ? { interval: 0, angle: -30, textAnchor: "end", height: 70 } : undefined}
      />
    </Box>
  );
}
