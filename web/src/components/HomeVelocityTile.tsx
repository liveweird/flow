import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { Badge, Group, SimpleGrid, Table, Text } from "@mantine/core";
import type { SprintConsistencyReport } from "../api/reports";
import { formatDate } from "../utils/formatDate";
import { overviewTeamRows } from "../utils/homeOverview";
import { formatMd } from "../utils/reportFormat";
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
            <Table verticalSpacing={4} aria-label={t("home.velocity.tableLabel")}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t("home.velocity.column.team")}</Table.Th>
                  <Table.Th>{t("home.velocity.column.sprint")}</Table.Th>
                  <Table.Th>{t("home.velocity.column.closed")}</Table.Th>
                  <Table.Th ta="right">{t("home.velocity.column.initial")}</Table.Th>
                  <Table.Th ta="right">{t("home.velocity.column.final")}</Table.Th>
                  <Table.Th ta="right">{t("home.velocity.column.delivered")}</Table.Th>
                  <Table.Th>{t("reports.sprints.drift")}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((row) => (
                  <Table.Tr key={row.key}>
                    <Table.Td>{row.team}</Table.Td>
                    <Table.Td>{row.sprint}</Table.Td>
                    <Table.Td>{formatDate(row.completedAt, t("reports.sprints.open"), timeZone)}</Table.Td>
                    <Table.Td ta="right">{formatMd(row.initialMd)}</Table.Td>
                    <Table.Td ta="right">{formatMd(row.finalMd)}</Table.Td>
                    <Table.Td ta="right">{formatMd(row.deliveredMd)}</Table.Td>
                    <Table.Td>
                      {row.drift && row.frozen && (
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
                      )}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollRegion>
        </SimpleGrid>
      )}
    </HomeTile>
  );
}
