import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Paper, SegmentedControl, Stack, Text } from "@mantine/core";
import { IconUsers } from "@tabler/icons-react";
import { getThroughputItems } from "../api/reportDrill";
import { getThroughputReport, type ThroughputGroup, type ThroughputSprint } from "../api/reports";
import EmptyState from "../components/EmptyState";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportGroupsTable, { type GroupColumn } from "../components/ReportGroupsTable";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportSprintsTable, { type SprintColumn } from "../components/ReportSprintsTable";
import ReportTabs from "../components/ReportTabs";
import SprintDrillDrawer from "../components/SprintDrillDrawer";
import ThroughputBucketTable from "../components/ThroughputBucketTable";
import ThroughputDrillDrawer from "../components/ThroughputDrillDrawer";
import ThroughputUserTables from "../components/ThroughputUserTables";
import { useReportPage } from "../hooks/useReportPage";
import { PERIOD_DRILL_ANCHOR, SPRINT_DRILL_ANCHOR, useThroughputDrill } from "../hooks/useThroughputDrill";
import { useThroughputView, type ThroughputView } from "../hooks/useThroughputView";
import { reportQuery, type ReportControls } from "../utils/reportFilter";
import { formatMd } from "../utils/reportFormat";
import { DELIVERY_TABS } from "../utils/reportLinks";
import { sprintLabeller, throughputSprintDrillCount } from "../utils/sprintDrill";
import {
  throughputBuckets,
  throughputCumulativeColumns,
  throughputFigureColumns,
  throughputPlot,
  throughputSprints,
  throughputTotals,
} from "../utils/throughputReport";

// The chart (and with it recharts) rides its own lazy chunk.
const ThroughputChart = lazy(() => import("../components/ThroughputChart"));

/**
 * Throughput is read per team — never the whole unit — and the member control picks several people. It honours the domain
 * view (delivered in — the task's own domain, the default — or earned in), domain, activity type and work category, and
 * buckets by week or month. A module-level constant: the hook reads its identity.
 */
const CONTROLS: ReportControls = {
  teamRequired: true,
  multiMember: true,
  domainView: "TASK",
  domain: true,
  activityType: true,
  workCategory: true,
  bucket: true,
};

