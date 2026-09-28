import { useState } from "react";
import { useTranslation } from "react-i18next";
import type { ParseKeys, TFunction } from "i18next";
import { Alert, Badge, Box, Button, Group, NumberInput, Select, Stack, Tabs, Text } from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useParams } from "react-router-dom";
import { ApiError } from "../api/http";
import {
  getDataSourceMetricsConfig,
  getDataSourceMetricsConfigOptions,
  updateDataSourceMetricsConfig,
  type MetricsFieldOption,
} from "../api/metrics";
import { listTeams } from "../api/teams";
import { useAdmin } from "../auth";
import EditPageLoadState from "../components/EditPageLoadState";
import MappingTable, { type MappingField, type MappingRow } from "../components/MappingTable";
import PageHeader from "../components/PageHeader";
import { dataSourcePath } from "../utils/dataSourceLinks";
import { CONTENT_MAX_WIDTH } from "../utils/layout";
import {
  buildInitialState,
  buildRequest,
  changedBoardIds,
  mergeWorkCategoryValues,
  METRICS_STAGES,
  setDomainKeyForProject,
  setOwnerTeamForDomainGroup,
  type ActivityRowState,
  type BoardRowState,
  type CapacityRowState,
  type DomainRowState,
  type FieldsState,
  type MetricsConfigFormState,
  type StatusRowState,
  type WorkCategoryRowState,
} from "../utils/metricsConfigForm";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";

const TEAMS_PAGE_SIZE = 100;

function StatusesTab({
  t,
  statuses,
  onChange,
}: {
  t: TFunction;
  statuses: StatusRowState[];
  onChange: (next: StatusRowState[]) => void;
}) {
  const stageOptions = METRICS_STAGES.map((s) => ({ value: s, label: t(`metrics.config.statuses.stage.${s}`) }));
  const rows: MappingRow[] = statuses.map((row) => ({
    id: row.statusId,
    label: (
      <Group gap="xs" wrap="nowrap">
        <Text size="sm">{row.name}</Text>
        <Badge variant="light" size="sm">
          {t(`dataSources.profile.category.${row.category}` as ParseKeys)}
        </Badge>
      </Group>
    ),
    fields: [
      {
        type: "select",
        ariaLabel: t("metrics.config.statuses.stageAria", { name: row.name }),
        value: row.stage,
        options: stageOptions,
        placeholder: t("metrics.config.statuses.stagePlaceholder"),
        onChange: (value) =>
          onChange(statuses.map((s) => (s.statusId === row.statusId ? { ...s, stage: (value as StatusRowState["stage"]) || "" } : s))),
      },
      {
        type: "checkbox",
        ariaLabel: t("metrics.config.statuses.blockedAria", { name: row.name }),
        checked: row.blocked,
        onChange: (checked) => onChange(statuses.map((s) => (s.statusId === row.statusId ? { ...s, blocked: checked } : s))),
      },
    ] satisfies MappingField[],
  }));
  return (
    <MappingTable
      idColumnLabel={t("metrics.config.statuses.columnStatus")}
      fieldColumnLabels={[t("metrics.config.statuses.columnStage"), t("metrics.config.statuses.columnBlocked")]}
      rows={rows}
      emptyMessage={t("metrics.config.statuses.empty")}
    />
  );
}

function FieldsTab({
  t,
  fields,
  fieldOptions,
  onChange,
}: {
  t: TFunction;
  fields: FieldsState;
  fieldOptions: MetricsFieldOption[];
  onChange: (next: FieldsState) => void;
}) {
  const data = [
    { value: "duedate", label: t("metrics.config.fields.dueDateOption") },
    ...fieldOptions.map((f) => ({ value: f.fieldId, label: `${f.name} (${t(`dataSources.profile.fieldRole.${f.detectedRole}` as ParseKeys)})` })),
  ];
  const slots: { key: keyof FieldsState; label: string }[] = [
    { key: "estimateTask", label: t("metrics.config.fields.estimateTask") },
    { key: "estimateEpic", label: t("metrics.config.fields.estimateEpic") },
    { key: "epicStart", label: t("metrics.config.fields.epicStart") },
    { key: "epicDue", label: t("metrics.config.fields.epicDue") },
    { key: "workCategory", label: t("metrics.config.fields.workCategory") },
  ];
  return (
    <Stack maw={480}>
      {slots.map((slot) => (
        <Select
          key={slot.key}
          label={slot.label}
          data={data}
          value={fields[slot.key] || null}
          onChange={(value) => onChange({ ...fields, [slot.key]: value ?? "" })}
          placeholder={t("metrics.config.fields.none")}
          clearable
          searchable
        />
      ))}
    </Stack>
  );
}

