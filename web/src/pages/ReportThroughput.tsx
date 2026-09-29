import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Stack, Text } from "@mantine/core";
import { getThroughputReport, type ThroughputGroup, type ThroughputSprint } from "../api/reports";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar, { type ReportControls } from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportGroupsTable, { type GroupColumn } from "../components/ReportGroupsTable";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportSprintsTable, { type SprintColumn } from "../components/ReportSprintsTable";
import ReportTabs from "../components/ReportTabs";
import ThroughputBucketTable from "../components/ThroughputBucketTable";
import { useReportPage } from "../hooks/useReportPage";
import { formatMd } from "../utils/reportFormat";
import { DELIVERY_TABS } from "../utils/reportLinks";
import { throughputChartRows, throughputTotals } from "../utils/throughputReport";

// The chart (and with it recharts) rides its own lazy chunk.
const ThroughputChart = lazy(() => import("../components/ThroughputChart"));

// Throughput's period view honours the domain view (delivered in — the task's own domain, the
// default — or earned in), domain, activity type and work category, and buckets by week or month.
const CONTROLS: ReportControls = { domainView: "TASK", domain: true, activityType: true, workCategory: true, bucket: true };

/** Report 2 — throughput: what was delivered, per week/month (period view) and per sprint (sprint view). */
export default function ReportThroughput() {
  const { t } = useTranslation();
  // The report travels with the bucket that produced it, so a refetch over kept data (a bucket
  // change in flight) never labels the OLD buckets with the NEW resolution.
  const { filtersQuery, filters, filter, setFilter, query } = useReportPage("throughput", async (requested) => ({
    ...(await getThroughputReport(requested)),
    bucket: requested.bucket ?? "WEEK",
  }));
  const report = query.data;
  const totals = report ? throughputTotals(report.byBucket) : undefined;

  const groupColumns: GroupColumn<ThroughputGroup>[] = [
    { key: "md", header: t("reports.throughput.column.deliveredMd"), render: (g) => formatMd(g.deliveredMd) },
    { key: "items", header: t("reports.throughput.column.deliveredItems"), render: (g) => g.deliveredItems },
  ];
  const sprintColumns: SprintColumn<ThroughputSprint>[] = [
    { key: "md", header: t("reports.throughput.column.deliveredMd"), render: (s) => formatMd(s.deliveredMd) },
    { key: "items", header: t("reports.throughput.column.deliveredItems"), render: (s) => s.deliveredItems },
  ];

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.throughput.title")} description={t("reports.throughput.description")} />
      <ReportTabs tabs={DELIVERY_TABS} />
      <ReportFiltersStatus query={filtersQuery} />
      {filters && (
        <>
          <ReportFilterBar filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          <ReportChartCard
            title={t("reports.throughput.periodTitle")}
            caption={t(`reports.throughput.caption.${filter.domainView ?? "TASK"}`)}
            isPending={query.isPending}
            isRefreshing={query.isPlaceholderData}
            error={query.error}
            empty={totals?.items === 0}
          >
            {report && totals && (
              <Stack gap="xs">
                <Text size="sm">
                  {t("reports.throughput.summary", {
                    md: formatMd(totals.md),
                    items: t("reports.items", { count: totals.items }),
                  })}
                </Text>
                <Suspense fallback={<LoadingBlock />}>
                  <ThroughputChart rows={throughputChartRows(report.byBucket, report.bucket)} />
                </Suspense>
                <ThroughputBucketTable rows={throughputChartRows(report.byBucket, report.bucket)} bucket={report.bucket} />
              </Stack>
            )}
          </ReportChartCard>
          {/* A failure is reported ONCE, by the first card. */}
          {!query.isError && (
            <ReportChartCard
              title={t("reports.throughput.sprintTitle")}
              caption={t("reports.throughput.sprintCaption")}
              isPending={query.isPending}
              isRefreshing={query.isPlaceholderData}
              error={query.error}
              empty={report?.bySprint.length === 0}
            >
              {report && (
                <ReportSprintsTable
                  sprints={report.bySprint}
                  filters={filters}
                  columns={sprintColumns}
                  minWidth={620}
                  driftDetail={(sprint) =>
                    sprint.snapshot &&
                    t("reports.throughput.snapshot", {
                      md: formatMd(sprint.snapshot.deliveredMd),
                      items: t("reports.items", { count: sprint.snapshot.deliveredItems }),
                    })
                  }
                />
              )}
            </ReportChartCard>
          )}
          {report && (
            <ReportGroupsTable level={report.meta.level} filters={filters} groups={report.groups} columns={groupColumns} />
          )}
        </>
      )}
    </Stack>
  );
}