/** Report 2 — throughput: what was delivered, per week/month (period view) and per sprint (sprint view), per period or cumulative. */
export default function ReportThroughput() {
  const { t } = useTranslation();
  // The report travels with the bucket that produced it, so a refetch over kept data (a bucket
  // change in flight) never labels the OLD buckets with the NEW resolution.
  const { filtersQuery, filters, filter, setFilter, query, storageKey } = useReportPage(
    "throughput",
    async (requested) => ({
      ...(await getThroughputReport(requested)),
      bucket: requested.bucket ?? "WEEK",
    }),
    undefined,
    CONTROLS,
  );
  const { view, setView } = useThroughputView();
  const cumulative = view === "CUMULATIVE";
  const report = query.data;
  // The shown report decides the mode (not the filter): a refetch over kept data never mixes the two shapes.
  const userMode = report?.meta.level === "USER";
  const noTeams = filters !== undefined && filters.teams.length === 0;
  const totals = report ? throughputTotals(report.byBucket) : undefined;
  const buckets = report ? throughputBuckets(report.byBucket, report.bucket) : undefined;
  const sprints = report && filters ? throughputSprints(report.bySprint, filters) : undefined;
  const sprintRows = new Map(report?.bySprint.map((sprint, index) => [sprint, sprints?.rows[index]]));
  // A bar of kept (placeholder) data belongs to the PREVIOUS filter: it must not open a drill under the new one.
  const { drill, ...handlers } = useThroughputDrill(reportQuery(filter), !query.isPlaceholderData, buckets?.people ?? [], sprints?.people ?? []);

  const groupColumns: GroupColumn<ThroughputGroup>[] = throughputFigureColumns<ThroughputGroup>(t, (g) => g);
  const sprintColumns: SprintColumn<ThroughputSprint>[] = [
    // Both Delivered figures stand behind the one bar a sprint row draws.
    ...throughputFigureColumns<ThroughputSprint>(t, (s) => s).map(
      (column): SprintColumn<ThroughputSprint> => ({ ...column, drill: { bucket: "DELIVERED", items: (s) => s.deliveredItems } }),
    ),
    ...(cumulative
      ? throughputCumulativeColumns<ThroughputSprint>(t, (s) => sprintRows.get(s) ?? { deliveredMd: 0, deliveredItems: 0, cumulativeMd: 0, cumulativeItems: 0 })
      : []),
  ];

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.throughput.title")} description={t("reports.throughput.description")} />
      <ReportTabs tabs={DELIVERY_TABS} />
      <ReportFiltersStatus query={filtersQuery} />
      {noTeams && (
        <Paper withBorder p="md">
          <EmptyState icon={IconUsers} label={t("reports.throughput.noTeams")} />
        </Paper>
      )}
      {filters && !noTeams && (
        <>
          <ReportFilterBar storageKey={storageKey} filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          <SegmentedControl
            aria-label={t("reports.throughput.view.label")}
            w="fit-content"
            data={[
              { value: "PERIOD", label: t("reports.throughput.view.period") },
              { value: "CUMULATIVE", label: t("reports.throughput.view.cumulative") },
            ]}
            value={view}
            onChange={(value) => setView(value as ThroughputView)}
          />
          <ReportChartCard
            title={t(cumulative ? "reports.throughput.periodTitleCumulative" : "reports.throughput.periodTitle")}
            caption={t(`reports.throughput.caption.${filter.domainView ?? "TASK"}`)}
            isPending={query.isPending}
            isRefreshing={query.isPlaceholderData}
            error={query.error}
            empty={totals?.items === 0}
            hint={t(cumulative ? "reports.drill.hintRows" : "reports.drill.hint")}
            drillAnchor={PERIOD_DRILL_ANCHOR}
          >
            {report && totals && buckets && (
              <Stack gap="xs">
                <Text size="sm">
                  {t("reports.throughput.summary", {
                    md: formatMd(totals.md),
                    items: t("reports.items", { count: totals.items }),
                  })}
                </Text>
                <Suspense fallback={<LoadingBlock />}>
                  <ThroughputChart
                    scope="period"
                    cumulative={cumulative}
                    rows={throughputPlot(buckets, cumulative)}
                    people={buckets.people}
                    onBarDoubleClick={handlers.onPeriodBar}
                  />
                </Suspense>
                <ThroughputBucketTable
                  series={buckets}
                  bucket={report.bucket}
                  cumulative={cumulative}
                  onDrill={handlers.onPeriodRow}
                  drillDisabled={handlers.drillDisabled}
                />
              </Stack>
            )}
          </ReportChartCard>
          {/* A failure is reported ONCE, by the first card. */}
          {!query.isError && (
            <ReportChartCard
              title={t(cumulative ? "reports.throughput.sprintTitleCumulative" : "reports.throughput.sprintTitle")}
              caption={t("reports.throughput.sprintCaption")}
              isPending={query.isPending}
              isRefreshing={query.isPlaceholderData}
              error={query.error}
              empty={report?.bySprint.length === 0}
              hint={t(cumulative ? "reports.drill.hintRows" : "reports.drill.hint")}
              drillAnchor={SPRINT_DRILL_ANCHOR}
            >
              {report && sprints && (
                <Stack gap="md">
                  <Suspense fallback={<LoadingBlock />}>
                    <ThroughputChart
                      scope="sprint"
                      cumulative={cumulative}
                      rows={throughputPlot(sprints, cumulative)}
                      people={sprints.people}
                      onBarDoubleClick={handlers.onSprintBar}
                    />
                  </Suspense>
                  <ReportSprintsTable
                    sprints={report.bySprint}
                    filters={filters}
                    columns={sprintColumns}
                    minWidth={cumulative ? 820 : 680}
                    drill={{ onDrill: handlers.onSprintRow, disabled: handlers.drillDisabled }}
                    driftDetail={(sprint) =>
                      sprint.snapshot &&
                      t("reports.throughput.snapshot", {
                        md: formatMd(sprint.snapshot.deliveredMd),
                        items: t("reports.items", { count: sprint.snapshot.deliveredItems }),
                      })
                    }
                  />
                </Stack>
              )}
            </ReportChartCard>
          )}
          {report &&
            buckets &&
            sprints &&
            (userMode ? (
              <ThroughputUserTables
                groups={report.groups}
                buckets={buckets}
                sprints={sprints}
                bucket={report.bucket}
                cumulative={cumulative}
                onDrillPeriodShare={handlers.onPeriodShare}
                onDrillSprintShare={handlers.onSprintShare}
                drillDisabled={handlers.drillDisabled}
              />
            ) : (
              <ReportGroupsTable level={report.meta.level} filters={filters} groups={report.groups} columns={groupColumns} />
            ))}
          <SprintDrillDrawer
            opened={drill.target !== null}
            target={drill.shown}
            filter={filter}
            scope="throughput"
            fetchItems={getThroughputItems}
            sprintLabel={sprintLabeller(report?.bySprint, filters)}
            chartCount={(target) => throughputSprintDrillCount(report?.bySprint, target)}
            people={sprints?.people}
            timeZone={filters.timeZone}
            onClose={drill.close}
            onSegmentChange={drill.setSegment}
          />
          {report && (
            <ThroughputDrillDrawer
              opened={drill.target !== null}
              target={drill.shown}
              filter={filter}
              bucket={report.bucket}
              byBucket={report.byBucket}
              people={buckets?.people ?? []}
              timeZone={filters.timeZone}
              onClose={drill.close}
              onSegmentChange={drill.setSegment}
            />
          )}
        </>
      )}
    </Stack>
  );
}