function DomainsTab({
  t,
  domains,
  teamOptions,
  onChange,
}: {
  t: TFunction;
  domains: DomainRowState[];
  teamOptions: { value: string; label: string }[];
  onChange: (next: DomainRowState[]) => void;
}) {
  const rows: MappingRow[] = domains.map((row) => ({
    id: row.projectKey,
    label: row.projectKey,
    fields: [
      {
        type: "text",
        ariaLabel: t("metrics.config.domains.domainKeyAria", { project: row.projectKey }),
        value: row.domainKey,
        onChange: (value) => onChange(setDomainKeyForProject(domains, row.projectKey, value)),
      },
      {
        type: "text",
        ariaLabel: t("metrics.config.domains.domainNameAria", { project: row.projectKey }),
        value: row.domainName,
        onChange: (value) => onChange(domains.map((d) => (d.projectKey === row.projectKey ? { ...d, domainName: value } : d))),
      },
      {
        type: "select",
        ariaLabel: t("metrics.config.domains.ownerTeamAria", { project: row.projectKey }),
        value: row.ownerTeamId,
        options: teamOptions,
        placeholder: t("metrics.config.domains.ownerTeamPlaceholder"),
        onChange: (value) => onChange(setOwnerTeamForDomainGroup(domains, row.projectKey, value)),
      },
    ] satisfies MappingField[],
  }));
  return (
    <MappingTable
      idColumnLabel={t("metrics.config.domains.columnProject")}
      fieldColumnLabels={[
        t("metrics.config.domains.columnDomainKey"),
        t("metrics.config.domains.columnDomainName"),
        t("metrics.config.domains.columnOwnerTeam"),
      ]}
      rows={rows}
      emptyMessage={t("metrics.config.domains.empty")}
    />
  );
}

function BoardsTab({
  t,
  boards,
  teamOptions,
  boardErrors,
  onChange,
}: {
  t: TFunction;
  boards: BoardRowState[];
  teamOptions: { value: string; label: string }[];
  boardErrors: Record<number, string>;
  onChange: (next: BoardRowState[]) => void;
}) {
  const rows: MappingRow[] = boards.map((row) => ({
    id: String(row.boardId),
    label: row.name,
    fields: [
      {
        type: "select",
        ariaLabel: t("metrics.config.boards.teamAria", { board: row.name }),
        value: row.teamId,
        options: teamOptions,
        placeholder: t("metrics.config.boards.teamPlaceholder"),
        error: boardErrors[row.boardId],
        onChange: (value) => onChange(boards.map((b) => (b.boardId === row.boardId ? { ...b, teamId: value } : b))),
      },
    ] satisfies MappingField[],
  }));
  return (
    <MappingTable
      idColumnLabel={t("metrics.config.boards.columnBoard")}
      fieldColumnLabels={[t("metrics.config.boards.columnTeam")]}
      rows={rows}
      emptyMessage={t("metrics.config.boards.empty")}
    />
  );
}

