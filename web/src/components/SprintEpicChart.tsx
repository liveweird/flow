import "@mantine/charts/styles.css";
import { BarChart, ChartTooltip } from "@mantine/charts";
import { Box, useComputedColorScheme } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { DEEP_DIVE_FILL_ALPHA } from "../utils/chartColors";
import { barDoubleClickProps, type ChartBarHit } from "../utils/chartDrill";
import { epicSegmentColour, epicSegmentName, type EpicChartSegment } from "../utils/epicSegments";
import { formatMd } from "../utils/reportFormat";
import { epicDataKey, type EpicChartRow, type EpicStack } from "../utils/sprintEpics";
import ChartLegendList, { PersonSwatch, TreatmentGlyph } from "./ChartLegendList";

const MAX_BAR = 28;

/** A stack of a by-epic chart and how it is told apart: Commitments' initial stack is outlined (translucent), its final one solid. */
export interface EpicChartStack extends EpicStack {
  /** `outlined` = a translucent fill inside a solid 2 px edge (the Deep dive's idiom); `solid` = ringed in the surface. */
  treatment: "outlined" | "solid";
  /** The series' words for a segment's name ("FLO-12 Checkout · initial"): the tooltip's row. */
  label: (segmentName: string) => string;
  /** The legend entry that names the stack's treatment; only a chart with two or more stacks has them. */
  legend?: string;
}

export interface SprintEpicChartProps {
  /** The segments in stack order; the rows carry each one's MD under `epicDataKey(stack.id, segment.position)`. */
  segments: EpicChartSegment[];
  stacks: readonly EpicChartStack[];
  rows: EpicChartRow[];
  /** The chart group's accessible name. */
  ariaLabel: string;
  legendLabel: string;
  /** A double-click on a segment: the sprint row's `id` and the segment's data key. */
  onBarDoubleClick?: (hit: ChartBarHit) => void;
}

/**
 * A sprint chart stacked by epic — Commitments' initial and final scope (two stacks per sprint), Throughput's delivered work (one). A
 * lazy chunk: recharts never enters the main bundle. The segments are arbitrary names, so they wear no semantic hue: the top epics the
 * categorical palette in stack order (an epic keeps its colour in every sprint), "Other epics" and "No epic" two neutral grays
 * (`epicSegmentColour`). Identity never rests on colour: the page's own legend names every segment, the tooltip lists the segments
 * of the hovered stack by name and the table beside the chart carries every number. Stacked segments are ringed in the surface, so they
 * never touch.
 */
export default function SprintEpicChart({ segments, stacks, rows, ariaLabel, legendLabel, onBarDoubleClick }: SprintEpicChartProps) {
  const { t } = useTranslation();
  const scheme = useComputedColorScheme("light");
  const colour = (segment: EpicChartSegment) => epicSegmentColour(segment, scheme);
  const series = stacks.flatMap((stack) =>
    segments.map((segment) => ({
      name: epicDataKey(stack.id, segment.position),
      label: stack.label(epicSegmentName(t, segment)),
      color: colour(segment),
      stackId: stack.id,
    })),
  );
  const treatmentOf = (stackId: string | undefined) => stacks.find((stack) => stack.id === stackId)?.treatment;
  return (
    <Box
      role="group"
      aria-label={ariaLabel}
      // A double-click on a drillable bar must not select the axis labels around it.
      style={onBarDoubleClick ? { userSelect: "none" } : undefined}
    >
      <ChartLegendList
        label={legendLabel}
        entries={[
          ...segments.map((segment) => ({
            key: segment.key,
            glyph: <PersonSwatch colour={colour(segment)} />,
            text: epicSegmentName(t, segment),
          })),
          ...stacks.flatMap((stack) =>
            stack.legend === undefined
              ? []
              : [{ key: `stack-${stack.id}`, glyph: <TreatmentGlyph solid={stack.treatment === "solid"} />, text: stack.legend }],
          ),
        ]}
      />
      <BarChart
        h={320}
        data={rows}
        dataKey="label"
        series={series}
        yAxisLabel={t("reports.unitMd")}
        valueFormatter={formatMd}
        tickLine="y"
        barProps={(item) => ({
          ...(treatmentOf(item.stackId) === "outlined"
            ? { maxBarSize: MAX_BAR, fillOpacity: DEEP_DIVE_FILL_ALPHA, strokeOpacity: 1, strokeWidth: 2 }
            : { maxBarSize: MAX_BAR, stroke: "var(--mantine-color-body)", strokeOpacity: 1, strokeWidth: 2 }),
          ...barDoubleClickProps(item.name, onBarDoubleClick),
        })}
        xAxisProps={rows.length > 6 ? { interval: 0, angle: -30, textAnchor: "end" as const, height: 80 } : undefined}
        tooltipProps={{
          content: ({ label, payload }) => (
            // Only the segments this sprint has: a sprint spreads over a few of the chart's eight, and 16 zero rows would bury them.
            <ChartTooltip label={label} payload={(payload ?? []).filter((entry) => Number(entry.value) > 0)} series={series} valueFormatter={formatMd} />
          ),
        }}
      />
    </Box>
  );
}
