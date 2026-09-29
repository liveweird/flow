import { useTranslation } from "react-i18next";
import { SimpleGrid, Stack, Text } from "@mantine/core";
import { getTaskEstimationAccuracyReport, type TaskAccuracyGroup } from "../api/reports";
import DistributionPanel from "../components/DistributionPanel";
import ExcludedList from "../components/ExcludedList";
import PageHeader from "../components/PageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar, { type ReportControls } from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportGroupsTable, { type GroupColumn } from "../components/ReportGroupsTable";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportTabs from "../components/ReportTabs";
import { useReportPage } from "../hooks/useReportPage";
import { formatMedian, formatRatio } from "../utils/reportFormat";
import { ESTIMATION_TABS } from "../utils/reportLinks";

// Task accuracy honours the domain view (delivered in — the task's own domain, the default), domain,
// activity type and work category.
const CONTROLS: ReportControls = { domainView: "TASK", domain: true, activityType: true, workCategory: true };

/** Report 3 — task estimation accuracy: actual ÷ estimate for finished tasks, at start (primary) and at done. */
export default function ReportTaskAccuracy() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, setFilter, query } = useReportPage("task-estimation-accuracy", getTaskEstimationAccuracyReport);
  const report = query.data;

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
      {filters && (
        <>
          <ReportFilterBar filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
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
                <Stack gap="lg">
                  <DistributionPanel
                    title={t("reports.taskAccuracy.startTitle")}
                    caption={t("reports.taskAccuracy.startCaption")}
                    distribution={report.atStart}
                    minSampleSize={report.meta.minSampleSize}
                    format={formatRatio}
                    axisLabel={t("reports.ratio.axis")}
                  />
                  <ExcludedList
                    population={report.excluded.population}
                    measured={report.atStart.n}
                    items={[
                      { label: t("reports.taskAccuracy.excluded.noWorklogs"), count: report.excluded.noWorklogs },
                      { label: t("reports.taskAccuracy.excluded.neverStarted"), count: report.excluded.neverStarted },
                      { label: t("reports.taskAccuracy.excluded.unestimatedAtStart"), count: report.excluded.unestimatedAtStart },
                    ]}
                  />
                </Stack>
                <Stack gap="lg">
                  <DistributionPanel
                    title={t("reports.taskAccuracy.doneTitle")}
                    caption={t("reports.taskAccuracy.doneCaption")}
                    distribution={report.atDone}
                    minSampleSize={report.meta.minSampleSize}
                    format={formatRatio}
                    axisLabel={t("reports.ratio.axis")}
                  />
                  <ExcludedList
                    population={report.excluded.population}
                    measured={report.atDone.n}
                    items={[
                      { label: t("reports.taskAccuracy.excluded.noWorklogs"), count: report.excluded.noWorklogs },
                      { label: t("reports.taskAccuracy.excluded.unestimatedAtDone"), count: report.excluded.unestimatedAtDone },
                    ]}
                  />
                </Stack>
              </SimpleGrid>
            )}
          </ReportChartCard>
          {report && report.groups.length > 0 && (
            <Stack gap="xs">
              <ReportGroupsTable level={report.meta.level} filters={filters} groups={report.groups} columns={groupColumns} />
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
