import { useTranslation } from "react-i18next";
import { Alert, Button, Stack, Text } from "@mantine/core";
import { IconAlertTriangle } from "@tabler/icons-react";

/**
 * The telemetry panel's crash fallback. The panel's open flag survives a reload, so a render crash inside it would otherwise come
 * back on every load with no way to switch it off: this is that way out ("Close panel" clears the flag; the account menu opens it again).
 */
export default function TelemetryPanelCrash({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  return (
    <Stack p="md">
      <Alert color="red" variant="light" icon={<IconAlertTriangle />} title={t("telemetry.crash.title")}>
        <Stack gap="sm" align="flex-start">
          <Text size="sm">{t("telemetry.crash.message")}</Text>
          <Button color="red" variant="light" onClick={onClose}>
            {t("telemetry.crash.close")}
          </Button>
        </Stack>
      </Alert>
    </Stack>
  );
}
