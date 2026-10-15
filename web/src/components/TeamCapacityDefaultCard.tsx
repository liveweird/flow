import { useEffect, useState } from "react";
import { Alert, Button, Group, NumberInput, Paper, Stack, Text, Title } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { updateTeamCapacity, type TeamCapacity } from "../api/capacity";
import { DECIMAL_SEPARATORS, parseFigure, TEAM_DEFAULT_MD_MAX } from "../utils/capacity";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

/**
 * The team's ONE default capacity figure in man-days (`TEAM_DEFAULT`): used for every sprint of the
 * team that has no calendar. Blank clears it. Edits only for the team's manager or an ADMIN
 * (`canEdit` from the server — the server stays the rule). Mounted per team (the page keys it by
 * team), never per `updatedAt`: a reload that brings a new stored figure is adopted while the field
 * is untouched and never replaces what the user is typing.
 */
export default function TeamCapacityDefaultCard({
  capacity,
  onDirtyChange,
}: {
  capacity: TeamCapacity;
  onDirtyChange: (dirty: boolean) => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [value, setValue] = useState<string | number>(capacity.defaultCapacityMd ?? "");
  // The stored figure the field was seeded from (the loaded one, or what the last save stored).
  const [stored, setStored] = useState(capacity.defaultCapacityMd);
  const [seen, setSeen] = useState(capacity.defaultCapacityMd);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const figure = parseFigure(value);
  const dirty = figure !== stored;
  const invalid = figure === undefined;

  if (capacity.defaultCapacityMd !== seen) {
    setSeen(capacity.defaultCapacityMd);
    if (!dirty) {
      setStored(capacity.defaultCapacityMd);
      setValue(capacity.defaultCapacityMd ?? "");
    }
  }

  useEffect(() => {
    onDirtyChange(dirty);
    return () => onDirtyChange(false);
  }, [dirty, onDirtyChange]);

  async function save() {
    if (figure === undefined) return;
    setError(null);
    setSaving(true);
    try {
      await updateTeamCapacity(capacity.teamId, { defaultCapacityMd: figure });
      setStored(figure);
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
            allowNegative={false}
            allowedDecimalSeparators={DECIMAL_SEPARATORS}
            step={1}
            hideControls
            value={value}
            onChange={setValue}
            error={invalid ? t("capacity.default.invalid") : undefined}
            disabled={!capacity.canEdit}
          />
          <Button onClick={() => void save()} loading={saving} disabled={!capacity.canEdit || invalid}>
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