function ActivityTypesTab({
  t,
  activityTypes,
  onChange,
}: {
  t: TFunction;
  activityTypes: ActivityRowState[];
  onChange: (next: ActivityRowState[]) => void;
}) {
  const rows: MappingRow[] = activityTypes.map((row) => ({
    id: row.issueType,
    label: row.issueType,
    fields: [
      {
        type: "text",
        ariaLabel: t("metrics.config.activityTypes.activityTypeAria", { issueType: row.issueType }),
        value: row.activityType,
        onChange: (value) =>
          onChange(activityTypes.map((a) => (a.issueType === row.issueType ? { ...a, activityType: value } : a))),
      },
    ] satisfies MappingField[],
  }));
  return (
    <MappingTable
      idColumnLabel={t("metrics.config.activityTypes.columnIssueType")}
      fieldColumnLabels={[t("metrics.config.activityTypes.columnActivityType")]}
      rows={rows}
      emptyMessage={t("metrics.config.activityTypes.empty")}
    />
  );
}

function WorkCategoriesTab({
  t,
  workCategoryField,
  workCategories,
  truncated,
  onChange,
}: {
  t: TFunction;
  workCategoryField: string;
  workCategories: WorkCategoryRowState[];
  truncated: boolean;
  onChange: (next: WorkCategoryRowState[]) => void;
}) {
  if (!workCategoryField) {
    return (
      <Text size="sm" c="dimmed">
        {t("metrics.config.workCategories.chooseField")}
      </Text>
    );
  }
  const rows: MappingRow[] = workCategories.map((row) => ({
    id: row.valueId,
    label: row.valueName ?? row.valueId,
    fields: [
      {
        type: "text",
        ariaLabel: t("metrics.config.workCategories.categoryAria", { value: row.valueName ?? row.valueId }),
        value: row.category,
        onChange: (value) =>
          onChange(workCategories.map((w) => (w.valueId === row.valueId ? { ...w, category: value } : w))),
      },
    ] satisfies MappingField[],
  }));
  return (
    <Stack gap="xs">
      {truncated && (
        <Text size="xs" c="dimmed">
          {t("metrics.config.workCategories.truncated")}
        </Text>
      )}
      <MappingTable
        idColumnLabel={t("metrics.config.workCategories.columnValue")}
        fieldColumnLabels={[t("metrics.config.workCategories.columnCategory")]}
        rows={rows}
        emptyMessage={t("metrics.config.workCategories.empty")}
      />
    </Stack>
  );
}

function CapacitiesTab({
  t,
  sprintCapacities,
  onChange,
}: {
  t: TFunction;
  sprintCapacities: CapacityRowState[];
  onChange: (next: CapacityRowState[]) => void;
}) {
  if (sprintCapacities.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        {t("metrics.config.capacities.empty")}
      </Text>
    );
  }
  return (
    <Stack gap="xs">
      <Text size="xs" c="dimmed">
        {t("metrics.config.capacities.capacityHint")}
      </Text>
      <Stack gap="sm" maw={360}>
        {sprintCapacities.map((row) => (
          <NumberInput
            key={row.sprintId}
            label={`${row.name} (${row.state})`}
            aria-label={t("metrics.config.capacities.capacityAria", { sprint: row.name })}
            placeholder={t("metrics.config.capacities.columnCapacity")}
            min={0}
            value={row.capacityMd === "" ? "" : Number(row.capacityMd)}
            onChange={(value) =>
              onChange(
                sprintCapacities.map((c) =>
                  c.sprintId === row.sprintId ? { ...c, capacityMd: value === "" ? "" : String(value) } : c,
                ),
              )
            }
          />
        ))}
      </Stack>
    </Stack>
  );
}

/**
 * The per-connection metrics-configuration editor (`/data-sources/:id/metrics-config`, ADMIN
 * only — `.claude/docs/metrics.md`, v0.3.0 M2 commit 6): seven tabs over the ONE composite
 * `DataSourceMetricsConfig` resource, ONE Save doing a full-replace PUT. `configured: false`
 * (nothing stored yet) shows a banner over the computed defaults the GET already returns.
 */
