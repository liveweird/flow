import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Badge, Group, SimpleGrid, Text } from "@mantine/core";
import type { SprintConsistencyReport } from "../api/reports";
import { formatDate } from "../utils/formatDate";
import { overviewTeamRows, type OverviewTeamRow } from "../utils/homeOverview";
import { formatMd } from "../utils/reportFormat";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import HomeTile from "./HomeTile";
import LoadingBlock from "./LoadingBlock";
import ScrollRegion from "./ScrollRegion";

// The chart (and with it recharts) rides its own lazy chunk.
const HomeVelocityChart = lazy(() => import("./HomeVelocityChart"));

/**
 * Tile 1 — each team's last closed sprint: initial and final velocity against what it delivered
 * (throughput). ONE request answers it: `sprint-consistency?lastSprints=1` at UNIT level, whose sprint
 * rows carry the committed (= initial), final and delivered figures of the same `fact_sprint` row.
 * A sprint whose live figures moved since it closed (D13) gets the orange "Drift" badge with the
 * frozen figures as text, exactly as on the report pages. The closing day is read in the configured
 * zone (the shared reference data's `timeZone`), like every report date.
 */
export default function HomeVelocityTile({
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
  const rows = query.data ? overviewTeamRows(query.data) : [];
  const columns: ColumnDef<OverviewTeamRow>[] = [
    { key: "team", header: t("home.velocity.column.team"), render: (row) => row.team },
    { key: "sprint", header: t("home.velocity.column.sprint"), render: (row) => row.sprint },
    {
      key: "closed",
      header: t("home.velocity.column.closed"),
      render: (row) => formatDate(row.completedAt, t("reports.sprints.open"), timeZone),
    },
    { key: "initial", header: t("home.velocity.column.initial"), render: (row) => formatMd(row.initialMd), align: "right" },
    { key: "final", header: t("home.velocity.column.final"), render: (row) => formatMd(row.finalMd), align: "right" },
    {
      key: "delivered",
      header: t("home.velocity.column.delivered"),
      render: (row) => formatMd(row.deliveredMd),
      align: "right",
    },
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
      title={t("home.velocity.title")}
      to={to}
      caption={t("home.velocity.caption")}
      isPending={query.isPending}
      error={query.error}
      links={links}
    >
      {rows.length === 0 ? (
        <Text size="sm" c="dimmed">
          {t("home.velocity.empty")}
        </Text>
      ) : (
        <SimpleGrid cols={{ base: 1, md: 2 }} spacing="lg">
          <Suspense fallback={<LoadingBlock />}>
            <HomeVelocityChart rows={rows} />
          </Suspense>
          <ScrollRegion label={t("home.velocity.tableLabel")} minWidth={520}>
            <ColumnTable
              verticalSpacing={4}
              aria-label={t("home.velocity.tableLabel")}
              columns={columns}
              rows={rows}
              rowKey={(row) => row.key}
            />
          </ScrollRegion>
        </SimpleGrid>
      )}
    </HomeTile>
  );
}
