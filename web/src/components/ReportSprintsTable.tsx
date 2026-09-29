import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Badge, Group, Table, Text } from "@mantine/core";
import type { ReportFilters } from "../api/reports";
import { formatDate } from "../utils/formatDate";
import { sortSprints, teamNameOf, type SprintIdentity } from "../utils/reportSprints";

export interface SprintColumn<S> {
  key: string;
  header: string;
  render: (sprint: S) => ReactNode;
}

/**
 * The per-sprint table beside a sprint chart (the chart's table view): sprint, team, completion
 * date (in the configured zone; "Open" for an active sprint), the report's own figure columns,
 * and the drift column — an orange "Drift" badge (the soft-finding hue: the live figures moved
 * since the sprint closed, D13) with the frozen figures as visible text, not a tooltip.
 */
export default function ReportSprintsTable<S extends SprintIdentity & { drift: boolean }>({
  sprints,
  filters,
  columns,
  driftDetail,
  minWidth = 760,
}: {
  sprints: readonly S[];
  filters: ReportFilters;
  columns: ReadonlyArray<SprintColumn<S>>;
  /** The frozen figures of a drifted sprint, already translated; null when it has no snapshot. */
  driftDetail: (sprint: S) => string | null;
  minWidth?: number;
}) {
  const { t } = useTranslation();
  return (
    <Table.ScrollContainer minWidth={minWidth}>
      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t("reports.sprints.sprint")}</Table.Th>
            <Table.Th>{t("reports.sprints.team")}</Table.Th>
            <Table.Th>{t("reports.sprints.completed")}</Table.Th>
            {columns.map((column) => (
              <Table.Th key={column.key} ta="right">
                {column.header}
              </Table.Th>
            ))}
            <Table.Th>{t("reports.sprints.drift")}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {sortSprints(sprints).map((sprint) => {
            const detail = sprint.drift ? driftDetail(sprint) : null;
            return (
              <Table.Tr key={`${sprint.teamId}:${sprint.sprintId}`}>
                <Table.Td>{sprint.name}</Table.Td>
                <Table.Td>{teamNameOf(filters, sprint.teamId)}</Table.Td>
                <Table.Td>{formatDate(sprint.completedAt, t("reports.sprints.open"), filters.timeZone)}</Table.Td>
                {columns.map((column) => (
                  <Table.Td key={column.key} ta="right">
                    {column.render(sprint)}
                  </Table.Td>
                ))}
                <Table.Td>
                  {detail !== null && (
                    <Group gap="xs" wrap="nowrap">
                      <Badge color="orange" variant="light">
                        {t("reports.sprints.driftBadge")}
                      </Badge>
                      <Text size="xs" c="dimmed">
                        {detail}
                      </Text>
                    </Group>
                  )}
                </Table.Td>
              </Table.Tr>
            );
          })}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}
