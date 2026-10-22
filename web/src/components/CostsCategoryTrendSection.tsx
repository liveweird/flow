import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Group, Stack, Text } from "@mantine/core";
import type { CostsCategoryTrend } from "../api/reports";
import { trendChartRows, trendSeries } from "../utils/costsCategories";
import { formatMd, formatPercent } from "../utils/reportFormat";
import { PersonSwatch } from "./ChartLegendList";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import LoadingBlock from "./LoadingBlock";
import ReportChartCard from "./ReportChartCard";
import ScrollRegion from "./ScrollRegion";

// The chart (and with it recharts) rides its own lazy chunk.
const CostsCategoryTrendChart = lazy(() => import("./CostsCategoryTrendChart"));

type Week = CostsCategoryTrend["weeks"][number];

/**
 * Costs' cumulative share of each work category by week (A45): each category's line is its share of everything logged from the start
 * of the period through the end of that week, so the right end is the period's mix and the left shows how it built up. The weekly
 * table (newest week first, so the period's mix is on top) carries every figure. The server sends no trend at the unit level or for a
 * period of more than 160 weeks: one dimmed line says so instead of a card.
 */
export default function CostsCategoryTrendSection({
  trend,
  colors,
  unitLevel,
  refreshing,
}: {
  trend: CostsCategoryTrend | null;
  colors: Record<string, string>;
  unitLevel: boolean;
  refreshing: boolean;
}) {
  const { t } = useTranslation();
  if (trend === null) {
    return (
      <Text size="xs" c="dimmed" data-testid="costs-trend-unavailable">
        {t(unitLevel ? "reports.costs.categories.trend.needTeam" : "reports.costs.categories.trend.tooLong")}
      </Text>
    );
  }
  const series = trendSeries(trend, colors, t);
  const rows = trendChartRows(trend, series);
  const tableLabel = t("reports.costs.categories.trend.tableAria");
  const columns: ColumnDef<Week>[] = [
    { key: "week", header: t("reports.costs.categories.trend.column.week"), render: (week) => week.weekStart },
    { key: "cumulativeMd", header: t("reports.costs.categories.trend.column.cumulativeMd"), align: "right", render: (week) => formatMd(week.cumulativeMd) },
    ...series.map(
      (line): ColumnDef<Week> => ({
        key: line.name,
        header: (
          <Group gap={6} wrap="nowrap" justify="flex-end">
            <PersonSwatch colour={line.color} />
            {line.label}
          </Group>
        ),
        align: "right",
        render: (week) => {
          const share = week.byCategory.find((category) => category.key === line.key)?.share ?? null;
          return share === null ? "—" : formatPercent(share);
        },
      }),
    ),
  ];
  return (
    <ReportChartCard
      title={t("reports.costs.categories.trend.title")}
      caption={t("reports.costs.categories.trend.caption")}
      isPending={false}
      isRefreshing={refreshing}
      empty={series.length === 0 || rows.length === 0}
    >
      <Stack gap="md">
        <Suspense fallback={<LoadingBlock />}>
          <CostsCategoryTrendChart rows={rows} series={series} />
        </Suspense>
        <ScrollRegion label={tableLabel} minWidth={240 + series.length * 110} maxHeight={320}>
          <ColumnTable verticalSpacing={4} stickyHeader aria-label={tableLabel} columns={columns} rows={[...trend.weeks].reverse()} rowKey={(week) => week.weekStart} />
        </ScrollRegion>
      </Stack>
    </ReportChartCard>
  );
}
