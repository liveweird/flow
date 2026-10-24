import { lazy, Suspense } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { Stack, Table, Text } from "@mantine/core";
import { getTelemetryHistory, type TelemetryGroup, type TelemetryRange, type TelemetrySeries } from "../api/telemetry";
import { formatDate, formatDateTime } from "../utils/formatDate";
import { chartRows, formatTrendValue, trendTimes, type TrendColumn } from "../utils/telemetryTrend";
import DailyTableDisclosure from "./DailyTableDisclosure";
import ErrorAlert from "./ErrorAlert";
import LoadingBlock from "./LoadingBlock";
import ScrollRegion from "./ScrollRegion";

const TelemetryTrendChart = lazy(() => import("./TelemetryTrendChart"));

const HISTORY_STALE_MS = 5 * 60_000;

/**
 * One section's trend: the history of its group over the panel's range (per group and range, kept five minutes), the `columns`
 * the section derives from the series, ONE small chart of the plotted columns and every column's numbers in a table behind a
 * disclosure (a 420 px panel has no room beside the chart). The notes say when the history is shorter than the range
 * (`historyFrom`) or cut at the server's series cap (`truncated`). The query is the section's own: it only exists while the
 * section is expanded.
 */
export default function TelemetryTrend({
  group,
  range,
  columns,
  label,
  active = true,
  connections = [],
}: {
  group: TelemetryGroup;
  range: TelemetryRange;
  /** Derives the columns from the answer's series (a pure function of the series and the translator). */
  columns: (series: readonly TelemetrySeries[], t: TFunction) => TrendColumn[];
  /** The section's name for the chart and the table. */
  label: string;
  /** False while the section is collapsed (its body may stay mounted through the close transition): no request, cached figures stay. */
  active?: boolean;
  /** The data sources of the current answer, to name the ones the history left out. */
  connections?: readonly { id: number; name: string }[];
}) {
  const { t } = useTranslation();
  const history = useQuery({
    queryKey: ["telemetry", "history", group, range],
    queryFn: ({ signal }) => getTelemetryHistory(group, range, signal),
    staleTime: HISTORY_STALE_MS,
    enabled: active,
    placeholderData: keepPreviousData,
  });
  if (history.isError && history.data === undefined) return <ErrorAlert error={history.error} />;
  if (history.data === undefined) return <LoadingBlock py="md" />;

  const data = history.data;
  const omitted = data.omittedConnectionIds.map((id) => connections.find((connection) => connection.id === id)?.name ?? `#${id}`);
  const all = columns(data.series, t);
  const plotted = all.filter((column) => column.plot);
  const times = trendTimes(all);
  const unit = plotted[0]?.unit ?? "COUNT";
  // Kept data of the previous range (a switch in flight) is dimmed, never mislabelled: its own range is on the answer.
  const stale = history.isPlaceholderData;
  return (
    <Stack gap="xs" aria-busy={stale || undefined} opacity={stale ? 0.5 : 1}>
      {times.length === 0 ? (
        <Text size="sm" c="dimmed">
          {t("telemetry.trend.empty")}
        </Text>
      ) : (
        <>
          <Suspense fallback={<LoadingBlock py="md" />}>
            <TelemetryTrendChart
              rows={chartRows(plotted, times)}
              lines={plotted.map((column) => ({ label: column.label }))}
              unit={unit}
              range={data.range}
              ariaLabel={t("telemetry.trend.chartAria", { section: label })}
            />
          </Suspense>
          <DailyTableDisclosure showLabel={t("telemetry.trend.showTable")} hideLabel={t("telemetry.trend.hideTable")}>
            <ScrollRegion label={t("telemetry.trend.tableAria", { section: label })} minWidth={120 + 110 * all.length} maxHeight={320}>
              <Table verticalSpacing="xs" stickyHeader aria-label={t("telemetry.trend.tableAria", { section: label })}>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>{t("telemetry.trend.time")}</Table.Th>
                    {all.map((column) => (
                      <Table.Th key={column.id}>{column.label}</Table.Th>
                    ))}
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {times.map((time) => (
                    <Table.Tr key={time}>
                      <Table.Td>{formatDateTime(time)}</Table.Td>
                      {all.map((column) => {
                        const value = column.values.get(time);
                        return <Table.Td key={column.id}>{value === undefined ? "—" : formatTrendValue(column.unit, value)}</Table.Td>;
                      })}
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </ScrollRegion>
          </DailyTableDisclosure>
        </>
      )}
      {data.historyFrom > data.from && (
        <Text size="xs" c="dimmed">
          {t("telemetry.trend.historyFrom", { date: formatDate(data.historyFrom) })}
        </Text>
      )}
      {omitted.length > 0 && (
        <Text size="xs" c="dimmed">
          {t("telemetry.trend.omitted", { names: omitted.join(", ") })}
        </Text>
      )}
      {data.truncated && omitted.length === 0 && (
        <Text size="xs" c="dimmed">
          {t("telemetry.trend.truncated")}
        </Text>
      )}
    </Stack>
  );
}
