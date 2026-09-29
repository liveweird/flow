import { useTranslation } from "react-i18next";
import { SimpleGrid, Stack, Text } from "@mantine/core";
import { getReportedTimeRatioReport, type ReportedTimeGroup } from "../api/reports";
import DistributionWithAccounting from "../components/DistributionWithAccounting";
import PageHeader from "../components/PageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar, { type ReportControls } from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportGroupsTable, { type GroupColumn } from "../components/ReportGroupsTable";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportTabs from "../components/ReportTabs";
import { useReportPage } from "../hooks/useReportPage";
import { formatMedian, formatPercent, formatRatio } from "../utils/reportFormat";
import { ESTIMATION_TABS } from "../utils/reportLinks";

// Reported time honours the domain view (delivered in — the default), domain, activity type and work category.
const CONTROLS: ReportControls = { domainView: "TASK", domain: true, activityType: true, workCategory: true };

/** Report 8 — reported time ÷ cycle time, with flow efficiency (A18) beside it. */
export default function ReportReportedTime() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, setFilter, query } = useReportPage("reported-time-ratio", getReportedTimeRatioReport);
  const report = query.data;

  const groupColumns: GroupColumn<ReportedTimeGroup>[] = [
    { key: "population", header: t("reports.reportedTime.column.population"), render: (g) => g.excluded.population },
    { key: "ratioN", header: t("reports.reportedTime.column.ratioN"), render: (g) => g.ratio.n },
    { key: "ratioMedian", header: t("reports.reportedTime.column.ratioMedian"), render: (g) => formatMedian(g.ratio, formatRatio) },
    { key: "flowN", header: t("reports.reportedTime.column.flowN"), render: (g) => g.flowEfficiency.n },
    { key: "flowMedian", header: t("reports.reportedTime.column.flowMedian"), render: (g) => formatMedian(g.flowEfficiency, formatPercent) },
  ];

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.reportedTime.title")} description={t("reports.reportedTime.description")} />
      <ReportTabs tabs={ESTIMATION_TABS} />
      <ReportFiltersStatus query={filtersQuery} />
      {filters && (
        <>
          <ReportFilterBar filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          <ReportChartCard
            title={t("reports.reportedTime.title")}
            isPending={query.isPending}
            isRefreshing={query.isPlaceholderData}
            error={query.error}
            empty={report?.excluded.population === 0}
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
                  axisLabel={t("reports.reportedTime.ratioAxis")}
                  population={report.excluded.population}
                  items={[
                    { label: t("reports.reportedTime.excluded.noWorklogs"), count: report.excluded.noWorklogs },
                    { label: t("reports.reportedTime.excluded.neverStarted"), count: report.excluded.neverStarted },
                    { label: t("reports.reportedTime.excluded.zeroCycle"), count: report.excluded.zeroCycle },
                  ]}
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
                  axisLabel={t("reports.reportedTime.flowAxis")}
                  population={report.flowEfficiencyExcluded.population}
                  items={[
                    { label: t("reports.reportedTime.excluded.neverStarted"), count: report.flowEfficiencyExcluded.neverStarted },
                    { label: t("reports.reportedTime.excluded.flowZeroCycle"), count: report.flowEfficiencyExcluded.zeroCycle },
                  ]}
                />
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
