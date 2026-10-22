import "@mantine/charts/styles.css";
import { AreaChart, BarChart, ChartTooltip } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CATEGORY_PALETTE, CHART_COLORS, DEEP_DIVE_FILL_ALPHA } from "../utils/chartColors";
import { personLabel } from "../utils/commitmentsReport";
import { formatMd } from "../utils/reportFormat";
import { COST_SERIES, type ThroughputPerson, type ThroughputPlotRow } from "../utils/throughputReport";
import { barDoubleClickProps, type ChartBarHit } from "../utils/chartDrill";
import ChartLegendList, { DashedLineGlyph, PersonSwatch, TreatmentGlyph } from "./ChartLegendList";

const MAX_BAR = 28;
/** The delivered and the actual-cost stacks of the people view (two columns per slot, a person's segment in each). */
const DELIVERED_STACK = "delivered";
const COST_STACK = "cost";

export interface ThroughputChartProps {
  /** What the slots are — weeks/months, or sprints (only the accessible name and the label tilt differ). */
  scope: "period" | "sprint";
  /** Running totals as stacked areas (the cumulative-flow look) instead of one bar per slot. */
  cumulative: boolean;
  rows: ThroughputPlotRow[];
  /** The picked people, bottom of the stack first; none = the whole team (a teal Delivered and a gray Actual cost series). */
  people: ThroughputPerson[];
  /** A double-click on a bar (the whole team) or on one person's segment: the row's `id` and the series (`md`/`ac`, or the person's `key`/`costKey`). */
  onBarDoubleClick?: (hit: ChartBarHit) => void;
}

/**
 * Delivered and actual-cost MD on ONE axis — per week/month or per sprint. Whole team: a teal Delivered bar beside a gray
 * Actual cost bar (the cost gray of the EVM curves and the Deep dive; the legend names both, position and the table carry
 * the rest). People picked: two stacks per slot — Delivered (by assignee) and Actual cost (by who logged the time, translucent fill inside a
 * solid edge, Commitments' committed-stack idiom) — one segment per person; people are arbitrary names, so they wear the
 * categorical `CATEGORY_PALETTE` by position (never a semantic hue) and are NUMBERED ("2. Bob") in the page's own legend and the tooltip, so identity never
 * rests on colour. `cumulative` draws running totals instead: Delivered as areas (stacked per person, whose top edge is the team's total) and
 * Actual cost as ONE dashed line, the picked people's summed time (a per-person cost line would bury the chart). Cost is MD only: it has no item count.
 * A lazy chunk: recharts never enters the main bundle. The tooltip adds the item count, so an unestimated task (an item
 * worth 0 MD) is never invisible.
 */
