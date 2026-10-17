import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Badge, Box, Group, SimpleGrid, Stack, Text } from "@mantine/core";
import { getSprintScopeItems } from "../api/reportDrill";
import type { SprintConsistencyReport } from "../api/reports";
import { useReportDrill } from "../hooks/useReportDrill";
import { formatDate } from "../utils/formatDate";
import { overviewTeamRows, type OverviewTeamRow } from "../utils/homeOverview";
import type { SprintBucket } from "../utils/reportDrill";
import { formatMd } from "../utils/reportFormat";
import { homeHitTarget } from "../utils/sprintDrill";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import { drillFigureColumn } from "../utils/drillFigure";
import HomeTile from "./HomeTile";
import LoadingBlock from "./LoadingBlock";
import ScrollRegion from "./ScrollRegion";
import SprintDrillDrawer from "./SprintDrillDrawer";

// The chart (and with it recharts) rides its own lazy chunk.
const HomeCommitmentsChart = lazy(() => import("./HomeCommitmentsChart"));

/** The tile's focus-return anchor for a drill opened by a chart double-click. */
const DRILL_ANCHOR = "home-commitments";

/** Home has no filter bar, so its drill (a sprint of the fixed last-sprint period, whose own team travels with it) is requested with none. */
const NO_FILTER = {};

/** The items the tile shows for a bar of a row — what a drill of it is compared with. */
function barItems(row: OverviewTeamRow, bucket: SprintBucket): number | undefined {
  switch (bucket) {
    case "COMMITTED":
      return row.initialItems;
    case "FINAL":
      return row.finalItems;
    case "DELIVERED":
      return row.deliveredItems;
    default:
      return undefined;
  }
}

/**
 * Tile 1 — each team's last closed sprint: initial and final scope against what it delivered (its velocity)
 * (throughput). ONE request answers it: `sprint-consistency?lastSprints=1` at UNIT level, whose sprint
 * rows carry the committed (= initial), final and delivered figures of the same `fact_sprint` row.
 * A sprint whose live figures moved since it closed (D13) gets the orange "Drift" badge with the
 * frozen figures as text, exactly as on the report pages. The closing day is read in the configured
 * zone (the shared reference data's `timeZone`), like every report date.
 */
export default function HomeCommitmentsTile({
  query,
  to,
  links,
  timeZone,
}: {
  query: { data?: SprintConsistencyReport; isPending: boolean; error: unknown };
  to: string;
  links: ReadonlyArray<{ to: string; label: string }>;
  /** The configured zone the closing day is read in; UTC while the reference data is pending or failed. */
  timeZone?: string;
}) {
  const { t } = useTranslation();
  const drill = useReportDrill("home", DRILL_ANCHOR);
  const rows = query.data ? overviewTeamRows(query.data) : [];
  // A figure of the row stands behind the bar it draws: Initial = COMMITTED, Final, Delivered. Its label names team, sprint and bar.
  const figure = (column: ColumnDef<OverviewTeamRow>, bucket: SprintBucket): ColumnDef<OverviewTeamRow> =>
    drillFigureColumn(column, {
      label: (row, figure, column) =>
        t("reports.drill.figureAria", { figure, column, name: `${row.team} · ${row.sprint} · ${t(`reports.drill.bucket.${bucket}`)}` }),
      count: (row) => barItems(row, bucket) ?? 0,
      onOpen: (row, trigger) => drill.open({ kind: "sprint", teamId: row.teamId, sprintId: row.sprintId, bucket }, { trigger }),
    });
  const columns: ColumnDef<OverviewTeamRow>[] = [
    { key: "team", header: t("home.commitments.column.team"), render: (row) => row.team },
    { key: "sprint", header: t("home.commitments.column.sprint"), render: (row) => row.sprint },
    {
      key: "closed",
      header: t("home.commitments.column.closed"),
      render: (row) => formatDate(row.completedAt, t("reports.sprints.open"), timeZone),
    },
    figure({ key: "initial", header: t("home.commitments.column.initial"), render: (row) => formatMd(row.initialMd), align: "right" }, "COMMITTED"),
    figure({ key: "final", header: t("home.commitments.column.final"), render: (row) => formatMd(row.finalMd), align: "right" }, "FINAL"),
    figure(
      { key: "delivered", header: t("home.commitments.column.delivered"), render: (row) => formatMd(row.deliveredMd), align: "right" },
      "DELIVERED",
    ),
    {
      key: "drift",
      header: t("reports.sprints.drift"),
      render: (row) =>
        row.drift &&
        row.frozen && (
          <Group gap="xs" wrap="nowrap">
            <Badge color="orange" variant="light">
              {t("reports.sprints.driftBadge")}
            </Badge>
            <Text size="xs" c="dimmed">
              {t("reports.sprintConsistency.snapshot", {
                committed: formatMd(row.frozen.committedMd),
                final: formatMd(row.frozen.finalMd),
                delivered: formatMd(row.frozen.deliveredMd),
              })}
            </Text>
          </Group>
        ),
    },
  ];
  return (
    <HomeTile
      title={t("home.commitments.title")}
      to={to}
      caption={t("home.commitments.caption")}
      isPending={query.isPending}
      error={query.error}
      links={links}
    >
      {rows.length === 0 ? (
        <Text size="sm" c="dimmed">
          {t("home.commitments.empty")}
        </Text>
      ) : (
        <Stack gap="xs">
          <Box tabIndex={-1} data-drill-anchor={DRILL_ANCHOR}>
            <SimpleGrid cols={{ base: 1, md: 2 }} spacing="lg">
              <Suspense fallback={<LoadingBlock />}>
                <HomeCommitmentsChart
                  rows={rows}
                  onBarDoubleClick={(hit) => {
                    const target = homeHitTarget(hit);
                    if (target) drill.open(target);
                  }}
                />
              </Suspense>
              <ScrollRegion label={t("home.commitments.tableLabel")} minWidth={620}>
                <ColumnTable
                  verticalSpacing={4}
                  aria-label={t("home.commitments.tableLabel")}
                  columns={columns}
                  rows={rows}
                  rowKey={(row) => row.key}
                />
              </ScrollRegion>
            </SimpleGrid>
          </Box>
          <Text size="xs" c="dimmed">
            {t("reports.drill.hint")}
          </Text>
        </Stack>
      )}
      <SprintDrillDrawer
        opened={drill.target !== null}
        target={drill.shown}
        filter={NO_FILTER}
        scope="sprint-consistency"
        fetchItems={getSprintScopeItems}
        sprintLabel={(sprint) => {
          const row = rows.find((candidate) => candidate.id === `${sprint.teamId}:${sprint.sprintId}`);
          return row && `${row.team} · ${row.sprint}`;
        }}
        chartCount={(target) => {
          const row = rows.find((candidate) => candidate.id === `${target.teamId}:${target.sprintId}`);
          return row && barItems(row, target.bucket);
        }}
        timeZone={timeZone ?? "UTC"}
        onClose={drill.close}
        onSegmentChange={drill.setSegment}
      />
    </HomeTile>
  );
}
