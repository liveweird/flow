import { useTranslation } from "react-i18next";
import { SimpleGrid, Stack, Text } from "@mantine/core";
import { getBlockedTimeReport, type BlockedGroup } from "../api/reports";
import BlockedTopItemsTable from "../components/BlockedTopItemsTable";
import DistributionWithAccounting from "../components/DistributionWithAccounting";
import PageHeader from "../components/PageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar, { type ReportControls } from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportGroupsTable, { type GroupColumn } from "../components/ReportGroupsTable";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportTabs from "../components/ReportTabs";
import { useReportPage } from "../hooks/useReportPage";
import { formatDays, formatMedian, formatPercent } from "../utils/reportFormat";
import { FLOW_TABS } from "../utils/reportLinks";

// Blocked time slices by domain, activity type and work category, and counts tasks, epics or both.
const CONTROLS: ReportControls = { domain: true, activityType: true, workCategory: true, itemKind: true };

/** Report 12 — blocked time: working days and share of cycle that finished items spent blocked. */
export default function ReportBlockedTime() {
  const { t } = useTranslation();
  // The item kind is always sent explicitly (the server's default, TASK, made visible).
  const { filtersQuery, filters, filter, setFilter, query } = useReportPage("blocked-time", (requested) =>
    getBlockedTimeReport({ ...requested, itemKind: requested.itemKind ?? "TASK" }),
  );
  const report = query.data;

  const groupColumns: GroupColumn<BlockedGroup>[] = [
    { key: "population", header: t("reports.blockedTime.column.population"), render: (g) => g.excluded.population },
    { key: "blockedItems", header: t("reports.blockedTime.column.blockedItems"), render: (g) => g.blockedItems },
    { key: "daysMedian", header: t("reports.blockedTime.column.daysMedian"), render: (g) => formatMedian(g.blockedWorkingDays, formatDays) },
    { key: "shareN", header: t("reports.blockedTime.column.shareN"), render: (g) => g.shareOfCycle.n },
    { key: "shareMedian", header: t("reports.blockedTime.column.shareMedian"), render: (g) => formatMedian(g.shareOfCycle, formatPercent) },
  ];

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.blockedTime.title")} description={t("reports.blockedTime.description")} />
      <ReportTabs tabs={FLOW_TABS} />
      <ReportFiltersStatus query={filtersQuery} />
      {filters && (
        <>
          <ReportFilterBar filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          <ReportChartCard
            title={t("reports.blockedTime.title")}
            isPending={query.isPending}
            isRefreshing={query.isPlaceholderData}
            error={query.error}
            empty={report?.excluded.population === 0}
          >
            {report && (
              <SimpleGrid cols={{ base: 1, md: 2 }} spacing="lg">
                {/* Every finished item is in the first distribution (zeros included), so it has nothing excluded; the share leaves out items without a cycle. */}
                <DistributionWithAccounting
                  title={t("reports.blockedTime.daysTitle")}
                  caption={t("reports.blockedTime.daysCaption")}
                  distribution={report.blockedWorkingDays}
                  minSampleSize={report.meta.minSampleSize}
                  format={formatDays}
                  axisLabel={t("reports.blockedTime.daysAxis")}
                  population={report.excluded.population}
                  items={[]}
                  note={
                    <Text size="sm">
                      {t("reports.blockedTime.blockedItems", { count: report.blockedItems, total: report.excluded.population })}
                    </Text>
                  }
                />
                <DistributionWithAccounting
                  title={t("reports.blockedTime.shareTitle")}
                  caption={t("reports.blockedTime.shareCaption")}
                  distribution={report.shareOfCycle}
                  minSampleSize={report.meta.minSampleSize}
                  format={formatPercent}
                  axisLabel={t("reports.blockedTime.shareAxis")}
                  population={report.excluded.population}
                  items={[
                    { label: t("reports.blockedTime.excluded.neverStarted"), count: report.excluded.neverStarted },
                    { label: t("reports.blockedTime.excluded.zeroCycle"), count: report.excluded.zeroCycle },
                  ]}
                />
              </SimpleGrid>
            )}
          </ReportChartCard>
          {report && report.excluded.population > 0 && (
            <ReportChartCard
              title={t("reports.blockedTime.topTitle")}
              caption={t("reports.blockedTime.topCaption")}
              isPending={false}
              isRefreshing={query.isPlaceholderData}
              empty={false}
            >
              {report.topItems.length === 0 ? (
                <Text size="sm" c="dimmed">
                  {t("reports.blockedTime.topEmpty")}
                </Text>
              ) : (
                <BlockedTopItemsTable items={report.topItems} filters={filters} />
              )}
            </ReportChartCard>
          )}
          {report && report.groups.length > 0 && (
            <Stack gap="xs">
              <ReportGroupsTable level={report.meta.level} filters={filters} groups={report.groups} columns={groupColumns} />
              <Text size="xs" c="dimmed">
                {t("reports.groupsCaption.distribution")}
              </Text>
              {report.meta.level === "TEAM" && report.itemKind !== "TASK" && (
                <Text size="xs" c="dimmed">
                  {t("reports.blockedTime.groupsTasksOnly")}
                </Text>
              )}
            </Stack>
          )}
        </>
      )}
    </Stack>
  );
}
