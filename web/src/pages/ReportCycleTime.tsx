import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Alert, SimpleGrid, Stack, Table, Text } from "@mantine/core";
import { IconInfoCircle } from "@tabler/icons-react";
import { getCycleTimeReport, type CycleTimeGroup } from "../api/reports";
import DistributionWithAccounting from "../components/DistributionWithAccounting";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar, { type ReportControls } from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportGroupsTable, { type GroupColumn } from "../components/ReportGroupsTable";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportTabs from "../components/ReportTabs";
import { useReportPage } from "../hooks/useReportPage";
import { cycleTrendRows, trendHasPoints } from "../utils/cycleTimeReport";
import { formatDays, formatMedian } from "../utils/reportFormat";
import { DELIVERY_TABS } from "../utils/reportLinks";

// The trend chart (and with it recharts) rides its own lazy chunk.
const CycleTimeTrendChart = lazy(() => import("../components/CycleTimeTrendChart"));

// Cycle time honours the domain view (delivered in — the default), domain, activity type and work
// category, and trends by week or month.
const CONTROLS: ReportControls = { domainView: "TASK", domain: true, activityType: true, workCategory: true, bucket: true };

const MISSING = "—";

/** Report 7 — cycle time: working days (primary) and elapsed days of finished tasks, plus the per-period trend. */
export default function ReportCycleTime() {
  const { t } = useTranslation();
  // The report travels with the bucket that produced it (the throughput page's rule): a refetch over
  // kept data never labels the old buckets with the new resolution.
  const { filtersQuery, filters, filter, setFilter, query } = useReportPage("cycle-time", async (requested) => ({
    ...(await getCycleTimeReport(requested)),
    bucket: requested.bucket ?? "WEEK",
  }));
  const report = query.data;
  const rows = report ? cycleTrendRows(report.trend, report.bucket) : [];

  const groupColumns: GroupColumn<CycleTimeGroup>[] = [
    { key: "population", header: t("reports.cycleTime.column.population"), render: (g) => g.excluded.population },
    { key: "workingN", header: t("reports.cycleTime.column.workingN"), render: (g) => g.workingDays.n },
    { key: "workingMedian", header: t("reports.cycleTime.column.workingMedian"), render: (g) => formatMedian(g.workingDays, formatDays) },
    { key: "elapsedN", header: t("reports.cycleTime.column.elapsedN"), render: (g) => g.elapsedDays.n },
    { key: "elapsedMedian", header: t("reports.cycleTime.column.elapsedMedian"), render: (g) => formatMedian(g.elapsedDays, formatDays) },
  ];

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.cycleTime.title")} description={t("reports.cycleTime.description")} />
      <ReportTabs tabs={DELIVERY_TABS} />
      <ReportFiltersStatus query={filtersQuery} />
      {filters && (
        <>
          <ReportFilterBar filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          <ReportChartCard
            title={t("reports.cycleTime.title")}
            isPending={query.isPending}
            isRefreshing={query.isPlaceholderData}
            error={query.error}
            empty={report?.excluded.population === 0}
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
                  axisLabel={t("reports.cycleTime.workingAxis")}
                  population={report.excluded.population}
                  items={[{ label: t("reports.cycleTime.excluded.neverStarted"), count: report.excluded.neverStarted }]}
                />
                <DistributionWithAccounting
                  title={t("reports.cycleTime.elapsedTitle")}
                  caption={t("reports.cycleTime.elapsedCaption")}
                  distribution={report.elapsedDays}
                  minSampleSize={report.meta.minSampleSize}
                  format={formatDays}
                  axisLabel={t("reports.cycleTime.elapsedAxis")}
                  population={report.excluded.population}
                  items={[{ label: t("reports.cycleTime.excluded.neverStarted"), count: report.excluded.neverStarted }]}
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
                <Table.ScrollContainer minWidth={320}>
                  <Table verticalSpacing={4} aria-label={t("reports.cycleTime.trendTableLabel")}>
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>{t(`reports.cycleTime.column.bucket${report.bucket}`)}</Table.Th>
                        <Table.Th ta="right">{t("reports.cycleTime.column.n")}</Table.Th>
                        <Table.Th ta="right">{t("reports.cycleTime.column.p50")}</Table.Th>
                        <Table.Th ta="right">{t("reports.cycleTime.column.p90")}</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {rows.map((row, index) => (
                        // Buckets are ordered and fixed per response; position is the identity.
                        <Table.Tr key={index}>
                          <Table.Td>{row.label}</Table.Td>
                          <Table.Td ta="right">{row.n}</Table.Td>
                          <Table.Td ta="right">{row.p50 === null ? MISSING : formatDays(row.p50)}</Table.Td>
                          <Table.Td ta="right">{row.p90 === null ? MISSING : formatDays(row.p90)}</Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                </Table.ScrollContainer>
              </Stack>
            </ReportChartCard>
          )}
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
