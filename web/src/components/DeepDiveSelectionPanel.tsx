import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, Paper, SegmentedControl, Stack, Text, TextInput, Title } from "@mantine/core";
import { fetchDeepDiveEpics, fetchDeepDiveSprints, type ReportFilters } from "../api/reports";
import { MAX_DEEP_DIVE_EPICS, MAX_DEEP_DIVE_SPRINTS, type DeepDiveMode, type DeepDiveSelection } from "../utils/deepDiveFilter";
import {
  dateProblem,
  draftOf,
  isMalformedDate,
  issueLabel,
  learnPicks,
  missingOf,
  pruneSprints,
  selectionOf,
  withDomain,
  withMode,
  type DeepDiveDraft,
  type PickFacts,
} from "../utils/deepDivePanel";
import { sprintLabel } from "../utils/sprintLabel";
import DeepDivePicker, { type PickerPage } from "./DeepDivePicker";
import ReportFilterSelect from "./ReportFilterSelect";

/** The widest option list a picker asks for (the API's page-size ceiling); more matches are reached by typing. */
const PICKER_PAGE_SIZE = 100;
const MODES: readonly DeepDiveMode[] = ["SPRINTS", "EPICS"];

/**
 * The Deep dive's selection: two modes (sprints of a domain, epics), each with its own picker that searches the server
 * as the viewer types, and an optional date range that clips the result. The Domain select sits above the picker in
 * BOTH modes — it is required for sprints and, for epics, only narrows the epic list (the selection and the URL stay the
 * epics'). There is no Connection select: each option carries its connection (named in the label where a list spans
 * several), the selection's connection follows from what is picked, and a pick from a second connection blocks Show
 * with its reason. Nothing is requested while the viewer edits: the explicit Show button hands the finished selection
 * to `onShow`, which writes it to the URL. The panel holds its own draft, started from `selection`; a parent that
 * wants it reset (the URL changed under it) gives it a new `key`. Changing the mode drops the picks (they belong to
 * the old mode) and keeps the domain and the dates; changing the domain keeps the picks that still fit and says how
 * many it dropped.
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

  const multi = filters.connections.length > 1;
  const connectionName = (id: number) => filters.connections.find((connection) => connection.id === id)?.name ?? String(id);
  const domainName = (key: string) => filters.domains.find((domain) => domain.domainKey === key)?.domainName ?? key;
  // "Then the options name their connection": only an answer that spans several connections needs the suffix.
  const suffixOf = (items: ReadonlyArray<{ connectionId: number }>) => {
    const several = new Set(items.map((item) => item.connectionId)).size > 1;
    return (connectionId: number) => (several ? ` · ${connectionName(connectionId)}` : "");
  };
  const pick = (field: "sprints" | "epics") => (values: string[], facts: Record<string, PickFacts>) =>
    setDraft((prev) => ({ ...prev, [field]: values, picked: { ...prev.picked, ...facts }, removed: null }));

  const problem = dateProblem(draft);
  const missing = missingOf(draft);
  const domainOptions = filters.domains.map((domain) => ({ value: domain.domainKey, label: domain.domainName }));

  const domainSelect = (
    <ReportFilterSelect
      label={t("reports.filters.domain")}
      placeholder={t(draft.mode === "SPRINTS" ? "reports.deepDive.panel.pickDomain" : "reports.deepDive.panel.anyDomain")}
      data={domainOptions}
      value={draft.domain}
      onChange={(domain) => setDraft((prev) => withDomain(prev, domain, domain === null ? "" : domainName(domain)))}
    />
  );

  const loadedDomain = draft.domain;
  const sprintPicker = (
    <DeepDivePicker
      label={t("reports.deepDive.panel.sprints")}
      placeholder={t("reports.deepDive.panel.pickSprints")}
      scope={["sprints", draft.domain]}
      enabled={draft.domain !== null}
      load={(q) =>
        fetchDeepDiveSprints({ domain: draft.domain ?? "", q, pageSize: PICKER_PAGE_SIZE }).then((page): PickerPage => {
          const suffix = suffixOf(page.items);
          return {
            options: page.items.map((sprint) => ({
              value: String(sprint.id),
              label: sprintLabel(sprint, filters.timeZone) + suffix(sprint.connectionId),
              connectionId: sprint.connectionId,
            })),
            total: page.total,
          };
        })
      }
      onOptions={(page, q) =>
        // An answer for a domain the draft has since left says nothing about the draft's sprints.
        setDraft((prev) => (prev.domain === loadedDomain ? learnPicks(pruneSprints(prev, page, q, domainName(loadedDomain ?? "")), page.options) : prev))
      }
      value={draft.sprints}
      connectionOf={(value) => draft.picked[value]?.connectionId}
      onChange={pick("sprints")}
      max={MAX_DEEP_DIVE_SPRINTS}
      labels={labels}
      disabledHint={t("reports.deepDive.panel.needDomain")}
    />
  );

  const epicsPicker = (
    <DeepDivePicker
      label={t("reports.deepDive.panel.epics")}
      placeholder={t("reports.deepDive.panel.pickEpics")}
      scope={["epics", draft.domain]}
      load={(q) =>
        fetchDeepDiveEpics({ domain: draft.domain ?? undefined, q, pageSize: PICKER_PAGE_SIZE }).then((page): PickerPage => {
          const suffix = suffixOf(page.items);
          return {
            options: page.items.map((epic) => ({
              value: epic.key,
              label: issueLabel(epic.key, epic.summary) + suffix(epic.connectionId),
              connectionId: epic.connectionId,
              domain: epic.domain,
            })),
            total: page.total,
          };
        })
      }
      onOptions={(page) => setDraft((prev) => learnPicks(prev, page.options))}
      value={draft.epics}
      connectionOf={(value) => draft.picked[value]?.connectionId}
      onChange={pick("epics")}
      max={MAX_DEEP_DIVE_EPICS}
      labels={labels}
    />
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
          {domainSelect}
          {draft.mode === "SPRINTS" ? sprintPicker : epicsPicker}
        </Group>
        {draft.removed !== null && (
          <Text role="status" aria-live="polite" size="sm" c="dimmed">
            {t(`reports.deepDive.panel.removed.${draft.removed.kind}`, { count: draft.removed.count, domain: draft.removed.domain })}
          </Text>
        )}
        <Group align="flex-start" gap="md" wrap="wrap">
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
            onClick={() => missing === null && onShow(selectionOf(draft, multi))}
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
