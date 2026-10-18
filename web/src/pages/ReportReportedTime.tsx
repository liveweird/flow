import { useTranslation } from "react-i18next";
import { Paper, SimpleGrid, Stack, Text } from "@mantine/core";
import { IconUsers } from "@tabler/icons-react";
import { getReportedTimeItems } from "../api/reportDrill";
import { getReportedTimeRatioReport, type ReportedTimeGroup } from "../api/reports";
import DistributionDrillDrawer, { type DistributionDrillView } from "../components/DistributionDrillDrawer";
import DistributionWithAccounting from "../components/DistributionWithAccounting";
import EmptyState from "../components/EmptyState";
import ReportPageHeader from "../components/ReportPageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar, { type ReportControls } from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportGroupsTable, { type GroupColumn } from "../components/ReportGroupsTable";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportPersonTotals from "../components/ReportPersonTotals";
import ReportTailControl from "../components/ReportTailControl";
import ReportTabs from "../components/ReportTabs";
import { useReportDrill } from "../hooks/useReportDrill";
import { useReportPage } from "../hooks/useReportPage";
import { distributionPeople } from "../utils/distributionPeople";
import { reportQuery } from "../utils/reportFilter";
import { formatMedian, formatPercent, formatRatio } from "../utils/reportFormat";
import { ESTIMATION_TABS } from "../utils/reportLinks";

/**
 * Reported time is read per team — never the whole unit — and the member control picks several people, whose histograms stack
 * per person. It honours the domain view (delivered in — the default), domain, activity type and work category. A module-level
 * constant: the hook reads its identity.
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
const DRILL_ANCHOR = "reported-time";

/** Report 8 — reported time ÷ cycle time, with flow efficiency (A18) beside it. */
export default function ReportReportedTime() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, setFilter, query, storageKey } = useReportPage(
    "reported-time-ratio",
    getReportedTimeRatioReport,
    undefined,
    CONTROLS,
  );
  const drill = useReportDrill(reportQuery(filter), DRILL_ANCHOR);
  const report = query.data;
  // A bar of kept (placeholder) data belongs to the PREVIOUS filter: it must not open a drill under the new one.
  const drillable = !query.isPlaceholderData;
  const openBar = (view: string) => (bucketIndex: number, segmentAccountId?: string, trigger?: HTMLElement) =>
    drill.open(segmentAccountId ? { kind: "hist", view, bucketIndex, segmentAccountId } : { kind: "hist", view, bucketIndex }, { trigger });
  // The shown report decides the mode (not the filter): a refetch over kept data never mixes the two shapes.
  const userMode = report?.meta.level === "USER";
  const noTeams = filters !== undefined && filters.teams.length === 0;
  // Each histogram's own split numbers the people (request order); a hidden distribution has none, so the other one may.
  const ratioPeople = report ? distributionPeople(report.ratio) : [];
  const flowPeople = report ? distributionPeople(report.flowEfficiency) : [];
  const totalsPeople = ratioPeople.length > 0 ? ratioPeople : flowPeople;
  const drillViews: DistributionDrillView[] = report
    ? [
        {
          view: "RATIO",
          title: t("reports.reportedTime.ratioTitle"),
          distribution: report.ratio,
          people: ratioPeople,
          format: formatRatio,
          unit: "ratio",
        },
        {
          view: "FLOW_EFFICIENCY",
          title: t("reports.reportedTime.flowTitle"),
          distribution: report.flowEfficiency,
          people: flowPeople,
          format: formatPercent,
          unit: "share",
        },
      ]
    : [];

  const groupColumns: GroupColumn<ReportedTimeGroup>[] = [
    { key: "population", header: t("reports.reportedTime.column.population"), render: (g) => g.excluded.population },
    { key: "ratioN", header: t("reports.reportedTime.column.ratioN"), render: (g) => g.ratio.n },
    { key: "ratioMedian", header: t("reports.reportedTime.column.ratioMedian"), render: (g) => formatMedian(g.ratio, formatRatio) },
    { key: "flowN", header: t("reports.reportedTime.column.flowN"), render: (g) => g.flowEfficiency.n },
    { key: "flowMedian", header: t("reports.reportedTime.column.flowMedian"), render: (g) => formatMedian(g.flowEfficiency, formatPercent) },
  ];

  return (
    <Stack gap="md">
      <ReportPageHeader title={t("reports.reportedTime.title")} description={t("reports.reportedTime.description")} filter={filter} filters={filters} controls={CONTROLS} tail />
      <ReportTabs tabs={ESTIMATION_TABS} />
      <ReportFiltersStatus query={filtersQuery} />
      {noTeams && (
        <Paper withBorder p="md">
          <EmptyState icon={IconUsers} label={t("reports.reportedTime.noTeams")} />
        </Paper>
      )}
      {filters && !noTeams && (
        <>
          <ReportFilterBar storageKey={storageKey} filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          <ReportTailControl filter={filter} onChange={setFilter} />
          <ReportChartCard
            title={t("reports.reportedTime.title")}
            isPending={query.isPending}
            isRefreshing={query.isPlaceholderData}
            error={query.error}
            empty={report?.excluded.population === 0}
            drillAnchor={DRILL_ANCHOR}
          >
            {report && (
              <SimpleGrid cols={{ base: 1, md: 2 }} spacing="lg">
                {/* The two measures have different partitions (no "no time logged" bucket for flow efficiency), so each keeps its own accounting. */}
                <DistributionWithAccounting
                  title={t("reports.reportedTime.ratioTitle")}
                  caption={t("reports.reportedTime.ratioCaption")}
                  distribution={report.ratio}
                  minSampleSize={report.meta.minSampleSize}
                  format={formatRatio}
                  people={ratioPeople}
                  axisLabel={t("reports.reportedTime.ratioAxis")}
                  population={report.excluded.population}
                  items={[
                    { label: t("reports.reportedTime.excluded.noWorklogs"), count: report.excluded.noWorklogs },
                    { label: t("reports.reportedTime.excluded.neverStarted"), count: report.excluded.neverStarted },
                    { label: t("reports.reportedTime.excluded.zeroCycle"), count: report.excluded.zeroCycle },
                  ]}
                  onDrill={openBar("RATIO")}
                  drillDisabled={!drillable}
                  // Very short cycles dominate the mean, p95 and the top of the histogram — worth saying only when they are shown.
                  note={
                    report.ratio.hidden ? undefined : (
                      <Text size="xs" c="dimmed">
                        {t("reports.reportedTime.outlierNote")}
                      </Text>
                    )
                  }
                />
                <DistributionWithAccounting
                  title={t("reports.reportedTime.flowTitle")}
                  caption={t("reports.reportedTime.flowCaption")}
                  distribution={report.flowEfficiency}
                  minSampleSize={report.meta.minSampleSize}
                  format={formatPercent}
                  people={flowPeople}
                  axisLabel={t("reports.reportedTime.flowAxis")}
                  population={report.flowEfficiencyExcluded.population}
                  items={[
                    { label: t("reports.reportedTime.excluded.neverStarted"), count: report.flowEfficiencyExcluded.neverStarted },
                    { label: t("reports.reportedTime.excluded.flowZeroCycle"), count: report.flowEfficiencyExcluded.zeroCycle },
                  ]}
                  onDrill={openBar("FLOW_EFFICIENCY")}
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
              report="reported-time-ratio"
              fetchItems={getReportedTimeItems}
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
    </Stack>
  );
}
