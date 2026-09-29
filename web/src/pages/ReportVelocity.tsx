import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Stack } from "@mantine/core";
import { getVelocityReport, type VelocityGroup, type VelocitySprint } from "../api/reports";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportGroupsTable, { type GroupColumn } from "../components/ReportGroupsTable";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportSprintsTable, { type SprintColumn } from "../components/ReportSprintsTable";
import ReportTabs from "../components/ReportTabs";
import { useReportPage } from "../hooks/useReportPage";
import { formatMd } from "../utils/reportFormat";
import { DELIVERY_TABS } from "../utils/reportLinks";
import { velocityChartRows } from "../utils/velocityReport";

// The chart (and with it recharts) rides its own lazy chunk.
const VelocityChart = lazy(() => import("../components/VelocityChart"));

/** Report 1 — velocity: each sprint's committed (initial) scope against its final scope. */
export default function ReportVelocity() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, setFilter, query } = useReportPage("velocity", getVelocityReport);
  const report = query.data;

  const figures = [
    { key: "initialMd", header: t("reports.velocity.column.initialMd"), value: (s: VelocityGroup) => formatMd(s.initialMd) },
    { key: "initialItems", header: t("reports.velocity.column.initialItems"), value: (s: VelocityGroup) => s.initialItems },
    { key: "finalMd", header: t("reports.velocity.column.finalMd"), value: (s: VelocityGroup) => formatMd(s.finalMd) },
    { key: "finalItems", header: t("reports.velocity.column.finalItems"), value: (s: VelocityGroup) => s.finalItems },
  ];
  const groupColumns: GroupColumn<VelocityGroup>[] = figures.map(({ key, header, value }) => ({ key, header, render: value }));
  const sprintColumns: SprintColumn<VelocitySprint>[] = figures.map(({ key, header, value }) => ({
    key,
    header,
    render: (sprint) => value(sprint),
  }));

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.velocity.title")} description={t("reports.velocity.description")} />
      <ReportTabs tabs={DELIVERY_TABS} />
      <ReportFiltersStatus query={filtersQuery} />
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
                <ReportSprintsTable
                  sprints={report.sprints}
                  filters={filters}
                  columns={sprintColumns}
                  driftDetail={(sprint) =>
                    sprint.snapshot &&
                    t("reports.velocity.snapshot", {
                      initialMd: formatMd(sprint.snapshot.initialMd),
                      finalMd: formatMd(sprint.snapshot.finalMd),
                    })
                  }
                />
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
