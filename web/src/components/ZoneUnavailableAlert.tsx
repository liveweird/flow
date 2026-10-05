import { useTranslation } from "react-i18next";
import { Alert, Button, Group, Text } from "@mantine/core";
import { loadErrorMessage } from "../utils/saveError";

/**
 * The membership editors' "the metrics zone failed to load" hint: why the End action / the add form's
 * submit is held off (the cause, status-mapped) and a Retry that refetches the zone — never a
 * silently dead button. `id` lets a disabled control point at it with `aria-describedby`.
 */
export default function ZoneUnavailableAlert({ error, onRetry, id }: { error: unknown; onRetry: () => void; id?: string }) {
  const { t } = useTranslation();
  return (
    <Alert id={id} color="red" variant="light">
      <Group justify="space-between" align="center" wrap="nowrap">
        <Text size="sm">
          {t("metrics.teamMembers.zoneUnavailable")} {loadErrorMessage(error, t)}
        </Text>
        <Button size="compact-xs" variant="default" onClick={onRetry}>
          {t("common.action.retry")}
        </Button>
      </Group>
    </Alert>
  );
}
