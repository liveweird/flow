import "@mantine/charts/styles.css";
import { BarChart, type BarChartSeries } from "@mantine/charts";
import { Box } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { CATEGORY_PALETTE, CHART_COLORS } from "../utils/chartColors";
import { personLabel } from "../utils/commitmentsReport";
import type { DistributionPerson } from "../utils/distributionPeople";
import ChartLegendList, { PersonSwatch } from "./ChartLegendList";

export interface HistogramRow {
  /** The bucket's range, already formatted ("0.50–0.75"; the open tail "≥ 12"). */
  label: string;
  count: number;
  /** The final open bucket (the long tail): plotted as its own gray series, so colour, legend and tooltip all name it. */
  open?: boolean;
  /** People mode: each person's count in this bucket, index-aligned with the `people` prop (the open bucket included). */
  shares?: number[];
}

/**
 * A distribution's histogram: item count per equal-width range. One series, so no legend; blue
 * (the estimate/plan side of the vocabulary). When the response ends in the open long-tail bucket, that bar is a second,
 * neutral gray series (stacked, so each range keeps one full-width bar) and a legend names both.
 *
 * People mode (`people` given, each row carrying `shares`): one stacked segment per picked person over the same ranges, the open
 * tail bucket split too (so it is no longer a gray series — its "≥" label and table row name it). People are arbitrary names, so
 * they wear the categorical `CATEGORY_PALETTE` by position and — colour never being the only cue — are NUMBERED ("2. Bob") in the
 * legend (from two people) and the tooltip; identity never rests on colour (the throughput chart's rule). A lazy chunk —
 * recharts never enters the main bundle.
 */
export default function DistributionHistogram({
  rows,
  name,
  xAxisLabel,
  people = [],
}: {
  rows: HistogramRow[];
  name: string;
  xAxisLabel: string;
  people?: readonly DistributionPerson[];
}) {
  const { t } = useTranslation();
  const byPerson = people.length > 0;
  const hasTail = !byPerson && rows.some((row) => row.open);
  // A series a range does not belong to is absent (undefined), never 0: the tooltip lists only the bar's own series.
  let data: Record<string, string | number | undefined>[] = rows.map((row) => ({ label: row.label, count: row.count }));
  if (byPerson) {
    data = rows.map((row) => ({ label: row.label, ...Object.fromEntries(people.map((_, index) => [`p${index}`, row.shares?.[index] ?? 0])) }));
  } else if (hasTail) {
    data = rows.map((row) => ({ label: row.label, count: row.open ? undefined : row.count, tail: row.open ? row.count : undefined }));
  }
  const series: BarChartSeries[] = byPerson
    ? people.map((person, index) => ({
        name: `p${index}`,
        label: personLabel(person.position, person.label),
        color: CATEGORY_PALETTE[index % CATEGORY_PALETTE.length],
      }))
    : [{ name: "count", label: t("reports.distribution.count"), color: CHART_COLORS.estimate }];
  if (hasTail) series.push({ name: "tail", label: t("reports.distribution.tailSeries"), color: CHART_COLORS.longTail });
  const groupName = t(byPerson ? "reports.distribution.histogramByPerson" : "reports.distribution.histogramLabel");
  return (
    <Box role="group" aria-label={`${groupName} — ${name}`}>
      {people.length > 1 && (
        <ChartLegendList
          label={t("reports.distribution.personLegend")}
          entries={people.map((person, index) => ({
            key: person.accountId,
            glyph: <PersonSwatch colour={CATEGORY_PALETTE[index % CATEGORY_PALETTE.length]} />,
            text: personLabel(person.position, person.label),
          }))}
        />
      )}
      <BarChart
        h={220}
        data={data}
        dataKey="label"
        type="stacked"
        withLegend={hasTail}
        series={series}
        xAxisLabel={xAxisLabel}
        yAxisLabel={t("reports.distribution.yAxis")}
        tickLine="y"
        barProps={
          byPerson
            ? { maxBarSize: 40, stroke: "var(--mantine-color-body)", strokeOpacity: 1, strokeWidth: 2 }
            : { maxBarSize: 40 }
        }
        xAxisProps={rows.length > 6 ? { interval: 0, angle: -30, textAnchor: "end", height: 70 } : undefined}
      />
    </Box>
  );
}
