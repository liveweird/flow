import "@mantine/charts/styles.css";
import { BarChart } from "@mantine/charts";
import { Box, useComputedColorScheme } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { AS_OF_COLOR, CHART_COLORS } from "../utils/chartColors";
import { deltaAxisMax, formatDeltaPoints, type DeltaBar } from "../utils/costsCategories";

const CATEGORY_AXIS_WIDTH = 140;

/**
 * Each work category's actual share minus its target, in percentage points, as a diverging horizontal bar: ONE series in ONE neutral
 * hue (the cost gray — red means blocking in this app and the sign is no verdict), the side of a centred zero line telling over from
 * under, so the colour carries nothing. One series, so no legend; the signed figure is in the tooltip and the table beside the chart
 * carries every number. A lazy chunk: recharts never enters the main bundle.
 */
export default function CostsTargetsChart({ bars }: { bars: readonly DeltaBar[] }) {
  const { t } = useTranslation();
  const scheme = useComputedColorScheme("light");
  const max = deltaAxisMax(bars);
  return (
    <Box role="group" aria-label={t("reports.costs.categories.targets.chartLabel")}>
      <BarChart
        h={Math.max(120, bars.length * 34 + 64)}
        data={bars.map((bar) => ({ label: bar.label, delta: bar.delta }))}
        dataKey="label"
        orientation="vertical"
        series={[{ name: "delta", label: t("reports.costs.categories.targets.series"), color: CHART_COLORS.categoryDelta }]}
        valueFormatter={formatDeltaPoints}
        tickLine="x"
        gridAxis="x"
        referenceLines={[{ x: 0, color: AS_OF_COLOR[scheme], strokeDasharray: "2 4" }]}
        xAxisLabel={t("reports.costs.categories.targets.axis")}
        xAxisProps={{ domain: [-max, max], allowDecimals: false }}
        yAxisProps={{ width: CATEGORY_AXIS_WIDTH }}
        barProps={{ maxBarSize: 18 }}
      />
    </Box>
  );
}
