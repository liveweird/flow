import "@mantine/charts/styles.css";
import { BarChart, ChartTooltip } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CHART_COLORS } from "../utils/chartColors";
import { barDoubleClickProps, type ChartBarHit } from "../utils/chartDrill";
import { useHomeWindowDays } from "../hooks/useHomeWindowDays";
import { isEpicView, teamBarsHeight, type HomeFigureRow } from "../utils/homeOverview";
import HomeTrendLabel from "./HomeTrendLabel";

const TEAM_AXIS_WIDTH = 120;

/**
 * One small multiple of the Home overview: one figure of every team as a horizontal bar (team names stay legible), in the team order
 * every chart of the page shares. One series, so no legend; the trend arrow sits at the end of the bar and the numbers — the figure,
 * its sample, the previous window and the verdict — are in the tooltip and the chart's table. A hidden figure (under the minimum
 * sample) draws no bar and says so in its tooltip. A lazy chunk: recharts never enters the main bundle.
 */
export default function HomeTeamBarsChart({
  rows,
  title,
  color,
  format,
  integer = false,
  onBarDoubleClick,
}: {
  rows: HomeFigureRow[];
  title: string;
  color: string;
  format: (value: number) => string;
  /** The values are counts: the axis ticks are whole numbers. */
  integer?: boolean;
  /** A double-click on a bar: the row's `id` is the team id. */
  onBarDoubleClick?: (hit: ChartBarHit) => void;
}) {
  const { t } = useTranslation();
  const days = useHomeWindowDays();
  const data = rows.map((row) => ({ id: String(row.teamId), team: row.team, value: row.value }));
  const series = [{ name: "value", label: title, color }];
  const previousSeries = { name: "previous", label: t("home.chart.previous", { days }), color: CHART_COLORS.previousWindow };
  return (
    <Box
      role="group"
      aria-label={t("home.chart.label", { title })}
      // A double-click on a drillable bar must not select the axis labels around it.
      style={onBarDoubleClick ? { userSelect: "none" } : undefined}
    >
      <BarChart
        h={teamBarsHeight(rows.length)}
        data={data}
        dataKey="team"
        orientation="vertical"
        series={series}
        valueFormatter={format}
        tickLine="x"
        gridAxis="x"
        yAxisProps={{ width: TEAM_AXIS_WIDTH }}
        // Headroom to the right of the longest bar: the trend arrow is drawn past its end.
        xAxisProps={{ allowDecimals: !integer, domain: [0, (max: number) => Math.max(max * 1.15, 1)] }}
        barProps={(bar) => ({
          maxBarSize: 18,
          ...barDoubleClickProps(bar.name, onBarDoubleClick),
          label: (props: object) => <HomeTrendLabel {...props} rows={rows} />,
        })}
        tooltipProps={{
          content: ({ label }) => {
            const row = rows.find((candidate) => candidate.team === label);
            if (!row) return null;
            // Mantine's tooltip reads each item's figure from `item.payload[item.name]` (the data row), so a hand-built item carries the row.
            const figures = { value: row.value, previous: row.previous?.value ?? null };
            const items = [] as { name: string; value: number; color: string; payload: typeof figures }[];
            if (row.value !== null) items.push({ name: "value", value: row.value, color, payload: figures });
            if (row.previous?.value != null) items.push({ name: "previous", value: row.previous.value, color: CHART_COLORS.previousWindow, payload: figures });
            const sample = t(isEpicView(row.view) ? "home.count.epics" : "home.count.tasks", { count: row.n });
            const note = row.hidden ? `, ${t("home.table.belowMinimum")}` : "";
            const verdict = row.trend && row.trend.tone !== "none" ? ` · ${t(`home.trend.${row.trend.tone}`)}` : "";
            return (
              <ChartTooltip
                label={`${row.team} · ${sample}${note}${verdict}`}
                payload={items}
                series={[...series, previousSeries]}
                valueFormatter={format}
              />
            );
          },
        }}
      />
    </Box>
  );
}
