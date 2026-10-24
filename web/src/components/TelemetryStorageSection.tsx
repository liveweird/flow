import { useTranslation } from "react-i18next";
import { Badge, Group, Stack, Table, Text } from "@mantine/core";
import type { TelemetryConnection, TelemetryCurrent, TelemetryRange } from "../api/telemetry";
import { formatDateTime } from "../utils/formatDate";
import { formatBytes, formatCount } from "../utils/telemetryFormat";
import { storageColumns } from "../utils/telemetryTrend";
import TelemetryTrend from "./TelemetryTrend";
import TelemetryValueTable from "./TelemetryValueTable";

/** A data source's name, with a neutral text badge while it is disabled or awaiting its purge. */
export function ConnectionName({ connection }: { connection: TelemetryConnection }) {
  const { t } = useTranslation();
  return (
    <Group gap={6} wrap="wrap">
      <Text size="sm" style={{ overflowWrap: "anywhere" }}>
        {connection.name}
      </Text>
      {connection.deleted ? (
        <Badge color="gray" variant="light">
          {t("telemetry.connection.deleted")}
        </Badge>
      ) : (
        !connection.enabled && (
          <Badge color="gray" variant="light">
            {t("telemetry.connection.disabled")}
          </Badge>
        )
      )}
    </Group>
  );
}

/** Storage: the database and each schema (measured hourly), the per-data-source estimate, and the trend. */
export default function TelemetryStorageSection({ current, range, active }: { current: TelemetryCurrent; range: TelemetryRange; active: boolean }) {
  const { t } = useTranslation();
  const { storage, connections } = current;
  return (
    <Stack gap="md">
      {storage == null ? (
        <Text size="sm" c="dimmed">
          {t("telemetry.storage.notYet")}
        </Text>
      ) : (
        <Stack gap="xs">
          <Text size="xs" c="dimmed">
            {t("telemetry.storage.measuredAt", { time: formatDateTime(storage.measuredAt) })}
          </Text>
          <TelemetryValueTable
            label={t("telemetry.storage.tableAria")}
            rows={[
              { label: t("telemetry.storage.database"), value: formatBytes(storage.databaseBytes) },
              ...storage.schemas.map((schema) => ({ label: t("telemetry.storage.schema", { name: schema.name }), value: formatBytes(schema.bytes) })),
            ]}
          />
        </Stack>
      )}
      {connections.length > 0 && (
        <Stack gap="xs">
          <Table aria-label={t("telemetry.storage.connectionsAria")} verticalSpacing="xs">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t("telemetry.connection.name")}</Table.Th>
                <Table.Th ta="right">{t("telemetry.storage.issues")}</Table.Th>
                <Table.Th ta="right">{t("telemetry.storage.estimatedBytes")}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {connections.map((connection) => (
                <Table.Tr key={connection.id}>
                  <Table.Td>
                    <ConnectionName connection={connection} />
                  </Table.Td>
                  <Table.Td ta="right">{formatCount(connection.issues)}</Table.Td>
                  <Table.Td ta="right">{connection.estimatedBytes == null ? "—" : `≈ ${formatBytes(connection.estimatedBytes)}`}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          <Text size="xs" c="dimmed">
            {t("telemetry.storage.estimateNote")}
          </Text>
        </Stack>
      )}
      <TelemetryTrend group="storage" range={range} columns={storageColumns} label={t("telemetry.section.storage")} active={active} connections={connections} />
    </Stack>
  );
}
