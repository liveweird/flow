import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, Paper, SegmentedControl, Stack, Text, TextInput, Title } from "@mantine/core";
import { fetchDeepDiveEpics, fetchDeepDiveEpicTasks, fetchDeepDiveSprints, type ReportFilters } from "../api/reports";
import {
  MAX_DEEP_DIVE_EPICS,
  MAX_DEEP_DIVE_ISSUES,
  MAX_DEEP_DIVE_SPRINTS,
  type DeepDiveMode,
  type DeepDiveSelection,
} from "../utils/deepDiveFilter";
import { dateProblem, draftOf, isMalformedDate, issueLabel, missingOf, selectionOf, withMode, type DeepDiveDraft } from "../utils/deepDivePanel";
import DeepDivePicker, { type PickerPage } from "./DeepDivePicker";
import ReportFilterSelect from "./ReportFilterSelect";

/** The widest option list a picker asks for (the API's page-size ceiling); more matches are reached by typing. */
const PICKER_PAGE_SIZE = 100;
const MODES: readonly DeepDiveMode[] = ["SPRINTS", "EPICS", "TASKS"];

const issuePage = (page: { items: { key: string; summary: string | null }[]; total: number }): PickerPage => ({
  options: page.items.map((item) => ({ value: item.key, label: issueLabel(item.key, item.summary) })),
  total: page.total,
});

/**
 * The Deep dive's selection: three modes (sprints of a domain, epics, handpicked tasks of one epic), each with its
 * own pickers that search the server as the viewer types, an optional connection (only where there are several) and
 * an optional date range that clips the result. Nothing is requested while the viewer edits: the explicit Show button
 * hands the finished selection to `onShow`, which writes it to the URL. The panel holds its own draft, started from
 * `selection`; a parent that wants it reset (the URL changed under it) gives it a new `key`. Changing the mode drops
 * the picks (they belong to the old mode) and keeps the connection and the dates; changing the domain, the epic or the
 * connection drops the picks that were listed under the old one.
 */
