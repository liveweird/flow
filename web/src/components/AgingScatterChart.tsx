import "@mantine/charts/styles.css";
import type { ReactElement } from "react";
import { ScatterChart } from "@mantine/charts";
import { Box, Paper, Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { AgingItem, AgingThresholds } from "../api/reports";
import {
  AGING_BAND_COLORS,
  AGING_HIT_RADIUS,
  agingItemKey,
  agingLogAxis,
  agingReferenceLines,
  agingYMax,
  AGING_LOG_FLOOR,
  logPlotPoints,
  logPlotted,
  type AgingPlot,
  type AgingPlotPoint,
} from "../utils/agingChart";
import { bandPercentileLabel, bandTone } from "../utils/agingReport";
import { formatDays } from "../utils/reportFormat";
import AgingDotMark from "./AgingDotMark";

/** The longest x tick label (more categories get a shorter one): the tooltip and the table carry the full name. */
const TICK_CHARS_FEW = 22;
const TICK_CHARS_MANY = 12;
const MANY_CATEGORIES = 7;

function truncate(label: string, max: number): string {
  return label.length > max ? `${label.slice(0, max - 1)}…` : label;
}

/**
 * The aging chart — one dot per in-progress item: x = where it is in the workflow (the server's `positions`, never
 * re-sorted), y = its age in working days from 0. The x axis is a NUMBER axis (category index plus a deterministic jitter
 * inside the category's band) whose ticks are the category indexes, labelled with the position names — which keeps the
 * jitter trivial and the dots exactly where the table says they are. The team's thresholds are dashed reference lines,
 * labelled with the percentile and its value. The y axis extends to the oldest item (an outlier is never clipped); with
 * `logScale` it is a log axis from half a day (ages under it sit on that floor line; tooltip and table keep the true age).
 * Dots wear the SERVER's band (red top threshold, orange next, gray rest — `bandTone`), WAITING items are hollow,
 * blocked ones ringed in red; clicking a dot selects it, and the page scrolls to and highlights its table row. A lazy chunk:
 * recharts never enters the main bundle.
 */
export default function AgingScatterChart({
  plot,
  thresholds,
  selectedKey,
  onSelect,
  logScale = false,
  height = 380,
}: {
  plot: AgingPlot;
  thresholds: AgingThresholds;
  selectedKey: string | null;
  onSelect: (key: string) => void;
  logScale?: boolean;
  height?: number;
}) {
  const { t } = useTranslation();
  const lines = agingReferenceLines(thresholds);
  const yMax = agingYMax(plot, lines);
  const logAxis = agingLogAxis(yMax);
  const points = logScale ? logPlotPoints(plot.points) : plot.points;
  const maxChars = plot.categories.length >= MANY_CATEGORIES ? TICK_CHARS_MANY : TICK_CHARS_FEW;
  const labelOf = (index: number) => plot.categories[Math.round(index)]?.label ?? "";

  const dot = (props: { cx?: number; cy?: number; payload?: AgingPlotPoint }): ReactElement => {
    const item = plot.items[props.payload?.i ?? -1];
    if (!item || props.cx == null || props.cy == null) return <g />;
    const key = agingItemKey(item);
    const tone = bandTone(item.band, thresholds);
    return (
      <g
        transform={`translate(${props.cx} ${props.cy})`}
        style={{ cursor: "pointer" }}
        data-item={key}
        data-tone={tone}
        onClick={() => onSelect(key)}
      >
        <circle r={AGING_HIT_RADIUS} fill="transparent" />
        <AgingDotMark color={AGING_BAND_COLORS[tone]} waiting={item.stage === "WAITING"} blocked={item.blocked} selected={key === selectedKey} />
      </g>
    );
  };

  return (
    <Box role="group" aria-label={t("reports.agingWip.chart.label")}>
      <ScatterChart
        h={height}
        data={[{ name: "items", color: "gray.6", data: points as unknown as Record<string, number>[] }]}
        dataKey={{ x: "x", y: "y" }}
        xAxisLabel={t("reports.agingWip.chart.xAxis")}
        yAxisLabel={t(logScale ? "reports.agingWip.chart.yAxisLog" : "reports.agingWip.chart.yAxis")}
        withLegend={false}
        tickLine="y"
        xAxisProps={{
          domain: [-0.5, plot.categories.length - 0.5],
          ticks: plot.categories.map((_, index) => index),
          interval: 0,
          allowDecimals: false,
          tickFormatter: (value: number) => truncate(labelOf(value), maxChars),
        }}
        yAxisProps={logScale ? { scale: "log", domain: logAxis.domain, ticks: logAxis.ticks, allowDataOverflow: false } : { domain: [0, yMax] }}
        referenceLines={lines.map((line) => ({
          y: logScale ? logPlotted(line.workingDays) : line.workingDays,
          color: AGING_BAND_COLORS[line.tone],
          strokeDasharray: "6 4",
          strokeWidth: 1.5,
          // Text wears the text colour, never the line's hue.
          label: {
            value: t("reports.agingWip.chart.lineLabel", { p: `p${line.percentile}`, days: formatDays(line.workingDays) }),
            position: "insideTopRight",
            fill: "currentColor",
          },
        }))}
        scatterProps={{ shape: dot as never }}
        tooltipProps={{
          content: ({ payload }) => {
            const item = plot.items[(payload?.[0]?.payload as AgingPlotPoint | undefined)?.i ?? -1];
            return item ? <AgingTooltip item={item} plot={plot} thresholds={thresholds} floored={logScale && item.ageWorkingDays < AGING_LOG_FLOOR} /> : null;
          },
        }}
      />
    </Box>
  );
}

/** The hovered dot in words: every channel the dot draws is also named here (and in the table). */
function AgingTooltip({ item, plot, thresholds, floored }: { item: AgingItem; plot: AgingPlot; thresholds: AgingThresholds; floored: boolean }) {
  const { t } = useTranslation();
  const position = plot.categories.find((category) => category.key === item.position)?.label ?? item.position;
  let band = "—";
  if (item.band === "WITHIN") band = t("reports.agingWip.band.within");
  else if (item.band != null && thresholds.percentiles.some((entry) => `P${entry.percentile}` === item.band)) {
    band = t("reports.agingWip.band.above", { p: bandPercentileLabel(item.band) });
  }
  const states = [item.waiting ? t("reports.agingWip.waiting") : null, item.blocked ? t("reports.agingWip.blocked") : null].filter(Boolean);
  return (
    <Paper withBorder shadow="sm" p="xs" maw={320}>
      <Stack gap={2}>
        <Text size="sm" fw={600}>
          {item.issueKey}
        </Text>
        {item.summary && (
          <Text size="xs" c="dimmed" lineClamp={2}>
            {item.summary}
          </Text>
        )}
        <Text size="xs">{`${t("reports.agingWip.chart.tooltip.position")}: ${position}`}</Text>
        <Text size="xs">{`${t("reports.agingWip.column.age")}: ${formatDays(item.ageWorkingDays)}`}</Text>
        {floored && (
          <Text size="xs" c="dimmed">
            {t("reports.agingWip.chart.flooredNote")}
          </Text>
        )}
        <Text size="xs">{`${t("reports.agingWip.column.band")}: ${band}`}</Text>
        {(item.assignee ?? item.assigneeAccountId) && (
          <Text size="xs">{`${t("reports.agingWip.column.assignee")}: ${item.assignee ?? item.assigneeAccountId}`}</Text>
        )}
        {states.length > 0 && <Text size="xs" fw={600}>{states.join(" · ")}</Text>}
      </Stack>
    </Paper>
  );
}