export default function ThroughputChart({ scope, cumulative, rows, people, onBarDoubleClick }: ThroughputChartProps) {
  const { t } = useTranslation();
  const byPerson = people.length > 0;
  const colour = (index: number) => CATEGORY_PALETTE[index % CATEGORY_PALETTE.length];
  const personName = (person: ThroughputPerson) => personLabel(person.position, person.label);
  const delivered = byPerson
    ? people.map((person, index) => ({
        name: person.key,
        label: t(cumulative ? "reports.throughput.userSeries.cumulative" : "reports.throughput.userSeries.delivered", { name: personName(person) }),
        color: colour(index),
        stackId: DELIVERED_STACK,
      }))
    : [
        {
          name: "md",
          label: t(cumulative ? "reports.throughput.series.cumulative" : "reports.throughput.series.delivered"),
          color: CHART_COLORS.delivered,
        },
      ];
  // Per period with people picked each person also has a cost segment; cumulative (and the whole team) has the one cost series.
  const cost =
    byPerson && !cumulative
      ? people.map((person, index) => ({
          name: person.costKey,
          label: t("reports.throughput.userSeries.actualCost", { name: personName(person) }),
          color: colour(index),
          stackId: COST_STACK,
        }))
      : [
          {
            name: COST_SERIES,
            label: t(cumulative ? "reports.throughput.series.actualCostCumulative" : "reports.throughput.series.actualCost"),
            color: CHART_COLORS.actualCost,
            strokeDasharray: cumulative ? "6 4" : undefined,
          },
        ];
  const series = [...delivered, ...cost];
  const name = t(`reports.throughput.chartLabels.${scope}${cumulative ? "Cumulative" : ""}`);
  const crowded = rows.length > (scope === "sprint" ? 6 : 8);
  const common = {
    h: 300,
    data: rows,
    dataKey: "label",
    series,
    yAxisLabel: t("reports.unitMd"),
    valueFormatter: formatMd,
    tickLine: "y" as const,
    // The whole team has two series (Delivered, Actual cost): the chart's own legend names them. People are told by the page's numbered legend.
    ...(byPerson ? {} : { withLegend: true, legendProps: { verticalAlign: "top" as const, height: 36 } }),
    xAxisProps: crowded ? { interval: 0, angle: -30, textAnchor: "end" as const, height: scope === "sprint" ? 80 : 70 } : undefined,
    tooltipProps: {
      content: ({ label, payload }: { label?: string | number; payload?: readonly { payload?: unknown }[] }) => {
        const row = payload?.[0]?.payload as ThroughputPlotRow | undefined;
        const items = row ? t("reports.items", { count: row.items }) : "";
        const note = cumulative ? t("reports.throughput.tooltipCumulativeItems", { items }) : items;
        return (
          <ChartTooltip
            label={row ? `${String(label)} · ${note}` : label}
            payload={(payload ?? []) as never}
            series={series}
            valueFormatter={formatMd}
          />
        );
      },
    },
  };
  return (
    <Box
      role="group"
      aria-label={byPerson ? t("reports.throughput.chartLabels.byPerson", { label: name }) : name}
      // A double-click on a drillable bar must not select the axis labels around it.
      style={onBarDoubleClick && !cumulative ? { userSelect: "none" } : undefined}
    >
      {byPerson && (
        <ChartLegendList
          label={t("reports.throughput.userLegendLabel")}
          entries={[
            ...(people.length > 1
              ? people.map((person, index) => ({ key: person.accountId, glyph: <PersonSwatch colour={colour(index)} />, text: personName(person) }))
              : []),
            // The two stacks (or the cost line) are told apart by treatment, never by a person's colour.
            ...(cumulative
              ? [{ key: "cost", glyph: <DashedLineGlyph />, text: t("reports.throughput.userLegend.actualCostCumulative") }]
              : [
                  { key: "delivered", glyph: <TreatmentGlyph solid />, text: t("reports.throughput.userLegend.delivered") },
                  { key: "cost", glyph: <TreatmentGlyph solid={false} />, text: t("reports.throughput.userLegend.actualCost") },
                ]),
          ]}
        />
      )}
      {cumulative ? (
        <AreaChart
          {...common}
          curveType="linear"
          withGradient={false}
          withDots={rows.length === 1}
          fillOpacity={0.55}
          // The delivered areas stack into the team's running total; the cost is its own unfilled dashed line over them.
          areaProps={(item) => (item.name === COST_SERIES ? { fillOpacity: 0 } : { stackId: DELIVERED_STACK })}
        />
      ) : (
        <BarChart
          {...common}
          barProps={(bar) => ({
            maxBarSize: MAX_BAR,
            // Cost stack: a translucent fill inside a solid edge; delivered: solid, ringed in the surface so stacked segments never touch.
            ...(bar.stackId === COST_STACK
              ? { fillOpacity: DEEP_DIVE_FILL_ALPHA, strokeOpacity: 1, strokeWidth: 2 }
              : byPerson
                ? { stroke: "var(--mantine-color-body)", strokeOpacity: 1, strokeWidth: 2 }
                : {}),
            ...barDoubleClickProps(bar.name, onBarDoubleClick),
          })}
        />
      )}
    </Box>
  );
}
