import { useState } from "react";
import { Alert, Button, Group, NumberInput, Paper, Stack, Text, Title } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { updateTeamCapacity, type TeamCapacity } from "../api/capacity";
import { TEAM_DEFAULT_MD_MAX } from "../utils/capacity";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

/**
 * The team's ONE default capacity figure in man-days (`TEAM_DEFAULT`): used for every sprint of the
 * team that has no calendar. Blank clears it. Edits only for the team's manager or an ADMIN
 * (`canEdit` from the server — the server stays the rule). Mounted per loaded figure (the page keys
 * it by team and `updatedAt`), so the field starts from what is stored.
 */
export default function TeamCapacityDefaultCard({ capacity }: { capacity: TeamCapacity }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [value, setValue] = useState<string | number>(capacity.defaultCapacityMd ?? "");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function save() {
    setError(null);
    setSaving(true);
    try {
      const figure = value === "" ? null : Number(value);
      await updateTeamCapacity(capacity.teamId, { defaultCapacityMd: figure });
      await refreshQueriesAfterMutation(queryClient, ["capacity"]);
      showSuccessToast(t("capacity.toast.saved"));
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          forbidden: "capacity.error.forbidden",
          notFound: "capacity.error.notFound",
          invalid: "capacity.error.invalidDefault",
          failed: "capacity.error.failed",
        }),
      );
    } finally {
      setSaving(false);
    }
  }

  return (
    <Paper withBorder p="md">
      <Stack gap="sm">
        <Title order={3} size="h4">
          {t("capacity.default.title")}
        </Title>
        <Text size="sm" c="dimmed">
          {t("capacity.default.intro")}
        </Text>
        {!capacity.canEdit && (
          <Alert color="gray" variant="light">
            {t("capacity.default.readOnly")}
          </Alert>
        )}
        <Group align="flex-end" gap="sm">
          <NumberInput
            label={t("capacity.default.field")}
            w={220}
            min={0}
            max={TEAM_DEFAULT_MD_MAX}
            decimalScale={2}
            step={1}
            hideControls
            value={value}
            onChange={setValue}
            disabled={!capacity.canEdit}
          />
          <Button onClick={() => void save()} loading={saving} disabled={!capacity.canEdit}>
            {t("capacity.default.save")}
          </Button>
        </Group>
        {error && (
          <Alert color="red" variant="light">
            {error}
          </Alert>
        )}
      </Stack>
    </Paper>
  );
}
