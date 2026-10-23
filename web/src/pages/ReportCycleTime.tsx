import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Alert, SimpleGrid, Stack, Text } from "@mantine/core";
import { IconInfoCircle } from "@tabler/icons-react";
import { getCycleTimeItems } from "../api/reportDrill";
import { getCycleTimeReport, type CycleTimeGroup } from "../api/reports";
import ColumnTable, { type ColumnDef } from "../components/ColumnTable";
import CycleTimeEpics from "../components/CycleTimeEpics";
import DistributionDrillDrawer, { type DistributionDrillView } from "../components/DistributionDrillDrawer";
import DistributionWithAccounting from "../components/DistributionWithAccounting";
import LoadingBlock from "../components/LoadingBlock";
import ReportPageHeader from "../components/ReportPageHeader";
import ReportPageShell from "../components/ReportPageShell";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar, { type ReportControls } from "../components/ReportFilterBar";
import ReportGroupsTable, { type GroupColumn } from "../components/ReportGroupsTable";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportPersonTotals from "../components/ReportPersonTotals";
import ReportTailControl from "../components/ReportTailControl";
import { useHistogramDrill } from "../hooks/useHistogramDrill";
import { useReportDrill } from "../hooks/useReportDrill";
import { useReportPage } from "../hooks/useReportPage";
import { cycleTrendRows, trendHasPoints, type CycleTrendRow } from "../utils/cycleTimeReport";
import { distributionPeople } from "../utils/distributionPeople";
import { formatDays, formatMedian } from "../utils/reportFormat";
import { DELIVERY_TABS } from "../utils/reportLinks";
import ScrollRegion from "../components/ScrollRegion";

// The trend chart (and with it recharts) rides its own lazy chunk.
const CycleTimeTrendChart = lazy(() => import("../components/CycleTimeTrendChart"));

/**
 * Cycle time is read per team — never the whole unit — and the member control picks several people, whose two histograms stack
 * per person (the trend and the epics stay the whole selection). It honours the domain view (delivered in — the default),
 * domain, activity type and work category, and trends by week or month. A module-level constant: the hook reads its identity.
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

const MISSING = "—";

/** The focus-return anchors of a drill opened by a chart double-click: the tasks card and the epics card below it. */
const TASKS_ANCHOR = "cycle-time-tasks";
const EPICS_ANCHOR = "cycle-time-epics";

/**
 * Report 7 — cycle time: working days (primary) and elapsed days of finished tasks, plus the per-period trend, and the
 * finished epics beside them (their own cycle, by owner team).
 */
