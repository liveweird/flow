import { useEffect, useId, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Box, Button, Group, Input, Paper, SegmentedControl, Stack, Text, TextInput, Title } from "@mantine/core";
import { IconChevronDown, IconChevronUp } from "@tabler/icons-react";
import { fetchDeepDiveEpics, fetchDeepDiveSprints, type ReportFilters } from "../api/reports";
import { isBoolean, useStoredState } from "../hooks/useStoredState";
import { MAX_DEEP_DIVE_EPICS, MAX_DEEP_DIVE_SPRINTS, type DeepDiveMode, type DeepDiveSelection } from "../utils/deepDiveFilter";
import {
  dateProblem,
  draftOf,
  isMalformedDate,
  isShown,
  issueLabel,
  learnPicks,
  missingOf,
  pruneSprints,
  selectionOf,
  selectionSummary,
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
 * BOTH modes — in both the picker stays disabled (and asks nothing) until a domain is chosen; for sprints the domain is part
 * of the selection, for epics it only picks which epics the picker lists (the selection and the URL stay the epics', and
 * a link's epics without a domain are a valid selection — only ADDING epics needs one). There is no Connection select: each option carries its connection (named in the label where a list spans
 * several), the selection's connection follows from what is picked, and a pick from a second connection blocks Show
 * with its reason. Nothing is requested while the viewer edits: the explicit Show button hands the finished selection
 * to `onShow`, which writes it to the URL. The panel holds its own draft, started from `selection`; a parent that
 * wants it reset (the URL changed under it) gives it a new `key`. Changing the mode drops the picks (they belong to
 * the old mode) and keeps the domain and the dates; changing the domain keeps every epic picked (a selection may span domains) and the sprints that
 * still fit, and says how many sprints it dropped.
 *
 * The form is one aligned row (Domain, the mode's picker, From, To, Show) under the mode switch. Over a selection the URL
 * shows, the panel folds to a one-line summary and an "Edit selection" disclosure button (`aria-expanded`/`aria-controls`); Show
 * and the toggle fold it, the toggle opens it (Show hands focus to the toggle; a `400` for the shown selection opens it), and the last choice is remembered per viewer (`reports.deepDive.selectionFolded`, folded by default).
 * A bare page has nothing to summarize, so its form is always open.
 */
export default function DeepDiveSelectionPanel({
  selection,
  filters,
  labels,
  refused = false,
  onShow,
}: {
  /** What the URL currently shows — the draft's starting point. */
  selection: DeepDiveSelection;
  filters: ReportFilters;
  /** Names of already-selected values (from the loaded report), so a selection opened from a link reads as names. */
  labels: Readonly<Record<string, string>>;
  /** The server refused the shown selection (a `400`): the form opens whatever the stored fold choice. */
  refused?: boolean;
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
  const formId = useId();
  const update = (changes: Partial<DeepDiveDraft>) => setDraft((prev) => ({ ...prev, ...changes }));

  const multi = filters.connections.length > 1;
  const connectionName = (id: number) => filters.connections.find((connection) => connection.id === id)?.name ?? String(id);
  const domainName = (key: string) => filters.domains.find((domain) => domain.domainKey === key)?.domainName ?? key;
  // "Then the options name their connection": only an answer that spans several connections needs the suffix.
  const suffixOf = (items: ReadonlyArray<{ connectionId: number }>) => {
    const several = new Set(items.map((item) => item.connectionId)).size > 1;
    return (connectionId: number) => (several ? ` · ${connectionName(connectionId)}` : "");
  };
  const learned = (field: "label" | "name") =>
    Object.fromEntries(Object.entries(draft.picked).flatMap(([value, facts]) => (facts[field] === undefined ? [] : [[value, facts[field]]])));
  const pick = (field: "sprints" | "epics") => (values: string[], facts: Record<string, PickFacts>) =>
    setDraft((prev) => ({ ...prev, [field]: values, picked: { ...prev.picked, ...facts }, removed: null }));

  // What the draft has learned about its picks (the options' text, a sprint's plain name) outlives the pickers; a loaded report's names refresh them.
  const pillLabels: Record<string, string> = { ...learned("label"), ...labels };
  const summaryNames: Record<string, string> = { ...learned("name"), ...labels };

  const problem = dateProblem(draft);
  const missing = missingOf(draft);
  const domainOptions = filters.domains.map((domain) => ({ value: domain.domainKey, label: domain.domainName }));

  const domainSelect = (
    <ReportFilterSelect
      label={t("reports.filters.domain")}
      placeholder={t("reports.deepDive.panel.pickDomain")}
      data={domainOptions}
      value={draft.domain}
      onChange={(domain) => setDraft((prev) => withDomain(prev, domain))}
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
              name: sprint.name,
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
      labels={pillLabels}
      disabledHint={t("reports.deepDive.panel.needDomain")}
    />
  );

  const epicsPicker = (
    <DeepDivePicker
      label={t("reports.deepDive.panel.epics")}
      placeholder={t("reports.deepDive.panel.pickEpics")}
      scope={["epics", draft.domain]}
      enabled={draft.domain !== null}
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
      labels={pillLabels}
      disabledHint={t("reports.deepDive.panel.needDomain")}
      removableWhenDisabled
    />
  );

  const [folded, setFolded] = useStoredState<boolean>("reports.deepDive.selectionFolded", true, isBoolean);
  // Folds only over a selection the URL shows; a bare page (or an incomplete link) has nothing to summarize, so the form stays open.
  const shown = isShown(selection);
  // A shown selection the server refuses opens the form (for this visit only: the stored choice is untouched) so the way out is on screen.
  const [unfolded, setUnfolded] = useState(false);
  const [seenRefusal, setSeenRefusal] = useState(false);
  if (refused !== seenRefusal) {
    setSeenRefusal(refused);
    if (refused) setUnfolded(true);
  }
  const expanded = !shown || !folded || unfolded;

  // Show folds the form its (focused) button sits in: focus moves to the toggle, the next thing the viewer may want.
  const toggle = useRef<HTMLButtonElement>(null);
  const refocusToggle = useRef(false);
  useEffect(() => {
    if (!expanded && refocusToggle.current) {
      refocusToggle.current = false;
      toggle.current?.focus();
    }
  }, [expanded]);

  const form = (
    <Box mt="md">
    <Stack gap="md">
      <SegmentedControl
        aria-label={t("reports.deepDive.panel.modeAria")}
        value={draft.mode}
        onChange={(mode) => setDraft((prev) => withMode(prev, mode as DeepDiveMode))}
        data={MODES.map((mode) => ({ value: mode, label: t(`reports.deepDive.panel.mode.${mode}`) }))}
        w="fit-content"
        maw="100%"
      />
      {/* One row on one line: every control is a label over a 36px input with its hints UNDER it, so the inputs share a top edge however
          long a hint or an error is; the Show button carries an invisible label of the same height. The row wraps whole controls. */}
      <Group align="flex-start" gap="md" wrap="wrap">
        {domainSelect}
        {draft.mode === "SPRINTS" ? sprintPicker : epicsPicker}
        <TextInput
          label={t("reports.deepDive.panel.from")}
          placeholder={t("common.dateFormatHint")}
          value={draft.from}
          onChange={(event) => update({ from: event.currentTarget.value.trim() })}
          error={isMalformedDate(draft.from) ? t("reports.deepDive.panel.dateFormat") : undefined}
          w={170}
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
          w={170}
        />
        <Input.Wrapper labelElement="div" label={<span aria-hidden="true">&nbsp;</span>}>
          {/* Focusable while blocked (aria-disabled, activation ignored), so its reason is one Tab away. */}
          <Button
            aria-disabled={missing !== null}
            data-disabled={missing !== null || undefined}
            aria-describedby={missingId}
            onClick={() => {
              if (missing !== null) return;
              onShow(selectionOf(draft, multi));
              refocusToggle.current = true;
              setFolded(true);
              setUnfolded(false);
            }}
          >
            {t("reports.deepDive.panel.show")}
          </Button>
        </Input.Wrapper>
      </Group>
      {draft.removed !== null && (
        <Text role="status" aria-live="polite" size="sm" c="dimmed">
          {t(`reports.deepDive.panel.removed.${draft.removed.kind}`, { count: draft.removed.count, domain: draft.removed.domain })}
        </Text>
      )}
    </Stack>
    {/* Always there (Show's aria-describedby, a live region), but outside the Stack's gap: empty, it takes no room. */}
    <Text id={missingId} size="sm" c="dimmed" aria-live="polite" mt={missing === null ? 0 : "md"}>
      {missing === null ? "" : t(`reports.deepDive.panel.missing.${missing}`)}
    </Text>
    </Box>
  );

  return (
    <Paper component="section" withBorder p="md" data-print="hide">
      <Group justify="space-between" align="flex-start" wrap="wrap" gap="sm">
        <Stack gap={2} flex="1 1 16rem" miw={0}>
          <Title order={3} size="h4">
            {t("reports.deepDive.panel.title")}
          </Title>
          {!expanded && (
            <Text size="sm" c="dimmed">
              {selectionSummary(selection, summaryNames, domainName, t)}
            </Text>
          )}
        </Stack>
        {shown && (
          // The standard disclosure: ONE name, "Edit selection", for both states (visible text = accessible name); the state is aria-expanded and the chevron.
          <Button
            ref={toggle}
            variant="default"
            size="xs"
            aria-expanded={expanded}
            aria-controls={formId}
            rightSection={expanded ? <IconChevronUp size={14} aria-hidden="true" /> : <IconChevronDown size={14} aria-hidden="true" />}
            onClick={() => {
              refocusToggle.current = false;
              setFolded(expanded);
              setUnfolded(false);
            }}
          >
            {t("reports.deepDive.panel.edit")}
          </Button>
        )}
      </Group>
      <div id={formId}>{expanded && form}</div>
    </Paper>
  );
}
