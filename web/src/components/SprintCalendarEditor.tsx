import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Alert, Button, Group, Paper, Stack, Text, Title } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { ApiError } from "../api/http";
import { sprintCalendarQueryKey, updateSprintCalendar, type SprintCalendar } from "../api/capacity";
import { calendarCapacityMd, COEFFICIENT_MAX, sameDraft, toCalendarBody, toDraft, withTeamCoefficient } from "../utils/capacity";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { formatMd } from "../utils/reportFormat";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import CapacityNumberInput from "./CapacityNumberInput";
import SprintCalendarGrid from "./SprintCalendarGrid";

/**
 * After a save the server re-derives asynchronously (a queued DERIVE job), so "Capacity in reports"
 * lags: the calendar is re-read a few times, with growing gaps, until that figure moves (about 22 s
 * at most, then it simply stays as of the last derive).
 */
const DERIVE_POLL_DELAYS_MS = [1500, 3000, 6000, 12000];

/**
 * One sprint's capacity calendar: the team coefficient, the live total (the server kernel's formula,
 * `utils/capacity.ts`), the grid, Save and Reset. It owns the draft and mounts per team and sprint —
 * never per `updatedAt`, so a reload can never swallow an edit. The draft is re-seeded EXPLICITLY:
 * after a successful save it becomes the new baseline; when the loaded document changes (a refetch
 * after someone else saved) a clean draft adopts it, while a dirty one is kept and the editor says the
 * calendar changed elsewhere (Reset loads the latest). Dirtiness is reported up (`onDirtyChange`) so
 * the page can ask before a picker discards the draft. Save is a full replace of the document;
 * failures render in the page's fixed vocabulary, never the server's text.
 */
export default function SprintCalendarEditor({
  calendar,
  onDirtyChange,
}: {
  calendar: SprintCalendar;
  onDirtyChange: (dirty: boolean) => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const loaded = useMemo(() => toDraft(calendar), [calendar]);
  // `base` is the document the draft was seeded from (the loaded one, or what the last save stored).
  const [base, setBase] = useState(loaded);
  const [draft, setDraft] = useState(loaded);
  const [seen, setSeen] = useState(calendar);
  const [changedElsewhere, setChangedElsewhere] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pollTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const dirty = useMemo(() => !sameDraft(draft, base), [draft, base]);
  const canEdit = calendar.canEdit;
  const noPeople = draft.people.length === 0;
  // A read-only caller gets no per-person coefficients, so the formula cannot run: the server's figure is the total.
  const total = useMemo(() => calendarCapacityMd(draft) ?? calendar.capacityMd, [draft, calendar.capacityMd]);

  function adoptLoaded() {
    setBase(loaded);
    setDraft(loaded);
    setChangedElsewhere(false);
  }
  if (calendar !== seen) {
    // A new document arrived (the query hands back the same object while nothing changed).
    setSeen(calendar);
    if (!sameDraft(loaded, base)) {
      if (dirty) setChangedElsewhere(true);
      else adoptLoaded();
    }
  } else if (changedElsewhere && !dirty) {
    // The edits were undone by hand: nothing left to protect, take the latest.
    adoptLoaded();
  }

  useEffect(() => {
    onDirtyChange(dirty);
    return () => onDirtyChange(false);
  }, [dirty, onDirtyChange]);
  useEffect(() => () => clearTimeout(pollTimer.current), []);

  const setTeamCoefficient = useCallback((value: number) => setDraft((prev) => withTeamCoefficient(prev, value)), []);

  function pollDerivedFigure() {
    const key = sprintCalendarQueryKey(calendar.teamId, calendar.sprintId);
    const before = { md: calendar.derivedCapacityMd, source: calendar.derivedCapacitySource };
    const attempt = (n: number) => {
      if (n >= DERIVE_POLL_DELAYS_MS.length) return;
      pollTimer.current = setTimeout(() => {
        void queryClient.refetchQueries({ queryKey: key, exact: true }).then(() => {
          const fresh = queryClient.getQueryData<SprintCalendar>(key);
          if (fresh && (fresh.derivedCapacityMd !== before.md || fresh.derivedCapacitySource !== before.source)) return;
          attempt(n + 1);
        });
      }, DERIVE_POLL_DELAYS_MS[n]);
    };
    clearTimeout(pollTimer.current);
    attempt(0);
  }

  async function save() {
    setError(null);
    setSaving(true);
    try {
      const sent = draft;
      await updateSprintCalendar(calendar.teamId, calendar.sprintId, toCalendarBody(sent));
      // The stored document is the new baseline; anything typed while the request ran stays an unsaved edit.
      setBase(sent);
      setChangedElsewhere(false);
      await refreshQueriesAfterMutation(queryClient, ["capacity"]);
      showSuccessToast(t("capacity.toast.saved"));
      pollDerivedFigure();
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
    adoptLoaded();
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
            {changedElsewhere && (
              <Alert color="orange" variant="light">
                {t("capacity.calendar.changedElsewhere")}
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
                disabled={!canEdit || noPeople}
                onCommit={setTeamCoefficient}
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
            {noPeople ? (
              <Text size="sm" c="dimmed">
                {t("capacity.calendar.noPeople")}
              </Text>
            ) : (
              <SprintCalendarGrid draft={draft} canEdit={canEdit} onChange={setDraft} />
            )}
            {!noPeople && total !== null && (
              // Deliberately not a live region: it changes on every keystroke. The success toast announces the save.
              <Group gap="xs" align="baseline">
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
              {/* With nobody on the roster a save would store a zero calendar that overrides the team default. */}
              <Button onClick={() => void save()} loading={saving} disabled={!canEdit || noPeople}>
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
