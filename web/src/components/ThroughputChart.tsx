import "@mantine/charts/styles.css";
import { AreaChart, BarChart, ChartTooltip } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CATEGORY_PALETTE, CHART_COLORS } from "../utils/chartColors";
import { personLabel } from "../utils/commitmentsReport";
import { formatMd } from "../utils/reportFormat";
import type { ThroughputPerson, ThroughputPlotRow } from "../utils/throughputReport";
import { barDoubleClickProps, type ChartBarHit } from "../utils/chartDrill";
import ChartLegendList, { PersonSwatch } from "./ChartLegendList";

const MAX_BAR = 28;

export interface ThroughputChartProps {
  /** What the slots are — weeks/months, or sprints (only the accessible name and the label tilt differ). */
  scope: "period" | "sprint";
  /** Running totals as stacked areas (the cumulative-flow look) instead of one bar per slot. */
  cumulative: boolean;
  rows: ThroughputPlotRow[];
  /** The picked people, bottom of the stack first; none = the whole team (one teal series). */
  people: ThroughputPerson[];
  /** A double-click on a bar (the whole team) or on one person's segment: the row's `id` and the series (`md` or the person's `key`). */
  onBarDoubleClick?: (hit: ChartBarHit) => void;
}

/**
 * Delivered MD on ONE axis — per week/month or per sprint. Whole team: one teal series (delivery teal), so no legend (the
 * card title names it). People picked: one stacked segment per person; people are arbitrary names, so they wear the
 * categorical `CATEGORY_PALETTE` by position (never a semantic hue) and are NUMBERED ("2. Bob") in the page's own legend and the tooltip, so identity never
 * rests on colour. `cumulative` draws the same series as running totals, stacked areas whose top edge is the team's total.
 * A lazy chunk: recharts never enters the main bundle. The tooltip adds the item count, so an unestimated task (an item
 * worth 0 MD) is never invisible.
 */
export default function ThroughputChart({ scope, cumulative, rows, people, onBarDoubleClick }: ThroughputChartProps) {
  const { t } = useTranslation();
  const byPerson = people.length > 0;
  const series = byPerson
    ? people.map((person, index) => ({
        name: person.key,
        label: t(cumulative ? "reports.throughput.userSeries.cumulative" : "reports.throughput.userSeries.delivered", {
          name: personLabel(person.position, person.label),
        }),
        color: CATEGORY_PALETTE[index % CATEGORY_PALETTE.length],
      }))
    : [
        {
          name: "md",
          label: t(cumulative ? "reports.throughput.series.cumulative" : "reports.throughput.series.delivered"),
          color: CHART_COLORS.delivered,
        },
      ];
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
      {people.length > 1 && (
        <ChartLegendList
          label={t("reports.throughput.userLegendLabel")}
          entries={people.map((person, index) => ({
            key: person.accountId,
            glyph: <PersonSwatch colour={CATEGORY_PALETTE[index % CATEGORY_PALETTE.length]} />,
            text: personLabel(person.position, person.label),
          }))}
        />
      )}
      {cumulative ? (
        <AreaChart
          {...common}
          type="stacked"
          curveType="linear"
          withGradient={false}
          withDots={rows.length === 1}
          fillOpacity={0.55}
        />
      ) : (
        <BarChart
          {...common}
          type={byPerson ? "stacked" : "default"}
          barProps={(bar) => ({
            ...(byPerson
              ? { maxBarSize: MAX_BAR, stroke: "var(--mantine-color-body)", strokeOpacity: 1, strokeWidth: 2 }
              : { maxBarSize: MAX_BAR }),
            ...barDoubleClickProps(bar.name, onBarDoubleClick),
          })}
        />
      )}
    </Box>
  );
}
