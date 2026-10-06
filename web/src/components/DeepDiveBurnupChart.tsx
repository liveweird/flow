import "@mantine/charts/styles.css";
import { LineChart } from "@mantine/charts";
import { Box, useComputedColorScheme } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { AS_OF_COLOR, CHART_COLORS } from "../utils/chartColors";
import { formatFigure } from "../utils/reportFormat";

export interface BurnupChartRow {
  date: string;
  pv: number;
  ev: number | null;
  ac: number | null;
  /** Present only while the budget line is switched on. */
  budget?: number | null;
}

/**
 * The Deep dive burn-up: cumulative plan (PV, blue), earned value (EV, teal) and cost (AC, gray) in man-days, one point
 * per day with ISO dates on ONE axis, plus the epics' budget plan as a dashed plan-blue line when asked for (the swatches
 * are hue-only, so the dash, the tooltip and the table beside the chart carry identity). EV and AC are `null` after the
 * last day they are current for and `connectNulls` is off: the lines end there instead of running flat. `asOfDate`
 * draws the labelled vertical marker where the data ends. A lazy chunk: recharts never enters the main bundle.
 */
export default function DeepDiveBurnupChart({ rows, withBudget, asOfDate }: { rows: BurnupChartRow[]; withBudget: boolean; asOfDate: string | null }) {
  const { t } = useTranslation();
  const scheme = useComputedColorScheme("light");
  const series = [
    { name: "pv", label: t("reports.deepDive.matrix.layer.pv"), color: CHART_COLORS.deepDivePlan },
    { name: "ev", label: t("reports.deepDive.matrix.layer.ev"), color: CHART_COLORS.deepDiveExecution },
    { name: "ac", label: t("reports.deepDive.matrix.layer.cost"), color: CHART_COLORS.deepDiveCost },
    ...(withBudget
      ? [{ name: "budget", label: t("reports.deepDive.burnup.series.budget"), color: CHART_COLORS.deepDiveBurnupBudget, strokeDasharray: "6 4" }]
      : []),
  ];
  // The label hangs off the line on the side with room: in the right half it ends at the line (insideBottomRight), else it starts there.
  const asOfIndex = rows.findIndex((row) => row.date === asOfDate);
  const referenceLines =
    asOfDate === null
      ? []
      : [
          {
            x: asOfDate,
            color: AS_OF_COLOR[scheme],
            strokeDasharray: "2 4",
            label: t("reports.deepDive.burnup.asOf", { day: asOfDate }),
            labelPosition: asOfIndex * 2 >= rows.length ? ("insideBottomRight" as const) : ("insideBottomLeft" as const),
          },
        ];
  return (
    <Box role="group" aria-label={t("reports.deepDive.burnup.chartLabel")}>
      <LineChart
        h={320}
        data={rows}
        dataKey="date"
        series={series}
        referenceLines={referenceLines}
        curveType="linear"
        connectNulls={false}
        withDots={false}
        withLegend
        legendProps={{ verticalAlign: "top", height: 36 }}
        yAxisLabel={t("reports.unitMd")}
        valueFormatter={formatFigure}
        tickLine="y"
        xAxisProps={{ minTickGap: 48 }}
      />
    </Box>
  );
}