export default function DataSourceMetricsConfig() {
  const { t } = useTranslation();
  const { id: idParam } = useParams();
  const id = Number(idParam);
  const idIsValid = Number.isFinite(id) && id > 0;
  const admin = useAdmin();
  const queryClient = useQueryClient();

  const configQuery = useQuery({
    queryKey: ["dataSources", "metricsConfig", id],
    queryFn: () => getDataSourceMetricsConfig(id),
    enabled: idIsValid && admin,
  });
  const optionsQuery = useQuery({
    queryKey: ["dataSources", "metricsConfigOptions", id],
    queryFn: () => getDataSourceMetricsConfigOptions(id),
    enabled: idIsValid && admin,
  });
  const teamsQuery = useQuery({
    queryKey: ["teams", "all-active"],
    queryFn: () => listTeams({ page: 1, pageSize: TEAMS_PAGE_SIZE, sort: "name" }),
    enabled: admin,
  });

  const [state, setState] = useState<MetricsConfigFormState | null>(null);
  const [originalBoards, setOriginalBoards] = useState<BoardRowState[]>([]);
  const [boardErrors, setBoardErrors] = useState<Record<number, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // Derived, not effect-set (the guarded-initialize idiom): both queries resolved, not yet built.
  if (!state && configQuery.data && optionsQuery.data) {
    const initial = buildInitialState(configQuery.data, optionsQuery.data);
    setState(initial);
    setOriginalBoards(initial.boards);
  }

  const workCategoryField = state?.fields.workCategory ?? "";
  const workCategoryOptionsQuery = useQuery({
    queryKey: ["dataSources", "metricsConfigOptions", id, "workCategoryValues", workCategoryField],
    queryFn: () => getDataSourceMetricsConfigOptions(id, workCategoryField),
    enabled: idIsValid && admin && workCategoryField !== "",
  });

  // Merge freshly-resolved work-category values into the form the moment they arrive or the
  // chosen field changes — guarded so it only ever ADDS/refreshes rows, never during a render
  // that already has the same set (an infinite-update guard, not a debounce).
  if (state && workCategoryField && workCategoryOptionsQuery.data) {
    const merged = mergeWorkCategoryValues(
      state.workCategories,
      workCategoryOptionsQuery.data.workCategoryValues,
      configQuery.data?.workCategories ?? [],
    );
    const currentIds = state.workCategories.map((w) => w.valueId).join(",");
    const mergedIds = merged.map((w) => w.valueId).join(",");
    if (currentIds !== mergedIds) {
      setState({ ...state, workCategories: merged });
    }
  }

  async function onSave() {
    if (!state) return;
    setError(null);
    setBoardErrors({});
    setSubmitting(true);
    try {
      await updateDataSourceMetricsConfig(id, buildRequest(state));
      await refreshQueriesAfterMutation(queryClient, ["dataSources", "metricsConfig", id]);
      setOriginalBoards(state.boards);
      showSuccessToast(t("metrics.config.toast.saved"));
    } catch (err) {
      if (err instanceof ApiError && (err.status === 400 || err.status === 409)) {
        setError(err.detail ?? t("metrics.config.saveFailedGeneric"));
        if (err.status === 409 && state) {
          const changed = changedBoardIds(originalBoards, state.boards);
          const nextErrors: Record<number, string> = {};
          for (const boardId of changed) nextErrors[boardId] = err.detail ?? t("metrics.config.saveConflict");
          setBoardErrors(nextErrors);
        }
      } else if (err instanceof ApiError && err.status === 403) {
        setError(t("metrics.config.saveForbidden"));
      } else {
        setError(t("metrics.config.saveFailedGeneric"));
      }
    } finally {
      setSubmitting(false);
    }
  }

  if (
    configQuery.isLoading ||
    optionsQuery.isLoading ||
    configQuery.isError ||
    optionsQuery.isError ||
    !state ||
    !configQuery.data ||
    !optionsQuery.data
  ) {
    const notFound =
      (configQuery.error instanceof ApiError && configQuery.error.status === 404) ||
      (optionsQuery.error instanceof ApiError && optionsQuery.error.status === 404);
    return (
      <EditPageLoadState
        isLoading={configQuery.isLoading || optionsQuery.isLoading}
        message={notFound ? t("dataSources.notFound") : t("metrics.config.loadFailedGeneric")}
        backTo={dataSourcePath(id)}
        backLabel={t("dataSources.details.backToDetails")}
      />
    );
  }

  // Non-null: the early return above only falls through once both queries have resolved successfully.
  const config = configQuery.data;
  const options = optionsQuery.data;
  const teamOptions = (teamsQuery.data?.items ?? []).map((team) => ({ value: String(team.id), label: team.name }));

  return (
    <Stack gap="md">
      <PageHeader
        title={t("metrics.config.title")}
        backTo={{ to: dataSourcePath(id), label: t("dataSources.details.backToDetails") }}
        actions={
          <Button onClick={() => void onSave()} loading={submitting}>
            {t("common.action.save")}
          </Button>
        }
      />

      {!config.configured && (
        <Alert color="orange" variant="light">
          {t("metrics.config.notConfiguredBanner")}
        </Alert>
      )}
      {error && (
        <Alert color="red" variant="light">
          {error}
        </Alert>
      )}

      <Box maw={CONTENT_MAX_WIDTH}>
        <Tabs defaultValue="statuses" keepMounted>
          <Tabs.List>
            <Tabs.Tab value="statuses">{t("metrics.config.tab.statuses")}</Tabs.Tab>
            <Tabs.Tab value="fields">{t("metrics.config.tab.fields")}</Tabs.Tab>
            <Tabs.Tab value="domains">{t("metrics.config.tab.domains")}</Tabs.Tab>
            <Tabs.Tab value="boards">{t("metrics.config.tab.boards")}</Tabs.Tab>
            <Tabs.Tab value="activityTypes">{t("metrics.config.tab.activityTypes")}</Tabs.Tab>
            <Tabs.Tab value="workCategories">{t("metrics.config.tab.workCategories")}</Tabs.Tab>
            <Tabs.Tab value="capacities">{t("metrics.config.tab.capacities")}</Tabs.Tab>
          </Tabs.List>

          <Tabs.Panel value="statuses" pt="md">
            <StatusesTab t={t} statuses={state.statuses} onChange={(statuses) => setState({ ...state, statuses })} />
          </Tabs.Panel>
          <Tabs.Panel value="fields" pt="md">
            <FieldsTab
              t={t}
              fields={state.fields}
              fieldOptions={options.fields}
              onChange={(fields) => setState({ ...state, fields })}
            />
          </Tabs.Panel>
          <Tabs.Panel value="domains" pt="md">
            <DomainsTab
              t={t}
              domains={state.domains}
              teamOptions={teamOptions}
              onChange={(domains) => setState({ ...state, domains })}
            />
          </Tabs.Panel>
          <Tabs.Panel value="boards" pt="md">
            <BoardsTab
              t={t}
              boards={state.boards}
              teamOptions={teamOptions}
              boardErrors={boardErrors}
              onChange={(boards) => setState({ ...state, boards })}
            />
          </Tabs.Panel>
          <Tabs.Panel value="activityTypes" pt="md">
            <ActivityTypesTab
              t={t}
              activityTypes={state.activityTypes}
              onChange={(activityTypes) => setState({ ...state, activityTypes })}
            />
          </Tabs.Panel>
          <Tabs.Panel value="workCategories" pt="md">
            <WorkCategoriesTab
              t={t}
              workCategoryField={workCategoryField}
              workCategories={state.workCategories}
              truncated={workCategoryOptionsQuery.data?.workCategoryValuesTruncated ?? false}
              onChange={(workCategories) => setState({ ...state, workCategories })}
            />
          </Tabs.Panel>
          <Tabs.Panel value="capacities" pt="md">
            <CapacitiesTab
              t={t}
              sprintCapacities={state.sprintCapacities}
              onChange={(sprintCapacities) => setState({ ...state, sprintCapacities })}
            />
          </Tabs.Panel>
        </Tabs>
      </Box>
    </Stack>
  );
}
