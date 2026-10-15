import { useState } from "react";
import { Alert, Button, Group, Paper, Stack, Text, Title } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { ApiError } from "../api/http";
import { updateSprintCalendar, type SprintCalendar } from "../api/capacity";
import {
  calendarCapacityMd,
  COEFFICIENT_MAX,
  sameDraft,
  toCalendarBody,
  toDraft,
  withTeamCoefficient,
} from "../utils/capacity";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { formatMd } from "../utils/reportFormat";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import CapacityNumberInput from "./CapacityNumberInput";
import SprintCalendarGrid from "./SprintCalendarGrid";

/**
 * One sprint's capacity calendar: the team coefficient, the live total (the server kernel's formula,
 * `utils/capacity.ts`), the grid, Save and Reset. It owns the draft and mounts per loaded document
 * (the page keys it by team, sprint and `updatedAt`), so a refetch that changes nothing never
 * clobbers an edit in progress. Save is a full replace of the document; failures render in the
 * page's fixed vocabulary, never the server's text. No discard guard yet (`useDiscardGuard` is not ported).
 */
export default function SprintCalendarEditor({ calendar }: { calendar: SprintCalendar }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState(() => toDraft(calendar));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const loaded = toDraft(calendar);
  const dirty = !sameDraft(draft, loaded);
  const canEdit = calendar.canEdit;
  // A read-only caller gets no per-person coefficients, so the formula cannot run: the server's figure is the total.
  const total = calendarCapacityMd(draft) ?? calendar.capacityMd;

  async function save() {
    setError(null);
    setSaving(true);
    try {
      await updateSprintCalendar(calendar.teamId, calendar.sprintId, toCalendarBody(draft));
      await refreshQueriesAfterMutation(queryClient, ["capacity"]);
      showSuccessToast(t("capacity.toast.saved"));
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          forbidden: "capacity.error.forbidden",
          notFound: "capacity.error.notFound",
          conflict: "capacity.error.conflict",
          invalid: "capacity.error.invalidCalendar",
          failed: "capacity.error.failed",
        }),
      );
      // A rejected or vanished document means the loaded one is stale: refetch, so Reset picks up the current sprint.
      if (err instanceof ApiError && [400, 404, 409].includes(err.status)) await refreshQueriesAfterMutation(queryClient, ["capacity"]);
    } finally {
      setSaving(false);
    }
  }

  function reset() {
    setDraft(loaded);
    setError(null);
  }

  const span = calendar.window;
  return (
    <Paper withBorder p="md">
      <Stack gap="md">
        <Stack gap={2}>
          <Title order={3} size="h4">
            {t("capacity.calendar.title")}
          </Title>
          <Text size="sm" c="dimmed">
            {calendar.sprintName}
            {span ? ` · ${span.from} → ${span.to}` : ""}
          </Text>
        </Stack>
        {span === null ? (
          <Alert color="gray" variant="light">
            {t("capacity.calendar.noWindow")}
          </Alert>
        ) : (
          <>
            {!canEdit && (
              <Alert color="gray" variant="light">
                {t("capacity.calendar.readOnly")}
              </Alert>
            )}
            {!calendar.hasCalendar && (
              <Alert color="gray" variant="light">
                {t("capacity.calendar.defaults")}
              </Alert>
            )}
            <Group align="flex-end" justify="space-between" wrap="wrap" gap="md">
              <CapacityNumberInput
                label={t("capacity.calendar.teamCoefficient")}
                description={t("capacity.calendar.teamCoefficientHint")}
                w={220}
                min={0}
                max={COEFFICIENT_MAX}
                step={0.1}
                value={draft.teamCoefficient}
                disabled={!canEdit}
                onCommit={(value) => setDraft(withTeamCoefficient(draft, value))}
              />
              <Text size="sm" fw={500}>
                {calendar.derivedCapacityMd !== null && calendar.derivedCapacitySource !== null
                  ? t("capacity.calendar.derived", {
                      md: formatMd(calendar.derivedCapacityMd),
                      source: t(`capacity.source.${calendar.derivedCapacitySource}`),
                    })
                  : t("capacity.calendar.derivedNone")}
              </Text>
            </Group>
            {draft.people.length === 0 ? (
              <Text size="sm" c="dimmed">
                {t("capacity.calendar.noPeople")}
              </Text>
            ) : (
              <SprintCalendarGrid draft={draft} canEdit={canEdit} onChange={setDraft} />
            )}
            {draft.people.length > 0 && total !== null && (
              <Group gap="xs" align="baseline" aria-live="polite">
                <Text fw={600}>{t("capacity.calendar.total", { md: formatMd(total) })}</Text>
                {(!calendar.hasCalendar || dirty) && canEdit && (
                  <Text size="sm" c="dimmed">
                    {t("capacity.calendar.totalHint")}
                  </Text>
                )}
              </Group>
            )}
            {error && (
              <Alert color="red" variant="light">
                {error}
              </Alert>
            )}
            <Group gap="sm">
              <Button onClick={() => void save()} loading={saving} disabled={!canEdit}>
                {t("capacity.calendar.save")}
              </Button>
              <Button variant="default" onClick={reset} disabled={!dirty || saving}>
                {t("capacity.calendar.reset")}
              </Button>
            </Group>
          </>
        )}
      </Stack>
    </Paper>
  );
}
