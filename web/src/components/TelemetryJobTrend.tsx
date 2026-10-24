import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Select, Stack } from "@mantine/core";
import type { TelemetryConnection, TelemetryRange } from "../api/telemetry";
import { jobColumns } from "../utils/telemetryTrend";
import TelemetryTrend from "./TelemetryTrend";

/**
 * The sync or DERIVE trend of ONE data source, picked from a Select (a trend per connection would be a chart per source; the
 * first source is the default). The history answer holds every connection's series; the picked id selects its own.
 */
export default function TelemetryJobTrend({
  kind,
  range,
  connections,
  active,
}: {
  kind: "sync" | "derive";
  range: TelemetryRange;
  connections: readonly TelemetryConnection[];
  active: boolean;
}) {
  const { t } = useTranslation();
  const [picked, setPicked] = useState<string | null>(null);
  const first = connections[0];
  if (first === undefined) return null;
  // A pick that is no longer listed (a purged source) falls back to the first.
  const selected = connections.find((connection) => String(connection.id) === picked) ?? first;
  const label = t(kind === "sync" ? "telemetry.section.sync" : "telemetry.section.derive");
  return (
    <Stack gap="xs">
      <Select
        label={t("telemetry.trend.connection")}
        size="xs"
        allowDeselect={false}
        data={connections.map((connection) => ({ value: String(connection.id), label: connection.name }))}
        value={String(selected.id)}
        onChange={setPicked}
      />
      <TelemetryTrend
        group={kind}
        range={range}
        columns={(series, tr) => jobColumns(series, selected.id, kind, tr)}
        label={`${label} · ${selected.name}`}
        active={active}
        connections={connections}
      />
    </Stack>
  );
}
