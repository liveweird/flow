import "@mantine/charts/styles.css";
import { ChartTooltip, LineChart } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CHART_COLORS } from "../utils/chartColors";
import { dotClickProps } from "../utils/chartDrill";
import { cssColor } from "../utils/homeTrend";
import { hasReference, isPartial, pointTitle, referenceNote, type TrendChartPoint } from "../utils/personTrendReport";
import type { ValueFormat } from "../utils/reportFormat";
import { REFERENCE_LINE_PROPS, REFERENCE_SERIES, referenceSeries } from "../utils/trendReference";
import { partialDot } from "./PartialDot";
import TrendTooltipLabel from "./TrendTooltipLabel";

/**
 * One row of Person trend: the person's figure at the end of each 90-day window, one line. A hidden figure, a figure with no denominator and a
 * window with nothing derived are `null` and `connectNulls` is off, so the line BREAKS there — a gap, never a zero. A window that only partly
 * lies in the derived span is a HOLLOW dot (`PartialDot`), and the tooltip says "partial" beside the sample. One series, so no legend: the card
 * title names it, unless a team reference is drawn beside it: the team median, a quiet DOTTED gray line with no dots (`referenceSeries`), whose legend
 * entry then names both; a point with too few peers (or no team) is a gap in it, and the tooltip names the team of the hovered point (a mover changes team). `onPointClick` (a pointer's route to the items behind a point; the numbers table is the keyboard's) is given the window end of a
 * clicked dot — a gap has no dot, and the guard below ignores one all the same. A lazy chunk: recharts never enters the main bundle.
 */
export default function PersonTrendChart({
  points,
  seriesLabel,
  axisLabel,
  format,
  minSampleSize,
  domain,
  onPointClick,
}: {
  points: readonly TrendChartPoint[];
  /** The tooltip's name for the line (the row's label). */
  seriesLabel: string;
  axisLabel: string;
  format: ValueFormat;
  minSampleSize: number;
  /** A fixed y range (a share is 0..1, so an all-zero line does not stretch the axis to 400% and a ratio never overshoots 100%); absent = the library's own. */
  domain?: readonly [number, number];
  /** Opens the items behind the clicked point (the window's last day). */
  onPointClick?: (end: string) => void;
}) {
  const { t } = useTranslation();
  const withReference = hasReference(points);
  const person = { name: "value", label: seriesLabel, color: CHART_COLORS.trendMedian };
  const series = withReference ? [person, referenceSeries(t("reports.personTrend.reference.series"))] : [person];
  const rows = points.map((point) => ({ label: point.label, value: point.value, ...(withReference && { [REFERENCE_SERIES]: point.reference?.value ?? null }) }));
  const hit = (end: string) => {
    if (points.find((point) => point.label === end)?.value != null) onPointClick?.(end);
  };
  const dotColor = cssColor(CHART_COLORS.trendMedian);
  const personLineProps = {
    dot: partialDot(CHART_COLORS.trendMedian, points.map(isPartial), onPointClick && ((index) => hit(points[index].label))),
    ...(onPointClick && { activeDot: { fill: dotColor, stroke: dotColor, ...dotClickProps("value", ({ rowId }) => hit(rowId)) } }),
  };
  return (
    <Box role="group" aria-label={seriesLabel}>
      <LineChart
        h={240}
        data={rows}
        dataKey="label"
        series={series}
        curveType="linear"
        connectNulls={false}
        withDots
        withLegend={withReference}
        legendProps={{ verticalAlign: "top", height: 36 }}
        lineProps={(item: { name: string }) => (item.name === REFERENCE_SERIES ? REFERENCE_LINE_PROPS : personLineProps)}
        yAxisLabel={axisLabel}
        valueFormatter={format}
        tickLine="y"
        yAxisProps={domain === undefined ? undefined : { domain: [...domain] }}
        xAxisProps={{ minTickGap: 24, ...(points.length > 8 ? { angle: -30, textAnchor: "end", height: 70 } : {}) }}
        tooltipProps={{
          content: ({ label, payload }) => {
            // The point comes from the HOVERED LABEL, not the payload: a gap has no plottable value, so recharts may hand over an empty payload for it.
            const point = points.find((candidate) => candidate.label === label);
            const title = point === undefined ? label : pointTitle(point, minSampleSize, t);
            const note = point === undefined ? undefined : referenceNote(point, format, t);
            return (
              <ChartTooltip
                label={note === undefined ? title : <TrendTooltipLabel title={title} note={note} />}
                // The reference is told in the label (its value, team and peers), not as a second row.
                payload={(payload ?? []).filter((entry) => entry.name !== REFERENCE_SERIES)}
                series={series}
                valueFormatter={format}
              />
            );
          },
        }}
      />
    </Box>
  );
}
