import "@mantine/charts/styles.css";
import { AreaChart } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { PaintedWipBand } from "../utils/wipReport";

/**
 * Items at the end of each day, stacked by band (stage, status or board column) — the top edge is
 * the total of the ticked bands. One axis (items); a legend from two bands up, none for one. A
 * translucent fill under a full-colour edge: the edge carries the ≥ 3:1 mark, the legend, the
 * tooltip and the tables beside the chart carry identity where hues repeat. A lazy chunk:
 * recharts never enters the main bundle.
 */
export default function WipChart({ rows, bands }: { rows: Array<Record<string, string | number>>; bands: PaintedWipBand[] }) {
  const { t } = useTranslation();
  const series = bands.map((band) => ({ name: band.name, label: band.label, color: band.color }));
  return (
    <Box role="group" aria-label={t("reports.wip.chartLabel")}>
      <AreaChart
        h={320}
        data={rows}
        dataKey="day"
        series={series}
        type="stacked"
        curveType="linear"
        withDots={false}
        withLegend={series.length > 1}
        legendProps={{ verticalAlign: "top", height: 36 }}
        fillOpacity={0.55}
        yAxisLabel={t("reports.wip.yAxis")}
        tickLine="y"
        xAxisProps={{ minTickGap: 48 }}
      />
    </Box>
  );
}
