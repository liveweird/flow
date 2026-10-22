import "@mantine/charts/styles.css";
import { ChartTooltip, CompositeChart } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CHART_COLORS } from "../utils/chartColors";
import { barDoubleClickProps, type ChartBarHit } from "../utils/chartDrill";
import { hasCapacity } from "../utils/commitmentsReport";
import type { CostsCapacityRow } from "../utils/costsSprints";
import { formatMd } from "../utils/reportFormat";

const MAX_BAR = 28;

export interface CostsCapacityChartProps {
  rows: CostsCapacityRow[];
  /** A double-click on a sprint's actual-cost bar: the sprint row's `id` and the series (`acMd`). */
  onBarDoubleClick?: (hit: ChartBarHit) => void;
}

/**
 * Each sprint's actual cost (a bar in the cost gray, the AC colour of the EVM curves) against its capacity (Commitments' idiom: a dashed
 * gray line with one dot per sprint, ONE axis, all MD). A sprint with no capacity has no marker (`connectNulls` off — a gap, never a
 * zero) and the series stays out when no sprint has one. The two share a hue, so form tells them apart — a filled bar against a dashed
 * dotted line — as do the legend and the tooltip, which also names where the capacity comes from; the table beside the chart carries
 * every number. A lazy chunk: recharts never enters the main bundle.
 */
export default function CostsCapacityChart({ rows, onBarDoubleClick }: CostsCapacityChartProps) {
  const { t } = useTranslation();
  const series = [
    { name: "acMd", type: "bar" as const, label: t("reports.costs.sprints.series.ac"), color: CHART_COLORS.actualCost },
    ...(hasCapacity(rows)
      ? [
          {
            name: "capacityMd",
            type: "line" as const,
            label: t("reports.commitments.series.capacity"),
            color: CHART_COLORS.capacity,
            strokeDasharray: "6 4",
          },
        ]
      : []),
  ];
  return (
    <Box
      role="group"
      aria-label={t("reports.costs.sprints.capacityChartLabel")}
      // A double-click on a drillable bar must not select the axis labels around it.
      style={onBarDoubleClick ? { userSelect: "none" } : undefined}
    >
      <CompositeChart
        h={320}
        data={rows}
        dataKey="label"
        series={series}
        curveType="linear"
        connectNulls={false}
        withDots
        dotProps={{ r: 4 }}
        withLegend
        legendProps={{ verticalAlign: "top", height: 36 }}
        yAxisLabel={t("reports.unitMd")}
        valueFormatter={formatMd}
        tickLine="y"
        barProps={(bar) => ({ maxBarSize: MAX_BAR, ...barDoubleClickProps(bar.name, onBarDoubleClick) })}
        xAxisProps={rows.length > 6 ? { interval: 0, angle: -30, textAnchor: "end" as const, height: 80 } : undefined}
        tooltipProps={{
          content: ({ label, payload }) => {
            const row = payload?.[0]?.payload as CostsCapacityRow | undefined;
            const note = row?.capacitySource ? t(`reports.commitments.capacitySource.${row.capacitySource}`) : null;
            return (
              <ChartTooltip
                label={note === null ? label : `${String(label)} · ${note}`}
                payload={(payload ?? []).filter((entry) => entry.name !== "capacityMd" || row?.capacityMd != null)}
                series={series}
                valueFormatter={formatMd}
              />
            );
          },
        }}
      />
    </Box>
  );
}