export default function DeepDiveSelectionPanel({
  selection,
  filters,
  labels,
  onShow,
}: {
  /** What the URL currently shows — the draft's starting point. */
  selection: DeepDiveSelection;
  filters: ReportFilters;
  /** Names of already-selected values (from the loaded report), so a selection opened from a link reads as names. */
  labels: Readonly<Record<string, string>>;
  onShow: (selection: DeepDiveSelection) => void;
}) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState<DeepDiveDraft>(() =>
    draftOf(
      selection,
      filters.connections.map((connection) => connection.id),
    ),
  );
  const missingId = useId();
  const update = (changes: Partial<DeepDiveDraft>) => setDraft((prev) => ({ ...prev, ...changes }));

  const connectionId = draft.connectionId === null ? undefined : Number(draft.connectionId);
  const problem = dateProblem(draft);
  const epic = draft.epics[0];
  const missing = missingOf(draft);

  const sprintPicker = (
    <>
      <ReportFilterSelect
        label={t("reports.filters.domain")}
        placeholder={t("reports.deepDive.panel.pickDomain")}
        data={filters.domains.map((domain) => ({ value: domain.domainKey, label: domain.domainName }))}
        value={draft.domain}
        onChange={(domain) => update({ domain, sprints: [] })}
      />
      <DeepDivePicker
        label={t("reports.deepDive.panel.sprints")}
        placeholder={t("reports.deepDive.panel.pickSprints")}
        scope={["sprints", connectionId ?? null, draft.domain]}
        enabled={draft.domain !== null}
        load={(q) =>
          fetchDeepDiveSprints({ domain: draft.domain ?? "", q, connectionId, pageSize: PICKER_PAGE_SIZE }).then((page) => ({
            options: page.items.map((sprint) => ({ value: String(sprint.id), label: sprint.name })),
            total: page.total,
          }))
        }
        value={draft.sprints}
        onChange={(sprints) => update({ sprints })}
        max={MAX_DEEP_DIVE_SPRINTS}
        labels={labels}
        disabledHint={t("reports.deepDive.panel.needDomain")}
      />
    </>
  );

  const epicLoader = (q: string | undefined) => fetchDeepDiveEpics({ q, connectionId, pageSize: PICKER_PAGE_SIZE }).then(issuePage);

  const epicsPicker = (
    <DeepDivePicker
      label={t("reports.deepDive.panel.epics")}
      placeholder={t("reports.deepDive.panel.pickEpics")}
      scope={["epics", connectionId ?? null]}
      load={epicLoader}
      value={draft.epics}
      onChange={(epics) => update({ epics })}
      max={MAX_DEEP_DIVE_EPICS}
      labels={labels}
    />
  );

  const tasksPicker = (
    <>
      <DeepDivePicker
        label={t("reports.deepDive.panel.epic")}
        placeholder={t("reports.deepDive.panel.pickEpic")}
        scope={["epics", connectionId ?? null]}
        load={epicLoader}
        value={draft.epics}
        onChange={(epics) => update({ epics, tasks: [] })}
        labels={labels}
      />
      <DeepDivePicker
        label={t("reports.deepDive.panel.tasks")}
        placeholder={t("reports.deepDive.panel.pickTasks")}
        scope={["epicTasks", connectionId ?? null, epic]}
        enabled={epic !== undefined}
        load={(q) => fetchDeepDiveEpicTasks(epic ?? "", { q, connectionId, pageSize: PICKER_PAGE_SIZE }).then(issuePage)}
        value={draft.tasks}
        onChange={(tasks) => update({ tasks })}
        max={MAX_DEEP_DIVE_ISSUES}
        labels={labels}
        disabledHint={t("reports.deepDive.panel.needEpic")}
      />
    </>
  );

  return (
    <Paper component="section" withBorder p="md">
      <Stack gap="md">
        <Title order={3} size="h4">
          {t("reports.deepDive.panel.title")}
        </Title>
        <SegmentedControl
          aria-label={t("reports.deepDive.panel.modeAria")}
          value={draft.mode}
          onChange={(mode) => setDraft((prev) => withMode(prev, mode as DeepDiveMode))}
          data={MODES.map((mode) => ({ value: mode, label: t(`reports.deepDive.panel.mode.${mode}`) }))}
        />
        <Group align="flex-start" gap="md" wrap="wrap">
          {draft.mode === "SPRINTS" && sprintPicker}
          {draft.mode === "EPICS" && epicsPicker}
          {draft.mode === "TASKS" && tasksPicker}
        </Group>
        <Group align="flex-start" gap="md" wrap="wrap">
          {filters.connections.length > 1 && (
            <ReportFilterSelect
              label={t("reports.filters.connection")}
              placeholder={t("reports.filters.allConnections")}
              data={filters.connections.map((connection) => ({ value: String(connection.id), label: connection.name }))}
              value={draft.connectionId}
              onChange={(next) => update({ connectionId: next, sprints: [], epics: [], tasks: [] })}
            />
          )}
          <TextInput
            label={t("reports.deepDive.panel.from")}
            placeholder={t("common.dateFormatHint")}
            value={draft.from}
            onChange={(event) => update({ from: event.currentTarget.value.trim() })}
            error={isMalformedDate(draft.from) ? t("reports.deepDive.panel.dateFormat") : undefined}
            w={160}
          />
          <TextInput
            label={t("reports.deepDive.panel.to")}
            placeholder={t("common.dateFormatHint")}
            value={draft.to}
            onChange={(event) => update({ to: event.currentTarget.value.trim() })}
            error={
              isMalformedDate(draft.to)
                ? t("reports.deepDive.panel.dateFormat")
                : problem === "range"
                  ? t("reports.deepDive.panel.dateRange")
                  : undefined
            }
            w={160}
          />
        </Group>
        <Group gap="md" align="center">
          {/* Focusable while blocked (aria-disabled, activation ignored), so its reason is one Tab away. */}
          <Button
            aria-disabled={missing !== null}
            data-disabled={missing !== null || undefined}
            aria-describedby={missingId}
            onClick={() => missing === null && onShow(selectionOf(draft))}
          >
            {t("reports.deepDive.panel.show")}
          </Button>
          <Text id={missingId} size="sm" c="dimmed" aria-live="polite">
            {missing === null ? "" : t(`reports.deepDive.panel.missing.${missing}`)}
          </Text>
        </Group>
      </Stack>
    </Paper>
  );
}
