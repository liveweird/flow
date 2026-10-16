import { useTranslation } from "react-i18next";
import { Paper, SimpleGrid, Stack, Text } from "@mantine/core";
import { IconUsers } from "@tabler/icons-react";
import { getEpicEstimationAccuracyReport, type EpicAccuracyRow } from "../api/reports";
import ColumnTable, { type ColumnDef } from "../components/ColumnTable";
import DistributionWithAccounting from "../components/DistributionWithAccounting";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar, { type ReportControls } from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportTailControl from "../components/ReportTailControl";
import ReportTabs from "../components/ReportTabs";
import { useReportPage } from "../hooks/useReportPage";
import { formatDate } from "../utils/formatDate";
import { dropMember } from "../utils/reportFilter";
import { formatMd, formatRatio } from "../utils/reportFormat";
import { ESTIMATION_TABS } from "../utils/reportLinks";
import ScrollRegion from "../components/ScrollRegion";

// Epic accuracy is read per team (the owner team, A19) — never the whole unit — and an epic has no user, so there is no member control.
// An epic's domain is its own space under either domain view and it has no activity type, so those two controls would change
// nothing — only domain and work category slice this report. A module-level constant: the hook reads its identity.
const CONTROLS: ReportControls = { teamRequired: true, noMember: true, domain: true, workCategory: true };

const optional = (value: number | null, format: (n: number) => string) => (value === null ? "—" : format(value));

/** Report 4 — epic estimation accuracy: actual ÷ the epic's OWN estimate, at start (primary) and at done. */
export default function ReportEpicAccuracy() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, setFilter, query, storageKey } = useReportPage(
    "epic-estimation-accuracy",
    getEpicEstimationAccuracyReport,
    dropMember,
    CONTROLS,
  );
  const report = query.data;
  const noTeams = filters !== undefined && filters.teams.length === 0;

  const epicColumns: ColumnDef<EpicAccuracyRow>[] = [
    {
      key: "epic",
      header: t("reports.epicAccuracy.column.epic"),
      render: (epic) => (
        <>
          <Text size="sm" fw={600}>
            {epic.issueKey}
          </Text>
          {epic.summary && (
            <Text size="xs" c="dimmed">
              {epic.summary}
            </Text>
          )}
        </>
      ),
    },
    {
      key: "doneAt",
      header: t("reports.epicAccuracy.column.doneAt"),
      render: (epic) => formatDate(epic.doneAt, "—", filters?.timeZone),
    },
    {
      key: "estimateStart",
      header: t("reports.epicAccuracy.column.estimateStart"),
      render: (epic) => optional(epic.ownEstimateAtStartMd, formatMd),
      align: "right",
    },
    {
      key: "estimateDone",
      header: t("reports.epicAccuracy.column.estimateDone"),
      render: (epic) => optional(epic.ownEstimateAtDoneMd, formatMd),
      align: "right",
    },
    { key: "childSum", header: t("reports.epicAccuracy.column.childSum"), render: (epic) => formatMd(epic.childSumMd), align: "right" },
    { key: "actual", header: t("reports.epicAccuracy.column.actual"), render: (epic) => formatMd(epic.actualMd), align: "right" },
    {
      key: "ratioStart",
      header: t("reports.epicAccuracy.column.ratioStart"),
      render: (epic) => optional(epic.ratio, formatRatio),
      align: "right",
    },
    {
      key: "ratioDone",
      header: t("reports.epicAccuracy.column.ratioDone"),
      render: (epic) => optional(epic.ratioAtDone, formatRatio),
      align: "right",
    },
  ];

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.epicAccuracy.title")} description={t("reports.epicAccuracy.description")} />
      <ReportTabs tabs={ESTIMATION_TABS} />
      <ReportFiltersStatus query={filtersQuery} />
      {noTeams && (
        <Paper withBorder p="md">
          <EmptyState icon={IconUsers} label={t("reports.epicAccuracy.noTeams")} />
        </Paper>
      )}
      {filters && !noTeams && (
        <>
          <ReportFilterBar storageKey={storageKey} filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          <ReportTailControl filter={filter} onChange={setFilter} />
          <ReportChartCard
            title={t("reports.epicAccuracy.title")}
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
                  title={t("reports.epicAccuracy.startTitle")}
                  caption={t("reports.epicAccuracy.startCaption")}
                  distribution={report.atStart}
                  minSampleSize={report.meta.minSampleSize}
                  format={formatRatio}
                  strip="centre"
                  accuracy={report.accuracyAtStart}
                  axisLabel={t("reports.ratio.axis")}
                  population={report.excluded.population}
                  items={[
                    { label: t("reports.epicAccuracy.excluded.noActual"), count: report.excluded.noActual },
                    { label: t("reports.epicAccuracy.excluded.neverStarted"), count: report.excluded.neverStarted },
                    { label: t("reports.epicAccuracy.excluded.unestimatedAtStart"), count: report.excluded.unestimatedAtStart },
                  ]}
                />
                <DistributionWithAccounting
                  title={t("reports.epicAccuracy.doneTitle")}
                  caption={t("reports.epicAccuracy.doneCaption")}
                  distribution={report.atDone}
                  minSampleSize={report.meta.minSampleSize}
                  format={formatRatio}
                  strip="centre"
                  accuracy={report.accuracyAtDone}
                  axisLabel={t("reports.ratio.axis")}
                  population={report.excluded.population}
                  items={[
                    { label: t("reports.epicAccuracy.excluded.noActual"), count: report.excluded.noActual },
                    { label: t("reports.epicAccuracy.excluded.unestimatedAtDone"), count: report.excluded.unestimatedAtDone },
                  ]}
                />
              </SimpleGrid>
            )}
          </ReportChartCard>
          {report && report.epics.length > 0 && (
            <ReportChartCard
              title={t("reports.epicAccuracy.epicsTitle")}
              caption={report.epicsTruncated ? t("reports.epicAccuracy.epicsTruncated", { count: report.epics.length }) : undefined}
              isPending={false}
              isRefreshing={query.isPlaceholderData}
              empty={false}
            >
              <ScrollRegion label={t("reports.epicAccuracy.epicsTitle")} minWidth={900}>
                <ColumnTable
                  aria-label={t("reports.epicAccuracy.epicsTitle")}
                  columns={epicColumns}
                  rows={report.epics}
                  // The list is ordered and fixed per response; key and position together stay unique across connections.
                  rowKey={(epic, index) => `${epic.issueKey}:${index}`}
                />
              </ScrollRegion>
            </ReportChartCard>
          )}
        </>
      )}
    </Stack>
  );
}
