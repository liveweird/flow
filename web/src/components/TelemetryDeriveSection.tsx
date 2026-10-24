import { useTranslation } from "react-i18next";
import { Badge, Stack, Table, Text } from "@mantine/core";
import type { TelemetryCurrent, TelemetryRange } from "../api/telemetry";
import { formatDateTime } from "../utils/formatDate";
import { formatCount, formatDuration } from "../utils/telemetryFormat";
import ScrollRegion from "./ScrollRegion";
import TelemetryJobTrend from "./TelemetryJobTrend";
import { ConnectionName } from "./TelemetryStorageSection";

/** DERIVE: per data source the last run (status, when, how long), the longest run of the week and the rows it wrote; and the trend. */
export default function TelemetryDeriveSection({ current, range, active }: { current: TelemetryCurrent; range: TelemetryRange; active: boolean }) {
  const { t } = useTranslation();
  const { connections } = current;
  return (
    <Stack gap="md">
      {connections.length === 0 ? (
        <Text size="sm" c="dimmed">
          {t("telemetry.connection.none")}
        </Text>
      ) : (
        <ScrollRegion label={t("telemetry.derive.tableAria")} minWidth={560}>
          <Table aria-label={t("telemetry.derive.tableAria")} verticalSpacing="xs">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t("telemetry.connection.name")}</Table.Th>
                <Table.Th>{t("telemetry.derive.lastRun")}</Table.Th>
                <Table.Th ta="right">{t("telemetry.derive.duration")}</Table.Th>
                <Table.Th ta="right">{t("telemetry.derive.peak")}</Table.Th>
                <Table.Th ta="right">{t("telemetry.derive.rows")}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {connections.map((connection) => {
                const derive = connection.derive;
                return (
                  <Table.Tr key={connection.id}>
                    <Table.Td>
                      <ConnectionName connection={connection} />
                    </Table.Td>
                    {derive == null ? (
                      <Table.Td colSpan={4}>
                        <Text size="sm" c="dimmed">
                          {t("telemetry.derive.none")}
                        </Text>
                      </Table.Td>
                    ) : (
                      <>
                        <Table.Td>
                          <Badge color={derive.lastStatus === "SUCCEEDED" ? "teal" : "red"} variant="light">
                            {derive.lastStatus === "SUCCEEDED" ? t("telemetry.derive.succeeded") : t("telemetry.derive.failed")}
                          </Badge>
                          <Text size="xs" c="dimmed">
                            {formatDateTime(derive.lastFinishedAt)}
                          </Text>
                        </Table.Td>
                        <Table.Td ta="right">{formatDuration(derive.lastDurationMs)}</Table.Td>
                        <Table.Td ta="right">{formatDuration(derive.peakDurationMs)}</Table.Td>
                        <Table.Td ta="right">{formatCount(derive.rowsWritten)}</Table.Td>
                      </>
                    )}
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </ScrollRegion>
      )}
      <TelemetryJobTrend kind="derive" range={range} connections={connections} active={active} />
    </Stack>
  );
}
