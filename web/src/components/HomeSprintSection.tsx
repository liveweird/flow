import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Badge, Group, Text } from "@mantine/core";
import type { HomeOverview } from "../api/home";
import type { ChartBarHit } from "../utils/chartDrill";
import { drillFigureColumn } from "../utils/drillFigure";
import { formatDate } from "../utils/formatDate";
import { LAST_SPRINT_FILTER, overviewLink, sectionAnchor, sprintBucketItems, sprintRows, type HomeSprintRow, type OpenDrill } from "../utils/homeOverview";
import type { SprintBucket } from "../utils/reportDrill";
import { formatMd } from "../utils/reportFormat";
import { commitmentsPath, sprintConsistencyPath, throughputPath } from "../utils/reportLinks";
import { homeHitTarget } from "../utils/sprintDrill";
import type { ColumnDef } from "./ColumnTable";
import HomeHiddenTable from "./HomeHiddenTable";
import HomeTile from "./HomeTile";
import LoadingBlock from "./LoadingBlock";
import NearViewport from "./NearViewport";

// The chart (and with it recharts) rides its own lazy chunk.
const HomeSprintChart = lazy(() => import("./HomeSprintChart"));

const ANCHOR = sectionAnchor("sprints");

/**
 * Section 1 — each team's last closed sprint, in man-days: capacity, initial and final scope against what it delivered (its
 * velocity), with the orange "Drift" badge and the frozen figures where the live figures moved since the sprint closed (D13). The
 * three scope bars (and the hidden table's Initial · Final · Delivered figures) open that sprint's items; the capacity bar is a
 * figure, not a set of items, and has no drill. No trend: a sprint is one event, not a window.
 */
export default function HomeSprintSection({ overview, open }: { overview: HomeOverview; open: OpenDrill }) {
  const { t } = useTranslation();
  const rows = sprintRows(overview.teams);
  const without = overview.teams.filter((team) => team.lastSprint === null).map((team) => team.name);
  const figure = (column: ColumnDef<HomeSprintRow>, bucket: SprintBucket): ColumnDef<HomeSprintRow> =>
    drillFigureColumn(column, {
      label: (row, text, header) =>
        t("reports.drill.figureAria", { figure: text, column: header, name: `${row.team} · ${row.sprint} · ${t(`reports.drill.bucket.${bucket}`)}` }),
      count: (row) => sprintBucketItems(row, bucket) ?? 0,
      onOpen: (row, trigger) => open({ kind: "sprint", teamId: row.teamId, sprintId: row.sprintId, bucket }, { trigger, anchor: ANCHOR }),
    });
  const columns: ColumnDef<HomeSprintRow>[] = [
    { key: "team", header: t("home.table.team"), render: (row) => row.team },
    { key: "sprint", header: t("home.sprints.column.sprint"), render: (row) => row.sprint },
    { key: "closed", header: t("home.sprints.column.closed"), render: (row) => formatDate(row.completedAt, "—", overview.timeZone) },
    { key: "capacity", header: t("home.sprints.column.capacity"), render: (row) => (row.capacityMd === null ? "—" : formatMd(row.capacityMd)), align: "right" },
    figure({ key: "initial", header: t("home.sprints.column.initial"), render: (row) => formatMd(row.initialMd), align: "right" }, "COMMITTED"),
    figure({ key: "final", header: t("home.sprints.column.final"), render: (row) => formatMd(row.finalMd), align: "right" }, "FINAL"),
    figure({ key: "delivered", header: t("home.sprints.column.delivered"), render: (row) => formatMd(row.deliveredMd), align: "right" }, "DELIVERED"),
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
  const onBarDoubleClick = (hit: ChartBarHit) => {
    const target = homeHitTarget(hit);
    if (target) open(target, { anchor: ANCHOR });
  };
  return (
    <HomeTile
      title={t("home.sprints.title")}
      to={overviewLink(commitmentsPath, LAST_SPRINT_FILTER)}
      caption={t("home.sprints.caption")}
      drillAnchor={ANCHOR}
      links={[
        { to: overviewLink(throughputPath, LAST_SPRINT_FILTER), label: t("home.sprints.throughputLink") },
        { to: overviewLink(sprintConsistencyPath, LAST_SPRINT_FILTER), label: t("home.sprints.consistencyLink") },
      ]}
    >
      {rows.length === 0 ? (
        <Text size="sm" c="dimmed">
          {t("home.sprints.empty")}
        </Text>
      ) : (
        <>
          <NearViewport height={260}>
            <Suspense fallback={<LoadingBlock />}>
              <HomeSprintChart rows={rows} timeZone={overview.timeZone} onBarDoubleClick={onBarDoubleClick} />
            </Suspense>
          </NearViewport>
          <HomeHiddenTable label={t("home.sprints.tableLabel")} columns={columns} rows={rows} rowKey={(row) => row.id} />
        </>
      )}
      {rows.length > 0 && without.length > 0 && (
        <Text size="xs" c="dimmed">
          {t("home.sprints.without", { teams: without.join(", ") })}
        </Text>
      )}
    </HomeTile>
  );
}
