import { Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { SyncCursorSummary } from "../api/dataSources";
import { formatEpochMillis } from "../utils/dataSourceState";
import ColumnTable, { type ColumnDef } from "./ColumnTable";

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
  const columns: ColumnDef<SyncCursorSummary>[] = [
    {
      key: "stream",
      header: t("dataSources.details.cursor.stream"),
      render: (cursor) => (
        <Text size="sm" fw={500}>
          {cursor.stream}
        </Text>
      ),
    },
    {
      key: "watermark",
      header: t("dataSources.details.cursor.watermark"),
      render: (cursor) => <Text size="sm">{formatEpochMillis(cursor.watermarkAt, t)}</Text>,
    },
    {
      key: "lastCompleted",
      header: t("dataSources.details.cursor.lastCompleted"),
      render: (cursor) => <Text size="sm">{formatEpochMillis(cursor.lastCompletedAt, t)}</Text>,
    },
  ];
  return (
    <ColumnTable
      aria-label={t("dataSources.details.cursors")}
      columns={columns}
      rows={cursors}
      rowKey={(cursor) => cursor.stream}
    />
  );
}
