import { useTranslation } from "react-i18next";
import { Stack } from "@mantine/core";
import type { TelemetryCurrent, TelemetryRange } from "../api/telemetry";
import { formatCount, formatDuration, formatRatio } from "../utils/telemetryFormat";
import { jvmColumns } from "../utils/telemetryTrend";
import { FirstSampleNote } from "./TelemetryMemorySection";
import TelemetryProcessHeader from "./TelemetryProcessHeader";
import TelemetryTrend from "./TelemetryTrend";
import TelemetryValueTable from "./TelemetryValueTable";

/** JVM extras: per process the CPU, thread count, GC work of the latest interval and the database pool, and the trend. */
export default function TelemetryJvmSection({ current, range, active }: { current: TelemetryCurrent; range: TelemetryRange; active: boolean }) {
  const { t } = useTranslation();
  return (
    <Stack gap="md">
      {current.processes.length === 0 ? (
        <FirstSampleNote intervalSeconds={current.sampleIntervalSeconds} />
      ) : (
        current.processes.map((process) => (
          <Stack key={process.id} gap="xs">
            <TelemetryProcessHeader process={process} />
            {process.sampledAt == null ? (
              <FirstSampleNote intervalSeconds={current.sampleIntervalSeconds} />
            ) : (
              <TelemetryValueTable
                label={t("telemetry.jvm.tableAria", { process: process.instance })}
                rows={[
                  { label: t("telemetry.jvm.cpu"), value: formatRatio(process.jvm.cpuRatio) },
                  { label: t("telemetry.jvm.threads"), value: formatCount(process.jvm.threads) },
                  { label: t("telemetry.jvm.gcCount"), value: formatCount(process.jvm.gcCount) },
                  { label: t("telemetry.jvm.gcTime"), value: formatDuration(process.jvm.gcTimeMs) },
                  { label: t("telemetry.jvm.poolAcquired"), value: formatCount(process.jvm.poolAcquired) },
                  { label: t("telemetry.jvm.poolIdle"), value: formatCount(process.jvm.poolIdle) },
                  { label: t("telemetry.jvm.poolPending"), value: formatCount(process.jvm.poolPending) },
                  { label: t("telemetry.jvm.poolAllocated"), value: formatCount(process.jvm.poolAllocated) },
                ]}
              />
            )}
          </Stack>
        ))
      )}
      <TelemetryTrend group="jvm" range={range} columns={jvmColumns} label={t("telemetry.section.jvm")} active={active} />
    </Stack>
  );
}
