import { useTranslation } from "react-i18next";
import { SimpleGrid, Stack, Text } from "@mantine/core";
import { getTaskEstimatesItems } from "../api/reportDrill";
import { getTaskEstimatesReport, type TaskEstimatesGroup } from "../api/reports";
import DistributionDrillDrawer, { type DistributionDrillView } from "../components/DistributionDrillDrawer";
import DistributionWithAccounting from "../components/DistributionWithAccounting";
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
import { distributionPeople } from "../utils/distributionPeople";
import { formatMd, formatMedian } from "../utils/reportFormat";
import { ESTIMATION_TABS } from "../utils/reportLinks";

/**
 * Task estimates is read per team — never the whole unit — and the member control picks several people, whose two histograms stack
 * per person (the percentile strip and the accounting stay the whole selection). It reads the very population of Task accuracy, so it
 * honours the same slices: the domain view (delivered in — the task's own domain, the default), domain, activity type and work
 * category. A module-level constant: the hook reads its identity.
 */
const CONTROLS: ReportControls = {
  teamRequired: true,
  multiMember: true,
  domainView: "TASK",
  domain: true,
  activityType: true,
  workCategory: true,
};

/** The card's focus-return anchor for a drill opened by a chart double-click (`ReportChartCard`'s `drillAnchor`). */
const DRILL_ANCHOR = "task-estimates";

/**
 * Report 19 — task estimates: the distribution of the finished tasks' estimates in MD, at done (primary) and at start, in the
 * server's fixed 0.5-MD bars. The histogram labels a bar by its upper bound because the response carries `bucketWidth`
 * (`histogramLabels`), not because of anything this page says.
 */
export default function ReportTaskEstimates() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, filterKey, setFilter, query, storageKey } = useReportPage(
    "task-estimates",
    getTaskEstimatesReport,
    undefined,
    CONTROLS,
  );
  const drill = useReportDrill(filterKey, DRILL_ANCHOR);
  const report = query.data;
  // A bar of kept (placeholder) data belongs to the PREVIOUS filter: it must not open a drill under the new one.
  const drillable = !query.isPlaceholderData;
  const openBar = useHistogramDrill(drill);
  // The shown report decides the mode (not the filter): a refetch over kept data never mixes the two shapes.
  const userMode = report?.meta.level === "USER";
  // Each histogram's own split numbers the people (request order); a hidden distribution has none, so the other one may.
  const donePeople = report ? distributionPeople(report.atDone) : [];
  const startPeople = report ? distributionPeople(report.atStart) : [];
  const totalsPeople = donePeople.length > 0 ? donePeople : startPeople;
  const drillViews: DistributionDrillView[] = report
    ? [
        {
          view: "AT_DONE",
          title: t("reports.taskEstimates.doneTitle"),
          distribution: report.atDone,
          people: donePeople,
          format: formatMd,
          unit: "estimateMd",
        },
        {
          view: "AT_START",
          title: t("reports.taskEstimates.startTitle"),
          distribution: report.atStart,
          people: startPeople,
          format: formatMd,
          unit: "estimateMd",
        },
      ]
    : [];

  const groupColumns: GroupColumn<TaskEstimatesGroup>[] = [
    { key: "population", header: t("reports.taskEstimates.column.population"), render: (g) => g.excluded.population },
    { key: "doneN", header: t("reports.taskEstimates.column.doneN"), render: (g) => g.atDone.n },
    { key: "doneMedian", header: t("reports.taskEstimates.column.doneMedian"), render: (g) => formatMedian(g.atDone, formatMd) },
    { key: "startN", header: t("reports.taskEstimates.column.startN"), render: (g) => g.atStart.n },
    { key: "startMedian", header: t("reports.taskEstimates.column.startMedian"), render: (g) => formatMedian(g.atStart, formatMd) },
  ];

  return (
    <Stack gap="md">
      <ReportPageHeader title={t("reports.taskEstimates.title")} description={t("reports.taskEstimates.description")} filter={filter} filters={filters} controls={CONTROLS} tail />
      <ReportPageShell
        tabs={ESTIMATION_TABS}
        filtersQuery={filtersQuery}
        filters={filters}
        noTeamsLabel={t("reports.taskEstimates.noTeams")}
      >
        {(filters) => (
          <>
            <ReportFilterBar storageKey={storageKey} filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
            {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
            <ReportTailControl filter={filter} onChange={setFilter} strictTail />
            <ReportChartCard
              title={t("reports.taskEstimates.title")}
              caption={t("reports.taskEstimates.hint")}
              isPending={query.isPending}
              isRefreshing={query.isPlaceholderData}
              error={query.error}
              empty={report?.excluded.population === 0}
              drillAnchor={DRILL_ANCHOR}
            >
              {report && (
                <SimpleGrid cols={{ base: 1, md: 2 }} spacing="lg">
                  {/* Each view carries ITS OWN accounting: the two partitions differ, so they never share a list. */}
                  <DistributionWithAccounting
                    title={t("reports.taskEstimates.doneTitle")}
                    caption={t("reports.taskEstimates.doneCaption")}
                    distribution={report.atDone}
                    minSampleSize={report.meta.minSampleSize}
                    format={formatMd}
                    people={donePeople}
                    axisLabel={t("reports.taskEstimates.axis")}
                    population={report.excluded.population}
                    items={[{ label: t("reports.taskEstimates.excluded.unestimatedAtDone"), count: report.excluded.unestimatedAtDone }]}
                    onDrill={openBar("AT_DONE")}
                    drillDisabled={!drillable}
                  />
                  <DistributionWithAccounting
                    title={t("reports.taskEstimates.startTitle")}
                    caption={t("reports.taskEstimates.startCaption")}
                    distribution={report.atStart}
                    minSampleSize={report.meta.minSampleSize}
                    format={formatMd}
                    people={startPeople}
                    axisLabel={t("reports.taskEstimates.axis")}
                    population={report.excluded.population}
                    items={[
                      { label: t("reports.taskEstimates.excluded.neverStarted"), count: report.excluded.neverStarted },
                      { label: t("reports.taskEstimates.excluded.unestimatedAtStart"), count: report.excluded.unestimatedAtStart },
                    ]}
                    onDrill={openBar("AT_START")}
                    drillDisabled={!drillable}
                  />
                </SimpleGrid>
              )}
            </ReportChartCard>
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
              <DistributionDrillDrawer
                report="task-estimates"
                fetchItems={getTaskEstimatesItems}
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
