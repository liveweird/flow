import { lazy, Suspense } from "react";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import { Box, SimpleGrid, Stack, Text } from "@mantine/core";
import type { CostsReport } from "../api/reports";
import type { DrillState } from "../hooks/useReportDrill";
import { keyLabel, sliceColors, visibleBreakdowns, type CostBreakdownBy, type DisplaySlice } from "../utils/costBreakdown";
import { breakdownAnchor } from "../utils/costsReport";
import type { ReportFilterState } from "../utils/reportFilter";
import { formatMd, formatPercent } from "../utils/reportFormat";
import type { DonutEntry } from "./CostBreakdownChart";
import CostBreakdownDrillDrawer from "./CostBreakdownDrillDrawer";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import { PersonSwatch } from "./ChartLegendList";
import LoadingBlock from "./LoadingBlock";
import ReportChartCard from "./ReportChartCard";
import ScrollRegion from "./ScrollRegion";
import DrillFigure from "./DrillFigure";

// The donut (and with it recharts) rides its own lazy chunk.
const CostBreakdownChart = lazy(() => import("./CostBreakdownChart"));

/** A drawn slice with the words the page prints for it. */
interface Row extends DonutEntry {
  slice: DisplaySlice;
}

function rowsOf(slices: readonly DisplaySlice[], t: TFunction): Row[] {
  const colors = sliceColors(slices.filter((slice) => !slice.other).length, slices.some((slice) => slice.other));
  return slices.map((slice, index) => ({
    id: String(index),
    slice,
    // "Other (N)" names the fold with its size, so it cannot be mistaken for (or collide with) a real key called "Other".
    label: slice.other ? t("reports.costs.breakdown.otherCount", { count: slice.keys.length }) : keyLabel(t, slice.key),
    md: slice.md,
    share: slice.share,
    color: colors[index],
    includes: slice.other ? slice.keys.map((key) => keyLabel(t, key)).join(", ") : undefined,
  }));
}

/**
 * Costs' two parts-of-whole charts — man-days by activity type and by work category — over the very worklogs the totals above
 * read, each a donut with its legend TABLE beside it (the table has every number and is the keyboard and screen-reader route: its
 * man-day figures are buttons that open the tasks behind the slice). A breakdown whose own filter is set has one slice and is not
 * drawn. Slices under 3% of the total fold into one "Other" slice that lists them. The drill drawer lives here too, so a deep link
 * (`drill=cost:…`) opens it once the report is in hand; the drill state itself is the page's (one hook for the page's two cards).
 */
export default function CostBreakdownSection({
  report,
  filter,
  timeZone,
  drill,
  drillable,
  isRefreshing,
}: {
  report: CostsReport;
  filter: ReportFilterState;
  timeZone: string;
  /** The page's drill (one hook for both cards: one focus owner). */
  drill: DrillState;
  /** False while the report shown is kept data of the previous filter: its slices must not open a drill under the new one. */
  drillable: boolean;
  isRefreshing: boolean;
}) {
  const { t } = useTranslation();
  const breakdowns = visibleBreakdowns(report, filter);

  const columns = (by: CostBreakdownBy, onOpen: (row: Row, trigger: HTMLElement) => void): ColumnDef<Row>[] => [
    {
      key: "name",
      header: t(by === "ACTIVITY_TYPE" ? "reports.filters.activityType" : "reports.filters.workCategory"),
      render: (row) => (
        <Box display="flex" style={{ alignItems: "flex-start", gap: 8 }}>
          <Box pt={3}>
            <PersonSwatch colour={row.color} />
          </Box>
          <Stack gap={0}>
            <Text size="sm">{row.label}</Text>
            {row.includes && (
              <Text size="xs" c="dimmed">
                {t("reports.costs.breakdown.includes", { keys: row.includes })}
              </Text>
            )}
          </Stack>
        </Box>
      ),
    },
    {
      key: "md",
      header: t("reports.costs.breakdown.md"),
      align: "right",
      render: (row) => (
        <DrillFigure
          label={t("reports.costs.breakdown.openAria", { md: formatMd(row.md), name: row.label })}
          count={row.md > 0 ? 1 : 0}
          disabled={!drillable}
          onOpen={(trigger) => onOpen(row, trigger)}
        >
          {formatMd(row.md)}
        </DrillFigure>
      ),
    },
    { key: "share", header: t("reports.costs.breakdown.share"), align: "right", render: (row) => formatPercent(row.share) },
  ];

  return (
    <>
      <SimpleGrid cols={{ base: 1, lg: Math.max(1, breakdowns.length) }} spacing="md">
        {breakdowns.map(({ by, slices }) => {
          const rows = rowsOf(slices, t);
          const title = t(`reports.costs.breakdown.title.${by}`);
          const open = (row: Row, trigger?: HTMLElement) =>
            drill.open({ kind: "cost", by, keys: row.slice.keys }, { trigger, anchor: breakdownAnchor(by) });
          return (
            <ReportChartCard
              key={by}
              title={title}
              caption={t("reports.costs.breakdown.caption")}
              isPending={false}
              isRefreshing={isRefreshing}
              empty={false}
              hint={t("reports.costs.breakdown.hint")}
              drillAnchor={breakdownAnchor(by)}
            >
              <Stack gap="md">
                <Suspense fallback={<LoadingBlock />}>
                  <CostBreakdownChart
                    entries={rows}
                    label={t("reports.costs.breakdown.chartLabel", { title })}
                    onSelect={(id) => {
                      const row = rows.find((candidate) => candidate.id === id);
                      if (row && drillable) open(row);
                    }}
                  />
                </Suspense>
                <ScrollRegion label={title} minWidth={320}>
                  <ColumnTable aria-label={title} columns={columns(by, open)} rows={rows} rowKey={(row) => row.id} />
                </ScrollRegion>
              </Stack>
            </ReportChartCard>
          );
        })}
      </SimpleGrid>
      <CostBreakdownDrillDrawer opened={drill.target !== null} target={drill.shown} filter={filter} timeZone={timeZone} onClose={drill.close} />
    </>
  );
}
