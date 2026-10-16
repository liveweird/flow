import { useTranslation } from "react-i18next";
import { Paper, SimpleGrid, Stack, Text, Title } from "@mantine/core";
import { IconUsers } from "@tabler/icons-react";
import { getEstimateAdjustmentsReport, type AdjustmentFigures, type EstimateAdjustmentsGroup } from "../api/reports";
import AdjustmentsBlock from "../components/AdjustmentsBlock";
import EmptyState from "../components/EmptyState";
import EpicsPerPersonNote from "../components/EpicsPerPersonNote";
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
import { formatPercent } from "../utils/reportFormat";
import { ESTIMATION_TABS } from "../utils/reportLinks";

/**
 * Adjustments are read per team — never the whole unit — and the member control picks several people, whose change histogram
 * stacks per person (the epics stay a team figure). Tasks honour the domain view, domain, activity type and work category; epics
 * read their own space. A module-level constant: the hook reads its identity.
 */
const CONTROLS: ReportControls = {
  teamRequired: true,
  multiMember: true,
  domainView: "TASK",
  domain: true,
  activityType: true,
  workCategory: true,
};

/** A share, or a dash when withheld below the minimum sample (or when the kind is absent at this level). */
const shareCell = (figures: AdjustmentFigures | null) =>
  figures === null || figures.share === null ? "—" : formatPercent(figures.share);

/** Report 5 — estimate adjustments: how often and how much estimates change, for tasks and for epics. */
export default function ReportEstimateAdjustments() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, setFilter, query, storageKey } = useReportPage(
    "estimate-adjustments",
    getEstimateAdjustmentsReport,
    undefined,
    CONTROLS,
  );
  const report = query.data;
  // The shown report decides the mode (not the filter): a refetch over kept data never mixes the two shapes.
  const userMode = report?.meta.level === "USER";
  const noTeams = filters !== undefined && filters.teams.length === 0;
  const people = report ? distributionPeople(report.tasks.changeDistribution) : [];

  const groupColumns: GroupColumn<EstimateAdjustmentsGroup>[] = [
    { key: "tasksStarted", header: t("reports.adjustments.column.tasksStarted"), render: (g) => g.tasks.started },
    { key: "tasksShare", header: t("reports.adjustments.column.tasksShare"), render: (g) => shareCell(g.tasks) },
    { key: "epicsStarted", header: t("reports.adjustments.column.epicsStarted"), render: (g) => g.epics?.started ?? "—" },
    { key: "epicsShare", header: t("reports.adjustments.column.epicsShare"), render: (g) => shareCell(g.epics) },
  ];
  // Nothing started and nothing finished for either kind: an empty period, not two blocks of zeros.
  const empty =
    report !== undefined &&
    report.tasks.started === 0 &&
    report.tasks.changeExcluded.population === 0 &&
    (report.meta.level === "USER" || (report.epics.started === 0 && report.epics.changeExcluded.population === 0));

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.adjustments.title")} description={t("reports.adjustments.description")} />
      <ReportTabs tabs={ESTIMATION_TABS} />
      <ReportFiltersStatus query={filtersQuery} />
      {noTeams && (
        <Paper withBorder p="md">
          <EmptyState icon={IconUsers} label={t("reports.adjustments.noTeams")} />
        </Paper>
      )}
      {filters && !noTeams && (
        <>
          <ReportFilterBar storageKey={storageKey} filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
          <Text size="xs" c="dimmed">
            {t("reports.adjustments.domainViewNote")}
          </Text>
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          <ReportTailControl filter={filter} onChange={setFilter} />
          <ReportChartCard
            title={t("reports.adjustments.title")}
            isPending={query.isPending}
            isRefreshing={query.isPlaceholderData}
            error={query.error}
            empty={empty}
          >
            {report && (
              <SimpleGrid cols={{ base: 1, lg: 2 }} spacing="xl">
                <AdjustmentsBlock
                  title={t("reports.adjustments.tasksTitle")}
                  figures={report.tasks}
                  minSampleSize={report.meta.minSampleSize}
                  people={people}
                />
                {userMode ? (
                  <Stack gap="md">
                    <Title order={4} size="h5">
                      {t("reports.adjustments.epicsTitle")}
                    </Title>
                    <EpicsPerPersonNote />
                  </Stack>
                ) : (
                  <AdjustmentsBlock
                    title={t("reports.adjustments.epicsTitle")}
                    figures={report.epics}
                    minSampleSize={report.meta.minSampleSize}
                  />
                )}
              </SimpleGrid>
            )}
          </ReportChartCard>
          {report && report.groups.length > 0 && (
            <Stack gap="xs">
              {userMode ? (
                <ReportPersonTotals groups={report.groups} people={people} columns={groupColumns} />
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