export default function ReportCycleTime() {
  const { t } = useTranslation();
  // The report travels with the bucket that produced it (the throughput page's rule): a refetch over
  // kept data never labels the old buckets with the new resolution.
  const { filtersQuery, filters, filter, filterKey, setFilter, query, storageKey } = useReportPage(
    "cycle-time",
    async (requested) => ({
      ...(await getCycleTimeReport(requested)),
      bucket: requested.bucket ?? "WEEK",
    }),
    undefined,
    CONTROLS,
  );
  const drill = useReportDrill(filterKey, TASKS_ANCHOR);
  const report = query.data;
  // A bar of kept (placeholder) data belongs to the PREVIOUS filter: it must not open a drill under the new one.
  const drillable = !query.isPlaceholderData;
  const openBar = useHistogramDrill(drill);
  // The shown report decides the mode (not the filter): a refetch over kept data never mixes the two shapes.
  const userMode = report?.meta.level === "USER";
  // Each histogram's own split numbers the people (request order); a hidden distribution has none, so the other one may.
  const workingPeople = report ? distributionPeople(report.workingDays) : [];
  const elapsedPeople = report ? distributionPeople(report.elapsedDays) : [];
  const totalsPeople = workingPeople.length > 0 ? workingPeople : elapsedPeople;
  const rows = report ? cycleTrendRows(report.trend, report.bucket) : [];
  const drillViews: DistributionDrillView[] = report
    ? [
        {
          view: "WORKING_DAYS",
          title: t("reports.cycleTime.workingTitle"),
          distribution: report.workingDays,
          people: workingPeople,
          format: formatDays,
          unit: "wd",
        },
        {
          view: "ELAPSED_DAYS",
          title: t("reports.cycleTime.elapsedTitle"),
          distribution: report.elapsedDays,
          people: elapsedPeople,
          format: formatDays,
          unit: "days",
        },
        // The epics carry no people (and at USER level their block is the all-zero hidden answer, so nothing is drawn to drill).
        {
          view: "EPIC_WORKING_DAYS",
          title: t("reports.cycleTime.epics.workingTitle"),
          distribution: report.epics.workingDays,
          people: [],
          format: formatDays,
          unit: "wd",
        },
        {
          view: "EPIC_ELAPSED_DAYS",
          title: t("reports.cycleTime.epics.elapsedTitle"),
          distribution: report.epics.elapsedDays,
          people: [],
          format: formatDays,
          unit: "days",
        },
      ]
    : [];

  const bucket = report?.bucket ?? "WEEK";
  const trendColumns: ColumnDef<CycleTrendRow>[] = [
    { key: "bucket", header: t(`reports.cycleTime.column.bucket${bucket}`), render: (row) => row.label },
    { key: "n", header: t("reports.cycleTime.column.n"), render: (row) => row.n, align: "right" },
    {
      key: "p50",
      header: t("reports.cycleTime.column.p50"),
      render: (row) => (row.p50 === null ? MISSING : formatDays(row.p50)),
      align: "right",
    },
    {
      key: "p90",
      header: t("reports.cycleTime.column.p90"),
      render: (row) => (row.p90 === null ? MISSING : formatDays(row.p90)),
      align: "right",
    },
  ];

  const groupColumns: GroupColumn<CycleTimeGroup>[] = [
    { key: "population", header: t("reports.cycleTime.column.population"), render: (g) => g.excluded.population },
    { key: "workingN", header: t("reports.cycleTime.column.workingN"), render: (g) => g.workingDays.n },
    { key: "workingMedian", header: t("reports.cycleTime.column.workingMedian"), render: (g) => formatMedian(g.workingDays, formatDays) },
    { key: "elapsedN", header: t("reports.cycleTime.column.elapsedN"), render: (g) => g.elapsedDays.n },
    { key: "elapsedMedian", header: t("reports.cycleTime.column.elapsedMedian"), render: (g) => formatMedian(g.elapsedDays, formatDays) },
  ];

  return (
    <Stack gap="md">
      <ReportPageHeader title={t("reports.cycleTime.title")} description={t("reports.cycleTime.description")} filter={filter} filters={filters} controls={CONTROLS} tail />
      <ReportPageShell
        tabs={DELIVERY_TABS}
        filtersQuery={filtersQuery}
        filters={filters}
        noTeamsLabel={t("reports.cycleTime.noTeams")}
      >
        {(filters) => (
          <>
            <ReportFilterBar storageKey={storageKey} filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
            {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
            <ReportTailControl filter={filter} onChange={setFilter} />
            <ReportChartCard
              title={t("reports.cycleTime.title")}
              isPending={query.isPending}
              isRefreshing={query.isPlaceholderData}
              error={query.error}
              empty={report?.excluded.population === 0}
              drillAnchor={TASKS_ANCHOR}
            >
              {report && (
                <SimpleGrid cols={{ base: 1, md: 2 }} spacing="lg">
                  {/* One partition serves both views (workingDays.n == elapsedDays.n), but each still shows its own accounting. */}
                  <DistributionWithAccounting
                    title={t("reports.cycleTime.workingTitle")}
                    caption={t("reports.cycleTime.workingCaption")}
                    distribution={report.workingDays}
                    minSampleSize={report.meta.minSampleSize}
                    format={formatDays}
                    people={workingPeople}
                    axisLabel={t("reports.cycleTime.workingAxis")}
                    population={report.excluded.population}
                    items={[{ label: t("reports.cycleTime.excluded.neverStarted"), count: report.excluded.neverStarted }]}
                    onDrill={openBar("WORKING_DAYS")}
                    drillDisabled={!drillable}
                  />
                  <DistributionWithAccounting
                    title={t("reports.cycleTime.elapsedTitle")}
                    caption={t("reports.cycleTime.elapsedCaption")}
                    distribution={report.elapsedDays}
                    minSampleSize={report.meta.minSampleSize}
                    format={formatDays}
                    people={elapsedPeople}
                    axisLabel={t("reports.cycleTime.elapsedAxis")}
                    population={report.excluded.population}
                    items={[{ label: t("reports.cycleTime.excluded.neverStarted"), count: report.excluded.neverStarted }]}
                    onDrill={openBar("ELAPSED_DAYS")}
                    drillDisabled={!drillable}
                  />
                </SimpleGrid>
              )}
            </ReportChartCard>
            {report && report.excluded.population > 0 && (
              <ReportChartCard
                title={t("reports.cycleTime.trendTitle")}
                caption={t("reports.cycleTime.trendCaption")}
                isPending={false}
                isRefreshing={query.isPlaceholderData}
                empty={false}
              >
                <Stack gap="md">
                  {trendHasPoints(rows) ? (
                    <Suspense fallback={<LoadingBlock />}>
                      <CycleTimeTrendChart rows={rows} />
                    </Suspense>
                  ) : (
                    // Every period is below the minimum sample: say so instead of drawing an empty frame.
                    <Alert color="gray" variant="light" icon={<IconInfoCircle size={16} />} role="note">
                      {t("reports.cycleTime.trendAllHidden")}
                    </Alert>
                  )}
                  <ScrollRegion label={t("reports.cycleTime.trendTableLabel")} minWidth={320}>
                    <ColumnTable
                      verticalSpacing={4}
                      aria-label={t("reports.cycleTime.trendTableLabel")}
                      columns={trendColumns}
                      rows={rows}
                      // Buckets are ordered and fixed per response; position is the identity.
                      rowKey={(_row, index) => String(index)}
                    />
                  </ScrollRegion>
                </Stack>
              </ReportChartCard>
            )}
            {report && report.groups.length > 0 && (
              <Stack gap="xs">
                {userMode ? (
                  <ReportPersonTotals groups={report.groups} people={totalsPeople} columns={groupColumns} />
                ) : (
                  <ReportGroupsTable level={report.meta.level} filters={filters} groups={report.groups} columns={groupColumns} />
                )}
                <Text size="xs" c="dimmed">
                  {t("reports.groupsCaption.distribution")}
                </Text>
              </Stack>
            )}
            {report && (
              <CycleTimeEpics
                epics={report.epics}
                level={report.meta.level}
                minSampleSize={report.meta.minSampleSize}
                filters={filters}
                isRefreshing={query.isPlaceholderData}
                drillAnchor={EPICS_ANCHOR}
                onDrill={(view, ...bar) => openBar(view, EPICS_ANCHOR)(...bar)}
                drillDisabled={!drillable}
              />
            )}
            {report && (
              <DistributionDrillDrawer
                report="cycle-time"
                fetchItems={getCycleTimeItems}
                opened={drill.target !== null}
                target={drill.shown}
                views={drillViews}
                filter={filter}
                timeZone={filters.timeZone}
                onClose={drill.close}
                onSegmentChange={drill.setSegment}
              />
            )}
          </>
        )}
      </ReportPageShell>
    </Stack>
  );
}
