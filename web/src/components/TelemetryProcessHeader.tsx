import { useTranslation } from "react-i18next";
import { Badge, Group, Stack, Text, Title } from "@mantine/core";
import type { TelemetryProcess } from "../api/telemetry";
import { formatDateTime } from "../utils/formatDate";
import { roleName } from "../utils/telemetryRole";

/** One process's heading in a section: role and instance, a "Stale" badge, and when its figures were sampled. */
export default function TelemetryProcessHeader({ process }: { process: TelemetryProcess }) {
  const { t } = useTranslation();
  return (
    <Stack gap={2}>
      <Group justify="space-between" wrap="nowrap" align="flex-start" gap="xs">
        <Title order={4} size="h5" style={{ overflowWrap: "anywhere" }}>
          {roleName(t, process.role)} · {process.instance}
        </Title>
        {process.stale && (
          <Badge color="orange" variant="light" style={{ flexShrink: 0 }}>
            {t("telemetry.stale.badge")}
          </Badge>
        )}
      </Group>
      <Text size="xs" c="dimmed">
        {process.sampledAt == null ? t("telemetry.noSample") : t("telemetry.sampledAt", { time: formatDateTime(process.sampledAt) })}
      </Text>
    </Stack>
  );
}
