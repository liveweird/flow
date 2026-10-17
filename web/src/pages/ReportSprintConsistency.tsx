import { lazy, Suspense, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Paper, Stack, Text } from "@mantine/core";
import { IconUsers } from "@tabler/icons-react";
import { getSprintScopeItems } from "../api/reportDrill";
import { getSprintConsistencyReport, type SprintConsistencyGroup, type SprintConsistencySprint } from "../api/reports";
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
import SprintDrillDrawer from "../components/SprintDrillDrawer";
import { useReportDrill } from "../hooks/useReportDrill";
import { useReportPage } from "../hooks/useReportPage";
import type { ChartBarHit } from "../utils/chartDrill";
import type { SprintBucket } from "../utils/reportDrill";
import { dropMember, reportQuery, type ReportControls } from "../utils/reportFilter";
import { formatMd } from "../utils/reportFormat";
import { DELIVERY_TABS } from "../utils/reportLinks";
import { sprintConsistencyRows } from "../utils/sprintConsistencyReport";
import { consistencyDrillCount, consistencyHitTarget, sprintLabeller } from "../utils/sprintDrill";

// The charts (and with them recharts) ride their own lazy chunk.
const SprintConsistencyChart = lazy(() => import("../components/SprintConsistencyChart"));

/** Each chart card's focus-return anchor for a drill opened by a double-click on its bars (`ReportChartCard`'s `drillAnchor`); the table's is the default. */
const DRILL_ANCHOR = { scope: "consistency-scope", partition: "consistency-partition", changes: "consistency-changes", table: "consistency-table" } as const;

/** The seven bucket figures of a sprint (or of a group's sum), each "MD (items)" — the full table. */
const FIGURES = ["committed", "added", "removed", "final", "delivered", "carriedOver", "dropped"] as const;
type Figure = (typeof FIGURES)[number];
/** The sprint bucket each figure stands behind: its column's figure opens that bucket's items. */
const FIGURE_BUCKET: Record<Figure, SprintBucket> = {
  committed: "COMMITTED",
  added: "ADDED",
  removed: "REMOVED",
  final: "FINAL",
  delivered: "DELIVERED",
  carriedOver: "CARRIED_OVER",
  dropped: "DROPPED",
};
type FigureRow = { [K in Figure as `${K}Md`]: number } & { [K in Figure as `${K}Items`]: number };

const mdItems = (row: FigureRow, figure: Figure): ReactNode => `${formatMd(row[`${figure}Md`])} (${row[`${figure}Items`]})`;

/**
 * Sprint consistency is read per team (the required team, no "All teams"), always for every member of it (no member control; a pasted
 * `accountId` is dropped by `dropMember`), and may be sliced by activity type and work category — live, without the frozen snapshot.
 * A module-level constant: the hook reads its identity.
 */
const CONTROLS: ReportControls = { teamRequired: true, noMember: true, activityType: true, workCategory: true };

