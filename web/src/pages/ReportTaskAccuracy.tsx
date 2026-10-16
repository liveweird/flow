import { useTranslation } from "react-i18next";
import { Paper, SimpleGrid, Stack, Text } from "@mantine/core";
import { IconUsers } from "@tabler/icons-react";
import { getTaskEstimationAccuracyReport, type TaskAccuracyGroup } from "../api/reports";
import DistributionWithAccounting from "../components/DistributionWithAccounting";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar, { type ReportControls } from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportGroupsTable, { type GroupColumn } from "../components/ReportGroupsTable";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportPersonTotals from "../components/ReportPersonTotals";
import ReportTailControl from "../components/ReportTailControl";
import ReportTabs from "../components/ReportTabs";
import { useReportPage } from "../hooks/useReportPage";
import { distributionPeople } from "../utils/distributionPeople";
import { formatMedian, formatRatio } from "../utils/reportFormat";
import { ESTIMATION_TABS } from "../utils/reportLinks";

/**
 * Task accuracy is read per team — never the whole unit — and the member control picks several people, whose two histograms stack
 * per person (the percentile strip, the accuracy measures and the accounting stay the whole selection). It honours the domain view
 * (delivered in — the task's own domain, the default), domain, activity type and work category. A module-level constant: the hook
 * reads its identity.
 */
const CONTROLS: ReportControls = {
  teamRequired: true,
  multiMember: true,
  domainView: "TASK",
  domain: true,
  activityType: true,
  workCategory: true,
};

/** Report 3 — task estimation accuracy: actual ÷ estimate for finished tasks, at start (primary) and at done. */
export default function ReportTaskAccuracy() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, setFilter, query, storageKey } = useReportPage(
    "task-estimation-accuracy",
    getTaskEstimationAccuracyReport,
    undefined,
    CONTROLS,
  );
  const report = query.data;
  // The shown report decides the mode (not the filter): a refetch over kept data never mixes the two shapes.
  const userMode = report?.meta.level === "USER";
  const noTeams = filters !== undefined && filters.teams.length === 0;
  // Each histogram's own split numbers the people (request order); a hidden distribution has none, so the other one may.
  const startPeople = report ? distributionPeople(report.atStart) : [];
  const donePeople = report ? distributionPeople(report.atDone) : [];
  const totalsPeople = startPeople.length > 0 ? startPeople : donePeople;

  const groupColumns: GroupColumn<TaskAccuracyGroup>[] = [
    { key: "population", header: t("reports.taskAccuracy.column.population"), render: (g) => g.excluded.population },
    { key: "startN", header: t("reports.taskAccuracy.column.startN"), render: (g) => g.atStart.n },
    { key: "startMedian", header: t("reports.taskAccuracy.column.startMedian"), render: (g) => formatMedian(g.atStart, formatRatio) },
    { key: "doneN", header: t("reports.taskAccuracy.column.doneN"), render: (g) => g.atDone.n },
    { key: "doneMedian", header: t("reports.taskAccuracy.column.doneMedian"), render: (g) => formatMedian(g.atDone, formatRatio) },
  ];

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.taskAccuracy.title")} description={t("reports.taskAccuracy.description")} />
      <ReportTabs tabs={ESTIMATION_TABS} />
      <ReportFiltersStatus query={filtersQuery} />
      {noTeams && (
        <Paper withBorder p="md">
          <EmptyState icon={IconUsers} label={t("reports.taskAccuracy.noTeams")} />
        </Paper>
      )}
      {filters && !noTeams && (
        <>
          <ReportFilterBar storageKey={storageKey} filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          <ReportTailControl filter={filter} onChange={setFilter} />
          <ReportChartCard
            title={t("reports.taskAccuracy.title")}
            caption={t("reports.ratio.hint")}
            isPending={query.isPending}
            isRefreshing={query.isPlaceholderData}
            error={query.error}
            empty={report?.excluded.population === 0}
          >
            {report && (
              <SimpleGrid cols={{ base: 1, md: 2 }} spacing="lg">
                {/* Each view carries ITS OWN accounting: the two partitions differ, so they never share a list. */}
                <DistributionWithAccounting
                  title={t("reports.taskAccuracy.startTitle")}
                  caption={t("reports.taskAccuracy.startCaption")}
                  distribution={report.atStart}
                  minSampleSize={report.meta.minSampleSize}
                  format={formatRatio}
                  strip="centre"
                  accuracy={report.accuracyAtStart}
                  people={startPeople}
                  axisLabel={t("reports.ratio.axis")}
                  population={report.excluded.population}
                  items={[
                    { label: t("reports.taskAccuracy.excluded.noWorklogs"), count: report.excluded.noWorklogs },
                    { label: t("reports.taskAccuracy.excluded.neverStarted"), count: report.excluded.neverStarted },
                    { label: t("reports.taskAccuracy.excluded.unestimatedAtStart"), count: report.excluded.unestimatedAtStart },
                  ]}
                />
                <DistributionWithAccounting
                  title={t("reports.taskAccuracy.doneTitle")}
                  caption={t("reports.taskAccuracy.doneCaption")}
                  distribution={report.atDone}
                  minSampleSize={report.meta.minSampleSize}
                  format={formatRatio}
                  strip="centre"
                  accuracy={report.accuracyAtDone}
                  people={donePeople}
                  axisLabel={t("reports.ratio.axis")}
                  population={report.excluded.population}
                  items={[
                    { label: t("reports.taskAccuracy.excluded.noWorklogs"), count: report.excluded.noWorklogs },
                    { label: t("reports.taskAccuracy.excluded.unestimatedAtDone"), count: report.excluded.unestimatedAtDone },
                  ]}
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
        </>
      )}
    </Stack>
  );
}
