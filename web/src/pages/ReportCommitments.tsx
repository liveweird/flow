import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Badge, Group, Paper, Stack, Text } from "@mantine/core";
import { IconUsers } from "@tabler/icons-react";
import { getCommitmentsReport, type CommitmentsGroup, type CommitmentsSprint } from "../api/reports";
import EmptyState from "../components/EmptyState";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportGroupsTable, { type GroupColumn } from "../components/ReportGroupsTable";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportSprintsTable, { type SprintColumn } from "../components/ReportSprintsTable";
import ReportTabs from "../components/ReportTabs";
import CommitmentsUserTables from "../components/CommitmentsUserTables";
import { useReportPage } from "../hooks/useReportPage";
import type { ReportControls } from "../utils/reportFilter";
import { formatMd, formatPercent } from "../utils/reportFormat";
import { DELIVERY_TABS } from "../utils/reportLinks";
import { loadFraction, commitmentsChartRows, commitmentsFigureColumns, commitmentsUserChart } from "../utils/commitmentsReport";

// The chart (and with it recharts) rides its own lazy chunk.
const CommitmentsChart = lazy(() => import("../components/CommitmentsChart"));

/**
 * Commitments are read per team (the capacity and the per-person split are team figures): the team is required — never the
 * whole unit — and the member control picks several people. A module-level constant: the hook reads its identity.
 */
const CONTROLS: ReportControls = { teamRequired: true, multiMember: true };

/** The capacity cell: the MD, and a neutral "default" badge when it is the computed default (members × working days) — words, not colour. */
function CapacityCell({ sprint }: { sprint: CommitmentsSprint }) {
  const { t } = useTranslation();
  if (sprint.capacityMd === null) return "—";
  return (
    <Group gap="xs" justify="flex-end" wrap="nowrap">
      <Text span size="sm">
        {formatMd(sprint.capacityMd)}
      </Text>
      {sprint.capacitySource === "DEFAULT" && (
        <Badge color="gray" variant="light" size="sm">
          {t("reports.commitments.defaultBadge")}
        </Badge>
      )}
    </Group>
  );
}

/** Report 1 — commitments: each sprint's committed (initial) scope against its final scope and the team's capacity; with people picked, their shares. */
export default function ReportCommitments() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, setFilter, query, storageKey } = useReportPage(
    "commitments",
    getCommitmentsReport,
    undefined,
    CONTROLS,
  );
  const report = query.data;
  // The shown report decides the mode (not the filter): a refetch over kept data never mixes the two shapes.
  const userMode = report?.meta.level === "USER";
  const noTeams = filters !== undefined && filters.teams.length === 0;

  const groupColumns: GroupColumn<CommitmentsGroup>[] = commitmentsFigureColumns<CommitmentsGroup>(t, (group) => group);
  const sprintColumns: SprintColumn<CommitmentsSprint>[] = [
    ...commitmentsFigureColumns<CommitmentsSprint>(t, (sprint) => sprint),
    ...(userMode
      ? []
      : [
          { key: "capacityMd", header: t("reports.commitments.column.capacityMd"), render: (sprint: CommitmentsSprint) => <CapacityCell sprint={sprint} /> },
          {
            key: "load",
            header: t("reports.commitments.column.load"),
            render: (sprint: CommitmentsSprint) => {
              const load = loadFraction(sprint.initialMd, sprint.capacityMd);
              return load === null ? "—" : formatPercent(load);
            },
          },
        ]),
  ];

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.commitments.title")} description={t("reports.commitments.description")} />
      <ReportTabs tabs={DELIVERY_TABS} />
      <ReportFiltersStatus query={filtersQuery} />
      {noTeams && (
        <Paper withBorder p="md">
          <EmptyState icon={IconUsers} label={t("reports.commitments.noTeams")} />
        </Paper>
      )}
      {filters && !noTeams && (
        <>
          <ReportFilterBar storageKey={storageKey} filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          <ReportChartCard
            title={t(userMode ? "reports.commitments.chartTitleUsers" : "reports.commitments.chartTitle")}
            isPending={query.isPending}
            isRefreshing={query.isPlaceholderData}
            error={query.error}
            empty={report?.sprints.length === 0}
          >
            {report && (
              <Stack gap="md">
                <Suspense fallback={<LoadingBlock />}>
                  {userMode ? (
                    <CommitmentsChart mode="user" {...commitmentsUserChart(report.sprints, filters)} />
                  ) : (
                    <CommitmentsChart mode="team" rows={commitmentsChartRows(report.sprints, filters)} />
                  )}
                </Suspense>
                <ReportSprintsTable
                  sprints={report.sprints}
                  filters={filters}
                  columns={sprintColumns}
                  minWidth={userMode ? 760 : 960}
                  driftDetail={(sprint) =>
                    sprint.snapshot &&
                    t("reports.commitments.snapshot", {
                      initialMd: formatMd(sprint.snapshot.initialMd),
                      finalMd: formatMd(sprint.snapshot.finalMd),
                    })
                  }
                />
                {!userMode && report.sprints.some((sprint) => sprint.capacityMd !== null) && (
                  <Text size="xs" c="dimmed">
                    {t("reports.commitments.loadNote")}
                  </Text>
                )}
              </Stack>
            )}
          </ReportChartCard>
          {report &&
            (userMode ? (
              <CommitmentsUserTables sprints={report.sprints} groups={report.groups} filters={filters} />
            ) : (
              <ReportGroupsTable level={report.meta.level} filters={filters} groups={report.groups} columns={groupColumns} />
            ))}
        </>
      )}
    </Stack>
  );
}
