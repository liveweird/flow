import { useTranslation } from "react-i18next";
import { Badge, Stack, Table, Text } from "@mantine/core";
import type { TelemetryCurrent, TelemetryRange, TelemetryRouteStats } from "../api/telemetry";
import { formatDateTime } from "../utils/formatDate";
import { formatCount, formatDuration } from "../utils/telemetryFormat";
import { requestsColumns } from "../utils/telemetryTrend";
import ScrollRegion from "./ScrollRegion";
import TelemetryTrend from "./TelemetryTrend";

function RouteRow({ stats, name }: { stats: TelemetryRouteStats; name: string }) {
  return (
    <Table.Tr>
      <Table.Td>
        <Text size="xs" title={name} style={{ overflowWrap: "anywhere" }}>
          {name}
        </Text>
      </Table.Td>
      <Table.Td ta="right">{formatCount(stats.count)}</Table.Td>
      <Table.Td ta="right">{formatDuration(stats.p50Ms)}</Table.Td>
      <Table.Td ta="right">{formatDuration(stats.p95Ms)}</Table.Td>
      <Table.Td ta="right">
        {stats.serverErrors > 0 ? (
          <Badge color="red" variant="light">
            {formatCount(stats.serverErrors)}
          </Badge>
        ) : (
          "0"
        )}
      </Table.Td>
      <Table.Td ta="right">
        {stats.rateLimited > 0 ? (
          <Badge color="orange" variant="light">
            {formatCount(stats.rateLimited)}
          </Badge>
        ) : (
          "0"
        )}
      </Table.Td>
    </Table.Tr>
  );
}

/** Requests: the latest sample interval's total and busiest report routes (count, p50, p95, 5xx, 429), and the trend. */
export default function TelemetryRequestsSection({ current, range, active }: { current: TelemetryCurrent; range: TelemetryRange; active: boolean }) {
  const { t } = useTranslation();
  const { requests } = current;
  const minutes = Math.max(1, Math.round(requests.windowSeconds / 60));
  const percentileMinutes = Math.max(1, Math.round(requests.percentileWindowSeconds / 60));
  return (
    <Stack gap="md">
      {requests.measuredAt == null ? (
        <Text size="sm" c="dimmed">
          {t("telemetry.requests.none")}
        </Text>
      ) : (
        <Stack gap="xs">
          <Text size="xs" c="dimmed">
            {t("telemetry.requests.window", { minutes, percentileMinutes, time: formatDateTime(requests.measuredAt) })}
          </Text>
          <ScrollRegion label={t("telemetry.requests.tableAria")} minWidth={520}>
            <Table aria-label={t("telemetry.requests.tableAria")} verticalSpacing="xs">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t("telemetry.requests.route")}</Table.Th>
                  <Table.Th ta="right">{t("telemetry.requests.count")}</Table.Th>
                  <Table.Th ta="right">{t("telemetry.requests.p50", { minutes: percentileMinutes })}</Table.Th>
                  <Table.Th ta="right">{t("telemetry.requests.p95", { minutes: percentileMinutes })}</Table.Th>
                  <Table.Th ta="right">{t("telemetry.requests.serverErrors")}</Table.Th>
                  <Table.Th ta="right">{t("telemetry.requests.rateLimited")}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                <RouteRow stats={requests.total} name={t("telemetry.requests.total")} />
                {requests.routes.map((route) => (
                  <RouteRow key={route.route} stats={route} name={route.route} />
                ))}
              </Table.Tbody>
            </Table>
          </ScrollRegion>
        </Stack>
      )}
      <TelemetryTrend group="requests" range={range} columns={requestsColumns} label={t("telemetry.section.requests")} active={active} />
    </Stack>
  );
}
