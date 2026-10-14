import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Badge, Group, Paper, Stack, Text } from "@mantine/core";
import { IconUsers } from "@tabler/icons-react";
import { getVelocityReport, type VelocityGroup, type VelocitySprint } from "../api/reports";
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
import VelocityUserTables from "../components/VelocityUserTables";
import { useReportPage } from "../hooks/useReportPage";
import type { ReportControls } from "../utils/reportFilter";
import { formatMd, formatPercent } from "../utils/reportFormat";
import { DELIVERY_TABS } from "../utils/reportLinks";
import { loadFraction, velocityChartRows, velocityFigureColumns, velocityUserChart } from "../utils/velocityReport";

// The chart (and with it recharts) rides its own lazy chunk.
const VelocityChart = lazy(() => import("../components/VelocityChart"));

/**
 * Velocity is read per team (the capacity and the per-person split are team figures): the team is required — never the
 * whole unit — and the member control picks several people. A module-level constant: the hook reads its identity.
 */
const CONTROLS: ReportControls = { teamRequired: true, multiMember: true };

/** The capacity cell: the MD, and a neutral "default" badge when it is the computed default (members × working days) — words, not colour. */
function CapacityCell({ sprint }: { sprint: VelocitySprint }) {
  const { t } = useTranslation();
  if (sprint.capacityMd === null) return "—";
  return (
    <Group gap="xs" justify="flex-end" wrap="nowrap">
      <Text span size="sm">
        {formatMd(sprint.capacityMd)}
      </Text>
      {sprint.capacitySource === "DEFAULT" && (
        <Badge color="gray" variant="light" size="sm">
          {t("reports.velocity.defaultBadge")}
        </Badge>
      )}
    </Group>
  );
}

/** Report 1 — velocity: each sprint's committed (initial) scope against its final scope and the team's capacity; with people picked, their shares. */
export default function ReportVelocity() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, setFilter, query, storageKey } = useReportPage(
    "velocity",
    getVelocityReport,
    undefined,
    CONTROLS,
  );
  const report = query.data;
  // The shown report decides the mode (not the filter): a refetch over kept data never mixes the two shapes.
  const userMode = report?.meta.level === "USER";
  const noTeams = filters !== undefined && filters.teams.length === 0;

  const groupColumns: GroupColumn<VelocityGroup>[] = velocityFigureColumns<VelocityGroup>(t, (group) => group);
  const sprintColumns: SprintColumn<VelocitySprint>[] = [
    ...velocityFigureColumns<VelocitySprint>(t, (sprint) => sprint),
    ...(userMode
      ? []
      : [
          { key: "capacityMd", header: t("reports.velocity.column.capacityMd"), render: (sprint: VelocitySprint) => <CapacityCell sprint={sprint} /> },
          {
            key: "load",
            header: t("reports.velocity.column.load"),
            render: (sprint: VelocitySprint) => {
              const load = loadFraction(sprint.initialMd, sprint.capacityMd);
              return load === null ? "—" : formatPercent(load);
            },
          },
        ]),
  ];

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.velocity.title")} description={t("reports.velocity.description")} />
      <ReportTabs tabs={DELIVERY_TABS} />
      <ReportFiltersStatus query={filtersQuery} />
      {noTeams && (
        <Paper withBorder p="md">
          <EmptyState icon={IconUsers} label={t("reports.velocity.noTeams")} />
        </Paper>
      )}
      {filters && !noTeams && (
        <>
          <ReportFilterBar storageKey={storageKey} filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          <ReportChartCard
            title={t(userMode ? "reports.velocity.chartTitleUsers" : "reports.velocity.chartTitle")}
            isPending={query.isPending}
            isRefreshing={query.isPlaceholderData}
            error={query.error}
            empty={report?.sprints.length === 0}
          >
            {report && (
              <Stack gap="md">
                <Suspense fallback={<LoadingBlock />}>
                  {userMode ? (
                    <VelocityChart mode="user" {...velocityUserChart(report.sprints, filters)} />
                  ) : (
                    <VelocityChart mode="team" rows={velocityChartRows(report.sprints, filters)} />
                  )}
                </Suspense>
                <ReportSprintsTable
                  sprints={report.sprints}
                  filters={filters}
                  columns={sprintColumns}
                  minWidth={userMode ? 760 : 960}
                  driftDetail={(sprint) =>
                    sprint.snapshot &&
                    t("reports.velocity.snapshot", {
                      initialMd: formatMd(sprint.snapshot.initialMd),
                      finalMd: formatMd(sprint.snapshot.finalMd),
                    })
                  }
                />
                {!userMode && report.sprints.some((sprint) => sprint.capacityMd !== null) && (
                  <Text size="xs" c="dimmed">
                    {t("reports.velocity.loadNote")}
                  </Text>
                )}
              </Stack>
            )}
          </ReportChartCard>
          {report &&
            (userMode ? (
              <VelocityUserTables sprints={report.sprints} groups={report.groups} filters={filters} />
            ) : (
              <ReportGroupsTable level={report.meta.level} filters={filters} groups={report.groups} columns={groupColumns} />
            ))}
        </>
      )}
    </Stack>
  );
}