/** Reports 6.1–6.3 — sprint consistency: how a sprint's scope moved and where its final scope ended. */
export default function ReportSprintConsistency() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, setFilter, query, storageKey } = useReportPage(
    "sprint-consistency",
    getSprintConsistencyReport,
    dropMember,
    CONTROLS,
  );
  const drill = useReportDrill(reportQuery(filter), DRILL_ANCHOR.table);
  const report = query.data;
  // A bar of kept (placeholder) data belongs to the PREVIOUS filter: it must not open a drill under the new one.
  const drillable = !query.isPlaceholderData;
  const noTeams = filters !== undefined && filters.teams.length === 0;
  const sliced = filter.activityType !== undefined || filter.workCategory !== undefined;

  const groupColumns: GroupColumn<SprintConsistencyGroup>[] = FIGURES.map((figure) => ({
    key: figure,
    header: t(`reports.sprintConsistency.column.${figure}`),
    render: (group) => mdItems(group, figure),
  }));
  const sprintColumns: SprintColumn<SprintConsistencySprint>[] = FIGURES.map((figure) => ({
    key: figure,
    header: t(`reports.sprintConsistency.column.${figure}`),
    render: (sprint) => mdItems(sprint, figure),
    drill: { bucket: FIGURE_BUCKET[figure], items: (sprint) => sprint[`${figure}Items`] },
  }));

  // While there is no report to show (first load, a failure, an empty period) ONE card carries that
  // state — four cards saying "No data in this period" would be noise.
  const showing = report !== undefined && report.sprints.length > 0 && !query.isError;
  const card = (title: string, caption: string | undefined, anchor: string, children: ReactNode) => (
    <ReportChartCard
      title={title}
      caption={caption}
      isPending={false}
      isRefreshing={query.isPlaceholderData}
      empty={false}
      hint={t(anchor === DRILL_ANCHOR.table ? "reports.drill.hintRows" : "reports.drill.hint")}
      drillAnchor={anchor}
    >
      {children}
    </ReportChartCard>
  );
  const chart = (kind: "scope" | "partition" | "changes") =>
    report &&
    filters && (
      <Suspense fallback={<LoadingBlock />}>
        <SprintConsistencyChart
          kind={kind}
          rows={sprintConsistencyRows(report.sprints, filters)}
          onBarDoubleClick={
            drillable
              ? (hit: ChartBarHit) => {
                  const target = consistencyHitTarget(hit);
                  if (target) drill.open(target, { anchor: DRILL_ANCHOR[kind] });
                }
              : undefined
          }
        />
      </Suspense>
    );

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.sprintConsistency.title")} description={t("reports.sprintConsistency.description")} />
      <ReportTabs tabs={DELIVERY_TABS} />
      <ReportFiltersStatus query={filtersQuery} />
      {noTeams && (
        <Paper withBorder p="md">
          <EmptyState icon={IconUsers} label={t("reports.sprintConsistency.noTeams")} />
        </Paper>
      )}
      {filters && !noTeams && (
        <>
          <ReportFilterBar storageKey={storageKey} filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          {sliced && (
            <Text size="sm" c="dimmed">
              {t("reports.sprintConsistency.sliceNote")}
            </Text>
          )}
          {showing ? (
            <>
              {card(t("reports.sprintConsistency.scopeTitle"), undefined, DRILL_ANCHOR.scope, chart("scope"))}
              {card(
                t("reports.sprintConsistency.partitionTitle"),
                t("reports.sprintConsistency.partitionCaption"),
                DRILL_ANCHOR.partition,
                chart("partition"),
              )}
              {card(t("reports.sprintConsistency.changesTitle"), undefined, DRILL_ANCHOR.changes, chart("changes"))}
              {card(
                t("reports.sprintConsistency.tableTitle"),
                t("reports.sprintConsistency.tableCaption"),
                DRILL_ANCHOR.table,
                <ReportSprintsTable
                  sprints={report.sprints}
                  filters={filters}
                  columns={sprintColumns}
                  minWidth={1100}
                  drill={{
                    onDrill: (sprint, bucket, trigger) =>
                      drill.open({ kind: "sprint", teamId: sprint.teamId, sprintId: sprint.sprintId, bucket }, { trigger, anchor: DRILL_ANCHOR.table }),
                    disabled: !drillable,
                  }}
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
          <SprintDrillDrawer
            opened={drill.target !== null}
            target={drill.shown}
            filter={filter}
            scope="sprint-consistency"
            fetchItems={getSprintScopeItems}
            sprintLabel={sprintLabeller(report?.sprints, filters)}
            caption={sliced ? t("reports.sprintConsistency.sliceNote") : undefined}
            chartCount={(target) => consistencyDrillCount(report?.sprints, target)}
            timeZone={filters.timeZone}
            onClose={drill.close}
            onSegmentChange={drill.setSegment}
          />
          {report && (
            <ReportGroupsTable level={report.meta.level} filters={filters} groups={report.groups} columns={groupColumns} drill={false} />
          )}
        </>
      )}
    </Stack>
  );
}
