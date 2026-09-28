import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Badge, Group, Stack, Table, Text } from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import {
  getReportFilters,
  getVelocityReport,
  type ReportFilters,
  type VelocityGroup,
  type VelocityReport,
} from "../api/reports";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar from "../components/ReportFilterBar";
import ReportGroupsTable, { type GroupColumn } from "../components/ReportGroupsTable";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportTabs from "../components/ReportTabs";
import { useReportFilter } from "../hooks/useReportFilter";
import { formatDate } from "../utils/formatDate";
import { reportQuery } from "../utils/reportFilter";
import { formatMd } from "../utils/reportFormat";
import { DELIVERY_TABS } from "../utils/reportLinks";
import { loadErrorMessage } from "../utils/saveError";
import { sortSprints, teamNameOf, velocityChartRows } from "../utils/velocityReport";

// The chart (and with it recharts) rides its own lazy chunk.
const VelocityChart = lazy(() => import("../components/VelocityChart"));

function SprintsTable({ report, filters }: { report: VelocityReport; filters: ReportFilters }) {
  const { t } = useTranslation();
  return (
    <Table.ScrollContainer minWidth={760}>
      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t("reports.velocity.column.sprint")}</Table.Th>
            <Table.Th>{t("reports.velocity.column.team")}</Table.Th>
            <Table.Th>{t("reports.velocity.column.completed")}</Table.Th>
            <Table.Th ta="right">{t("reports.velocity.column.initialMd")}</Table.Th>
            <Table.Th ta="right">{t("reports.velocity.column.initialItems")}</Table.Th>
            <Table.Th ta="right">{t("reports.velocity.column.finalMd")}</Table.Th>
            <Table.Th ta="right">{t("reports.velocity.column.finalItems")}</Table.Th>
            <Table.Th>{t("reports.velocity.column.drift")}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {sortSprints(report.sprints).map((sprint) => (
            <Table.Tr key={`${sprint.teamId}:${sprint.sprintId}`}>
              <Table.Td>{sprint.name}</Table.Td>
              <Table.Td>{teamNameOf(filters, sprint.teamId)}</Table.Td>
              <Table.Td>{formatDate(sprint.completedAt, t("reports.velocity.open"), filters.timeZone)}</Table.Td>
              <Table.Td ta="right">{formatMd(sprint.initialMd)}</Table.Td>
              <Table.Td ta="right">{sprint.initialItems}</Table.Td>
              <Table.Td ta="right">{formatMd(sprint.finalMd)}</Table.Td>
              <Table.Td ta="right">{sprint.finalItems}</Table.Td>
              <Table.Td>
                {sprint.drift && sprint.snapshot && (
                  <Group gap="xs" wrap="nowrap">
                    {/* Orange = the soft-finding hue: live figures moved since the sprint closed (D13). */}
                    <Badge color="orange" variant="light">
                      {t("reports.velocity.drift")}
                    </Badge>
                    <Text size="xs" c="dimmed">
                      {t("reports.velocity.snapshot", {
                        initialMd: formatMd(sprint.snapshot.initialMd),
                        finalMd: formatMd(sprint.snapshot.finalMd),
                      })}
                    </Text>
                  </Group>
                )}
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}

/** Report 1 — velocity: each sprint's committed (initial) scope against its final scope. */
export default function ReportVelocity() {
  const { t } = useTranslation();
  const filtersQuery = useQuery({ queryKey: ["reports", "filters"], queryFn: getReportFilters, staleTime: 60_000 });
  const filters = filtersQuery.data;
  const { filter, setFilter } = useReportFilter(filters);
  const query = useQuery({
    queryKey: ["reports", "velocity", reportQuery(filter)],
    queryFn: () => getVelocityReport(filter),
    enabled: filtersQuery.isSuccess,
    placeholderData: keepPreviousData,
  });
  const report = query.data;

  const groupColumns: GroupColumn<VelocityGroup>[] = [
    { key: "initialMd", header: t("reports.velocity.column.initialMd"), render: (g) => formatMd(g.initialMd) },
    { key: "initialItems", header: t("reports.velocity.column.initialItems"), render: (g) => g.initialItems },
    { key: "finalMd", header: t("reports.velocity.column.finalMd"), render: (g) => formatMd(g.finalMd) },
    { key: "finalItems", header: t("reports.velocity.column.finalItems"), render: (g) => g.finalItems },
  ];

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.velocity.title")} description={t("reports.velocity.description")} />
      <ReportTabs tabs={DELIVERY_TABS} />
      {filtersQuery.isError && (
        <Alert color="red" variant="light" role="alert">
          {loadErrorMessage(filtersQuery.error, t)}
        </Alert>
      )}
      {filtersQuery.isPending && <LoadingBlock />}
      {filters && (
        <>
          <ReportFilterBar filters={filters} filter={filter} onChange={setFilter} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          <ReportChartCard
            title={t("reports.velocity.chartTitle")}
            isPending={query.isPending}
            isRefreshing={query.isPlaceholderData}
            error={query.error}
            empty={report?.sprints.length === 0}
          >
            {report && (
              <Stack gap="md">
                <Suspense fallback={<LoadingBlock />}>
                  <VelocityChart rows={velocityChartRows(report.sprints, filters)} />
                </Suspense>
                <SprintsTable report={report} filters={filters} />
              </Stack>
            )}
          </ReportChartCard>
          {report && (
            <ReportGroupsTable level={report.meta.level} filters={filters} groups={report.groups} columns={groupColumns} />
          )}
        </>
      )}
    </Stack>
  );
}
