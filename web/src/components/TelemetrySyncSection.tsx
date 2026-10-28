import { useTranslation } from "react-i18next";
import { Badge, Stack, Table, Text } from "@mantine/core";
import type { TelemetryConnection, TelemetryCurrent, TelemetryRange } from "../api/telemetry";
import { formatDateTime } from "../utils/formatDate";
import { formatCount, formatDuration } from "../utils/telemetryFormat";
import ScrollRegion from "./ScrollRegion";
import TelemetryJobTrend from "./TelemetryJobTrend";
import { ConnectionName } from "./TelemetryStorageSection";

function LastGoodSync({ connection, now }: { connection: TelemetryConnection; now: number }) {
  const { t } = useTranslation();
  const at = connection.freshness.lastSyncSucceededAt;
  if (at == null) return <Text size="sm" c="dimmed">{t("telemetry.sync.never")}</Text>;
  return (
    <>
      <Text size="sm">{t("telemetry.ago", { age: formatDuration(Math.max(0, now - at)) })}</Text>
      <Text size="xs" c="dimmed">
        {formatDateTime(at)}
      </Text>
    </>
  );
}

function Failures({ connection }: { connection: TelemetryConnection }) {
  const { t } = useTranslation();
  const { consecutiveFailures, lastSyncErrorCode, pausedAt } = connection.freshness;
  const paused = pausedAt != null && (
    <Badge color="orange" variant="light">
      {t("dataSources.paused.badge")}
    </Badge>
  );
  if (consecutiveFailures === 0) return paused || <>0</>;
  return (
    <>
      <Badge color="red" variant="light">
        {t("telemetry.sync.failedInARow", { count: consecutiveFailures })}
      </Badge>
      {paused}
      {lastSyncErrorCode != null && (
        <Text size="xs" c="dimmed">
          {lastSyncErrorCode}
        </Text>
      )}
    </>
  );
}

function RateLimited({ connection }: { connection: TelemetryConnection }) {
  const { t } = useTranslation();
  if (connection.rateLimited24h === 0) return <>0</>;
  return (
    <>
      <Badge color="orange" variant="light">
        {formatCount(connection.rateLimited24h)}
      </Badge>
      <Text size="xs" c="dimmed">
        {t("telemetry.sync.backoff", { duration: formatDuration(connection.backoffMs24h) })}
      </Text>
    </>
  );
}

/** Sync: per data source the age of the last good sync, consecutive failures, Jira 429s of the last 24 h, the queue and the last backfill; and the trend. */
export default function TelemetrySyncSection({ current, range, active }: { current: TelemetryCurrent; range: TelemetryRange; active: boolean }) {
  const { t } = useTranslation();
  const { connections } = current;
  return (
    <Stack gap="md">
      {connections.length === 0 ? (
        <Text size="sm" c="dimmed">
          {t("telemetry.connection.none")}
        </Text>
      ) : (
        <ScrollRegion label={t("telemetry.sync.tableAria")} minWidth={620}>
          <Table aria-label={t("telemetry.sync.tableAria")} verticalSpacing="xs">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t("telemetry.connection.name")}</Table.Th>
                <Table.Th>{t("telemetry.sync.lastGood")}</Table.Th>
                <Table.Th>{t("telemetry.sync.failures")}</Table.Th>
                <Table.Th>{t("telemetry.sync.rateLimited")}</Table.Th>
                <Table.Th>{t("telemetry.sync.queue")}</Table.Th>
                <Table.Th>{t("telemetry.sync.lastBackfill")}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {connections.map((connection) => (
                <Table.Tr key={connection.id}>
                  <Table.Td>
                    <ConnectionName connection={connection} />
                  </Table.Td>
                  <Table.Td>
                    <LastGoodSync connection={connection} now={current.generatedAt} />
                  </Table.Td>
                  <Table.Td>
                    <Failures connection={connection} />
                  </Table.Td>
                  <Table.Td>
                    <RateLimited connection={connection} />
                  </Table.Td>
                  <Table.Td>
                    {t("telemetry.sync.queueCounts", { pending: connection.queue.pending, running: connection.queue.running })}
                  </Table.Td>
                  <Table.Td>
                    {connection.lastBackfill?.durationMs == null ? (
                      "—"
                    ) : (
                      <>
                        <Text size="sm">{formatDuration(connection.lastBackfill.durationMs)}</Text>
                        {connection.lastBackfill.finishedAt != null && (
                          <Text size="xs" c="dimmed">
                            {formatDateTime(connection.lastBackfill.finishedAt)}
                          </Text>
                        )}
                      </>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </ScrollRegion>
      )}
      <TelemetryJobTrend kind="sync" range={range} connections={connections} active={active} />
    </Stack>
  );
}
