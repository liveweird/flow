import { lazy, Suspense, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Stack } from "@mantine/core";
import { getSprintConsistencyReport, type SprintConsistencyGroup, type SprintConsistencySprint } from "../api/reports";
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
import { sprintConsistencyRows } from "../utils/sprintConsistencyReport";

// The charts (and with them recharts) ride their own lazy chunk.
const SprintConsistencyChart = lazy(() => import("../components/SprintConsistencyChart"));

/** The seven bucket figures of a sprint (or of a group's sum), each "MD (items)" — the full table. */
const FIGURES = ["committed", "added", "removed", "final", "delivered", "carriedOver", "dropped"] as const;
type Figure = (typeof FIGURES)[number];
type FigureRow = { [K in Figure as `${K}Md`]: number } & { [K in Figure as `${K}Items`]: number };

const mdItems = (row: FigureRow, figure: Figure): ReactNode => `${formatMd(row[`${figure}Md`])} (${row[`${figure}Items`]})`;

/** Reports 6.1–6.3 — sprint consistency: how a sprint's scope moved and where its final scope ended. */
export default function ReportSprintConsistency() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, setFilter, query } = useReportPage("sprint-consistency", getSprintConsistencyReport);
  const report = query.data;

  const groupColumns: GroupColumn<SprintConsistencyGroup>[] = FIGURES.map((figure) => ({
    key: figure,
    header: t(`reports.sprintConsistency.column.${figure}`),
    render: (group) => mdItems(group, figure),
  }));
  const sprintColumns: SprintColumn<SprintConsistencySprint>[] = FIGURES.map((figure) => ({
    key: figure,
    header: t(`reports.sprintConsistency.column.${figure}`),
    render: (sprint) => mdItems(sprint, figure),
  }));

  // While there is no report to show (first load, a failure, an empty period) ONE card carries that
  // state — four cards saying "No data in this period" would be noise.
  const showing = report !== undefined && report.sprints.length > 0 && !query.isError;
  const card = (title: string, caption: string | undefined, children: ReactNode) => (
    <ReportChartCard
      title={title}
      caption={caption}
      isPending={false}
      isRefreshing={query.isPlaceholderData}
      empty={false}
    >
      {children}
    </ReportChartCard>
  );
  const chart = (kind: "scope" | "partition" | "changes") =>
    report &&
    filters && (
      <Suspense fallback={<LoadingBlock />}>
        <SprintConsistencyChart kind={kind} rows={sprintConsistencyRows(report.sprints, filters)} />
      </Suspense>
    );

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.sprintConsistency.title")} description={t("reports.sprintConsistency.description")} />
      <ReportTabs tabs={DELIVERY_TABS} />
      <ReportFiltersStatus query={filtersQuery} />
      {filters && (
        <>
          <ReportFilterBar filters={filters} filter={filter} onChange={setFilter} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          {showing ? (
            <>
              {card(t("reports.sprintConsistency.scopeTitle"), undefined, chart("scope"))}
              {card(
                t("reports.sprintConsistency.partitionTitle"),
                t("reports.sprintConsistency.partitionCaption"),
                chart("partition"),
              )}
              {card(t("reports.sprintConsistency.changesTitle"), undefined, chart("changes"))}
              {card(
                t("reports.sprintConsistency.tableTitle"),
                t("reports.sprintConsistency.tableCaption"),
                <ReportSprintsTable
                  sprints={report.sprints}
                  filters={filters}
                  columns={sprintColumns}
                  minWidth={1100}
                  driftDetail={(sprint) =>
                    sprint.snapshot &&
                    t("reports.sprintConsistency.snapshot", {
                      committed: formatMd(sprint.snapshot.committedMd),
                      final: formatMd(sprint.snapshot.finalMd),
                      delivered: formatMd(sprint.snapshot.deliveredMd),
                    })
                  }
                />,
              )}
            </>
          ) : (
            <ReportChartCard
              title={t("reports.sprintConsistency.scopeTitle")}
              isPending={query.isPending}
              error={query.error}
              empty
            >
              {null}
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
