import { Table, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { SyncCursorSummary } from "../api/dataSources";
import { formatEpochMillis } from "../utils/dataSourceState";

/**
 * The details page's persisted-cursor table (plan §10/§11): one row per stream still resuming
 * (`reference`/`issues`/`changelogs`/`worklogs`/`reconcile`) — a completed pass with nothing
 * left to resume has no row at all, per `SyncStatusResponse.cursors`'s own contract, so an empty
 * list is a normal "nothing to resume" state, not a load failure.
 */
export default function CursorTable({ cursors }: { cursors: SyncCursorSummary[] }) {
  const { t } = useTranslation();
  if (cursors.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        {t("dataSources.details.noCursors")}
      </Text>
    );
  }
  return (
    <Table aria-label={t("dataSources.details.cursors")}>
      <Table.Thead>
        <Table.Tr>
          <Table.Th>{t("dataSources.details.cursor.stream")}</Table.Th>
          <Table.Th>{t("dataSources.details.cursor.watermark")}</Table.Th>
          <Table.Th>{t("dataSources.details.cursor.lastCompleted")}</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {cursors.map((cursor) => (
          <Table.Tr key={cursor.stream}>
            <Table.Td>
              <Text size="sm" fw={500}>
                {cursor.stream}
              </Text>
            </Table.Td>
            <Table.Td>
              <Text size="sm">{formatEpochMillis(cursor.watermarkAt, t)}</Text>
            </Table.Td>
            <Table.Td>
              <Text size="sm">{formatEpochMillis(cursor.lastCompletedAt, t)}</Text>
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}
