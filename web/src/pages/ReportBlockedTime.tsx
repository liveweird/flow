import { useTranslation } from "react-i18next";
import { Paper, SimpleGrid, Stack, Text } from "@mantine/core";
import { IconUsers } from "@tabler/icons-react";
import { getBlockedTimeItems } from "../api/reportDrill";
import { getBlockedTimeReport, type BlockedGroup } from "../api/reports";
import BlockedTopItemsTable from "../components/BlockedTopItemsTable";
import DistributionDrillDrawer, { type DistributionDrillView } from "../components/DistributionDrillDrawer";
import DistributionWithAccounting from "../components/DistributionWithAccounting";
import EmptyState from "../components/EmptyState";
import ReportPageHeader from "../components/ReportPageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar, { type ReportControls } from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportGroupsTable, { type GroupColumn } from "../components/ReportGroupsTable";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportTailControl from "../components/ReportTailControl";
import ReportTabs from "../components/ReportTabs";
import { useReportDrill } from "../hooks/useReportDrill";
import { useReportPage } from "../hooks/useReportPage";
import { BLOCKED_ITEM_KINDS, reportQuery } from "../utils/reportFilter";
import { formatDays, formatMedian, formatPercent } from "../utils/reportFormat";
import { FLOW_TABS } from "../utils/reportLinks";

// Blocked time slices by domain, activity type and work category, counts tasks, epics or both, and is read per team — never the whole unit
// (`teamRequired`; a domain may still be picked beside the team).
const CONTROLS: ReportControls = { teamRequired: true, domain: true, activityType: true, workCategory: true, itemKind: BLOCKED_ITEM_KINDS };

/** The card's focus-return anchor for a drill opened by a chart double-click (`ReportChartCard`'s `drillAnchor`). */
const DRILL_ANCHOR = "blocked-time";

/** Report 12 — blocked time: working days and share of cycle that finished items spent blocked. */
export default function ReportBlockedTime() {
  const { t } = useTranslation();
  // The item kind is always sent explicitly (the server's default, TASK, made visible).
  const { filtersQuery, filters, filter, setFilter, query, storageKey } = useReportPage(
    "blocked-time",
    (requested) => getBlockedTimeReport({ ...requested, itemKind: requested.itemKind ?? "TASK" }),
    undefined,
    CONTROLS,
  );
  const drill = useReportDrill(reportQuery(filter), DRILL_ANCHOR);
  const report = query.data;
  // A bar of kept (placeholder) data belongs to the PREVIOUS filter: it must not open a drill under the new one.
  const drillable = !query.isPlaceholderData;
  const noTeams = filters !== undefined && filters.teams.length === 0;
  // The histograms carry no per-person split, so no drill carries a segment.
  const openBar = (view: string) => (bucketIndex: number, _segmentAccountId?: string, trigger?: HTMLElement) =>
    drill.open({ kind: "hist", view, bucketIndex }, { trigger });
  const drillViews: DistributionDrillView[] = report
    ? [
        {
          view: "BLOCKED_WORKING_DAYS",
          title: t("reports.blockedTime.daysTitle"),
          distribution: report.blockedWorkingDays,
          people: [],
          format: formatDays,
          unit: "blockedWd",
        },
        {
          view: "SHARE_OF_CYCLE",
          title: t("reports.blockedTime.shareTitle"),
          distribution: report.shareOfCycle,
          people: [],
          format: formatPercent,
          unit: "blockedShare",
        },
      ]
    : [];

  const groupColumns: GroupColumn<BlockedGroup>[] = [
    { key: "population", header: t("reports.blockedTime.column.population"), render: (g) => g.excluded.population },
    { key: "blockedItems", header: t("reports.blockedTime.column.blockedItems"), render: (g) => g.blockedItems },
    { key: "daysMedian", header: t("reports.blockedTime.column.daysMedian"), render: (g) => formatMedian(g.blockedWorkingDays, formatDays) },
    { key: "shareN", header: t("reports.blockedTime.column.shareN"), render: (g) => g.shareOfCycle.n },
    { key: "shareMedian", header: t("reports.blockedTime.column.shareMedian"), render: (g) => formatMedian(g.shareOfCycle, formatPercent) },
  ];

  return (
    <Stack gap="md">
      <ReportPageHeader title={t("reports.blockedTime.title")} description={t("reports.blockedTime.description")} filter={filter} filters={filters} controls={CONTROLS} tail />
      <ReportTabs tabs={FLOW_TABS} />
      <ReportFiltersStatus query={filtersQuery} />
      {noTeams && (
        <Paper withBorder p="md">
          <EmptyState icon={IconUsers} label={t("reports.blockedTime.noTeams")} />
        </Paper>
      )}
      {filters && !noTeams && (
        <>
          <ReportFilterBar storageKey={storageKey} filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          <ReportTailControl filter={filter} onChange={setFilter} />
          <ReportChartCard
            title={t("reports.blockedTime.title")}
            isPending={query.isPending}
            isRefreshing={query.isPlaceholderData}
            error={query.error}
            empty={report?.excluded.population === 0}
            drillAnchor={DRILL_ANCHOR}
          >
            {report && (
              <SimpleGrid cols={{ base: 1, md: 2 }} spacing="lg">
                {/* Both distributions cover the blocked items only: the never-blocked ones are the first reason, the share also leaves out a blocked item without a cycle. */}
                <DistributionWithAccounting
                  title={t("reports.blockedTime.daysTitle")}
                  caption={t("reports.blockedTime.daysCaption")}
                  distribution={report.blockedWorkingDays}
                  minSampleSize={report.meta.minSampleSize}
                  format={formatDays}
                  axisLabel={t("reports.blockedTime.daysAxis")}
                  population={report.excluded.population}
                  items={[{ label: t("reports.blockedTime.excluded.neverBlocked"), count: report.excluded.neverBlocked }]}
                  onDrill={openBar("BLOCKED_WORKING_DAYS")}
                  drillDisabled={!drillable}
                  note={
                    <Text size="sm">
                      {t("reports.blockedTime.neverBlocked", { count: report.excluded.neverBlocked, total: report.excluded.population })}
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
                    { label: t("reports.blockedTime.excluded.neverBlocked"), count: report.excluded.neverBlocked },
                    { label: t("reports.blockedTime.excluded.neverStarted"), count: report.excluded.neverStarted },
                    { label: t("reports.blockedTime.excluded.zeroCycle"), count: report.excluded.zeroCycle },
                  ]}
                  onDrill={openBar("SHARE_OF_CYCLE")}
                  drillDisabled={!drillable}
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
          {report && (
            <DistributionDrillDrawer
              report="blocked-time"
              fetchItems={getBlockedTimeItems}
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
