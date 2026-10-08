// Pure state-shaping helpers behind `pages/DataSourceMetricsConfig.tsx`
// (`.claude/docs/metrics.md` "Per-connection metrics configuration"): the GET response's
// computed-or-stored config, combined with the options endpoint's reference data, into one
// per-tab row shape the page edits; and the reverse — the edited rows back into the PUT body.

import type {
  DataSourceMetricsConfigOptions,
  DataSourceMetricsConfigRequest,
  DataSourceMetricsConfigResponse,
  MetricsFieldValueOption,
} from "../api/metrics";

/** The server's bounds on these free-text/numeric inputs (the spec's `maxLength`/`maximum`; a violation is a 400). */
export const DOMAIN_KEY_MAX_LENGTH = 50;
export const DOMAIN_NAME_MAX_LENGTH = 100;
export const CATEGORY_MAX_LENGTH = 100;
export const CAPACITY_MD_MAX = 999999.99;

export type MetricsStage = "NOT_STARTED" | "IN_PROGRESS" | "DONE";
export const METRICS_STAGES: MetricsStage[] = ["NOT_STARTED", "IN_PROGRESS", "DONE"];

export interface StatusRowState {
  statusId: string;
  name: string;
  category: string;
  /** "" means unmapped — omitted from the submitted `statusStages` list. */
  stage: MetricsStage | "";
  blocked: boolean;
  /** Some in-scope project's reference workflow uses the status (options `inWorkflow`) — drives the default Statuses filter. */
  inWorkflow: boolean;
  /** Some work item's status interval carries the status (options `seenInHistory`). */
  seenInHistory: boolean;
}

/** One per-domain override of a status's stage — only ever a set stage; "same as all domains" is the ABSENCE of a row. */
export interface DomainStageRowState {
  domainKey: string;
  statusId: string;
  stage: MetricsStage;
}

export interface DomainRowState {
  projectKey: string;
  domainKey: string;
  domainName: string;
  /** "" means unowned; otherwise a team id carried as a string (Mantine Select values are strings). */
  ownerTeamId: string;
}

export interface BoardRowState {
  boardId: number;
  name: string;
  projectKey: string | null;
  /** "" means unmapped; otherwise a team id carried as a string (Mantine Select values are strings). */
  teamId: string;
}

export interface ActivityRowState {
  issueType: string;
  activityType: string;
}

export interface WorkCategoryRowState {
  valueId: string;
  valueName: string | null;
  /** "" means uncategorized — omitted from the submitted `workCategories` list. */
  category: string;
}

export interface CapacityRowState {
  sprintId: number;
  boardId: number | null;
  name: string;
  state: string;
  /** "" means unset — DERIVE falls back to the computed default. Carried as text for the NumberInput. */
  capacityMd: string;
}

export interface FieldsState {
  estimateTask: string;
  estimateEpic: string;
  epicStart: string;
  epicDue: string;
  workCategory: string;
}

export interface MetricsConfigFormState {
  statuses: StatusRowState[];
  /** Per-domain status → stage overrides (`metrics.status_stage_map` rows with a domain key). */
  domainStages: DomainStageRowState[];
  fields: FieldsState;
  domains: DomainRowState[];
  boards: BoardRowState[];
  activityTypes: ActivityRowState[];
  workCategories: WorkCategoryRowState[];
  sprintCapacities: CapacityRowState[];
}

/**
 * The GET config (stored or computed-defaults) plus the options endpoint's reference lists,
 * merged into one editable row per reference item — a reference item with no stored mapping
 * still gets a row (unmapped/blank), so an admin sees every status/project/board/issue type/
 * sprint Jira reports, not only the ones already configured.
 */
export function buildInitialState(
  config: DataSourceMetricsConfigResponse,
  options: DataSourceMetricsConfigOptions,
): MetricsConfigFormState {
  const stageByStatusId = new Map(config.statusStages.map((s) => [s.statusId, s.stage]));
  const blockedStatusIds = new Set(config.blockedStatuses);
  const statuses: StatusRowState[] = options.statuses.map((s) => ({
    statusId: s.statusId,
    name: s.name,
    category: s.category,
    stage: stageByStatusId.get(s.statusId) ?? "",
    blocked: blockedStatusIds.has(s.statusId),
    inWorkflow: s.inWorkflow,
    seenInHistory: s.seenInHistory,
  }));

  const fields: FieldsState = {
    estimateTask: config.fields.estimateTask ?? "",
    estimateEpic: config.fields.estimateEpic ?? "",
    epicStart: config.fields.epicStart ?? "",
    epicDue: config.fields.epicDue ?? "",
    workCategory: config.fields.workCategory ?? "",
  };

  const domainByProjectKey = new Map(config.domains.map((d) => [d.projectKey, d]));
  const domains: DomainRowState[] = options.projects.map((projectKey) => {
    const existing = domainByProjectKey.get(projectKey);
    return {
      projectKey,
      domainKey: existing?.domainKey ?? projectKey,
      domainName: existing?.domainName ?? projectKey,
      ownerTeamId: existing?.ownerTeamId != null ? String(existing.ownerTeamId) : "",
    };
  });

  const teamIdByBoardId = new Map(config.boards.map((b) => [b.boardId, b.teamId]));
  const boards: BoardRowState[] = options.boards.map((b) => ({
    boardId: b.boardId,
    name: b.name,
    projectKey: b.projectKey ?? null,
    teamId: teamIdByBoardId.has(b.boardId) ? String(teamIdByBoardId.get(b.boardId)) : "",
  }));

  const activityTypeByIssueType = new Map(config.activityTypes.map((a) => [a.issueType, a.activityType]));
  const activityTypes: ActivityRowState[] = options.issueTypes.map((issueType) => ({
    issueType,
    activityType: activityTypeByIssueType.get(issueType) ?? issueType,
  }));

  const capacityBySprintId = new Map(config.sprintCapacities.map((c) => [c.sprintId, c.capacityMd]));
  const sprintCapacities: CapacityRowState[] = options.sprints.map((s) => ({
    sprintId: s.sprintId,
    boardId: s.boardId ?? null,
    name: s.name,
    state: s.state,
    capacityMd: capacityBySprintId.has(s.sprintId) ? String(capacityBySprintId.get(s.sprintId)) : "",
  }));

  // workCategories starts empty — it only has rows once a work-category field is chosen and its
  // own values query resolves (mergeWorkCategoryValues below), same as the stored config's own
  // "empty until a field is chosen" default (`.claude/docs/metrics.md`).
  const domainStages: DomainStageRowState[] = config.domainStatusStages.map((o) => ({
    domainKey: o.domainKey,
    statusId: o.statusId,
    stage: o.stage,
  }));

  return { statuses, domainStages, fields, domains, boards, activityTypes, workCategories: [], sprintCapacities };
}

/**
 * Combines the work-category-field-scoped options query's distinct values with whatever category
 * is already chosen for each — from the currently edited rows first (so switching tabs never
 * loses an in-progress edit), falling back to the originally stored mapping.
 */
export function mergeWorkCategoryValues(
  existingRows: WorkCategoryRowState[],
  values: MetricsFieldValueOption[],
  stored: { valueId: string; category: string }[],
): WorkCategoryRowState[] {
  const categoryByValueId = new Map<string, string>();
  for (const row of stored) categoryByValueId.set(row.valueId, row.category);
  for (const row of existingRows) categoryByValueId.set(row.valueId, row.category);
  return values.map((v) => ({
    valueId: v.valueId,
    valueName: v.valueName ?? null,
    category: categoryByValueId.get(v.valueId) ?? "",
  }));
}

export function buildRequest(state: MetricsConfigFormState): DataSourceMetricsConfigRequest {
  return {
    statusStages: state.statuses
      .filter((s) => s.stage !== "")
      .map((s) => ({ statusId: s.statusId, stage: s.stage as MetricsStage })),
    domainStatusStages: state.domainStages.map((o) => ({ domainKey: o.domainKey, statusId: o.statusId, stage: o.stage })),
    fields: {
      estimateTask: state.fields.estimateTask || null,
      estimateEpic: state.fields.estimateEpic || null,
      epicStart: state.fields.epicStart || null,
      epicDue: state.fields.epicDue || null,
      workCategory: state.fields.workCategory || null,
    },
    domains: state.domains.map((d) => ({
      projectKey: d.projectKey,
      domainKey: d.domainKey,
      domainName: d.domainName,
      ownerTeamId: d.ownerTeamId === "" ? null : Number(d.ownerTeamId),
    })),
    boards: state.boards.filter((b) => b.teamId !== "").map((b) => ({ boardId: b.boardId, teamId: Number(b.teamId) })),
    activityTypes: state.activityTypes.map((a) => ({ issueType: a.issueType, activityType: a.activityType })),
    workCategories: state.fields.workCategory
      ? state.workCategories
          .filter((w) => w.category !== "")
          .map((w) => ({ valueId: w.valueId, valueName: w.valueName, category: w.category }))
      : [],
    blockedStatuses: state.statuses.filter((s) => s.blocked).map((s) => s.statusId),
    sprintCapacities: state.sprintCapacities
      .filter((c) => c.capacityMd !== "")
      .map((c) => ({ sprintId: c.sprintId, capacityMd: Number(c.capacityMd) })),
  };
}

/**
 * Sets `ownerTeamId` on every domain row sharing the SAME `domainKey` as the row identified by
 * `projectKey` — the server's own rule ("every row of a domainKey must agree, null or equal";
 * `metrics/DataSourceMetricsConfig.kt`'s `validateDomains`) means one project's owner choice IS
 * every project's choice once they share a domain.
 */
export function setOwnerTeamForDomainGroup(domains: DomainRowState[], projectKey: string, ownerTeamId: string): DomainRowState[] {
  const changedRow = domains.find((d) => d.projectKey === projectKey);
  if (!changedRow) return domains;
  const domainKey = changedRow.domainKey;
  return domains.map((d) => (d.domainKey === domainKey ? { ...d, ownerTeamId } : d));
}

/**
 * Sets one row's domain key. When the new key joins rows that already share it, the merged group gets
 * ONE owner — the edited row's own owner if it has one, else the group's existing owner — so a merge
 * never leaves two disagreeing owners for the server to reject (every row of a domainKey must agree).
 */
export function setDomainKeyForProject(domains: DomainRowState[], projectKey: string, domainKey: string): DomainRowState[] {
  const edited = domains.find((d) => d.projectKey === projectKey);
  if (!edited) return domains;
  const renamed = domains.map((d) => (d.projectKey === projectKey ? { ...d, domainKey } : d));
  const groupOwner =
    edited.ownerTeamId !== ""
      ? edited.ownerTeamId
      : (renamed.find((d) => d.projectKey !== projectKey && d.domainKey === domainKey && d.ownerTeamId !== "")?.ownerTeamId ?? "");
  return renamed.map((d) => (d.domainKey === domainKey ? { ...d, ownerTeamId: groupOwner } : d));
}

/** The board ids whose `teamId` differs between two board-row snapshots — the 409 row-marking rule. */
export function changedBoardIds(before: BoardRowState[], after: BoardRowState[]): Set<number> {
  const beforeById = new Map(before.map((b) => [b.boardId, b.teamId]));
  const changed = new Set<number>();
  for (const row of after) {
    if (beforeById.get(row.boardId) !== row.teamId && row.teamId !== "") changed.add(row.boardId);
  }
  return changed;
}

/**
 * Sets (or, for `stage === ""`, removes) the override of one status in one domain — the list never
 * holds two rows for the same (domain, status), mirroring the server's duplicate-key `400`.
 */
export function setDomainStage(
  overrides: DomainStageRowState[],
  domainKey: string,
  statusId: string,
  stage: MetricsStage | "",
): DomainStageRowState[] {
  const rest = overrides.filter((o) => !(o.domainKey === domainKey && o.statusId === statusId));
  return stage === "" ? rest : [...rest, { domainKey, statusId, stage }];
}

/** The distinct, non-empty domain keys the Domains tab currently defines, in first-seen order. */
export function currentDomainKeys(domains: DomainRowState[]): string[] {
  return [...new Set(domains.map((d) => d.domainKey).filter((key) => key !== ""))];
}

/**
 * The domain keys that carry overrides but are no longer a defined domain (a key renamed on the Domains tab
 * after an override was set) — the server would `400` them, so the editor flags them before saving.
 */
export function orphanOverrideDomains(domains: DomainRowState[], overrides: DomainStageRowState[]): string[] {
  const known = new Set(currentDomainKeys(domains));
  return [...new Set(overrides.map((o) => o.domainKey).filter((key) => !known.has(key)))];
}

/**
 * The overrides naming a status the connection no longer reports (it left `norm.statuses` after the override was
 * saved): the status table has no row for them, so without this they could be neither seen nor removed — and the
 * server would `400` every save carrying them.
 */
export function orphanOverrideStatuses(statuses: StatusRowState[], overrides: DomainStageRowState[]): DomainStageRowState[] {
  const known = new Set(statuses.map((s) => s.statusId));
  return overrides.filter((o) => !known.has(o.statusId));
}

/**
 * The stage the server seeds a status with before any admin choice (`metrics/DataSourceMetricsConfig.kt`'s
 * `toDefaultStage`): the to-do category → NOT_STARTED, IN_PROGRESS → IN_PROGRESS, DONE → DONE, UNKNOWN stays unmapped ("").
 */
export function defaultStageForCategory(category: string): MetricsStage | "" {
  switch (category) {
    case "TODO":
      return "NOT_STARTED";
    case "IN_PROGRESS":
      return "IN_PROGRESS";
    case "DONE":
      return "DONE";
    default:
      return "";
  }
}

/** Whether the status carries a deliberate admin choice: a non-default stage, the Blocked flag or a per-domain override. */
function statusHasUserChoice(row: StatusRowState, overrides: DomainStageRowState[]): boolean {
  return row.stage !== defaultStageForCategory(row.category) || row.blocked || overrides.some((o) => o.statusId === row.statusId);
}

/**
 * The Statuses tab's default filter: a status is listed when the connection's workflows use it or its history
 * carries it, PLUS any status the admin has made a choice on (so a hidden status never hides a choice) or touched
 * this session (so a row never vanishes under the cursor when an edit happens to restore its default). When NO
 * status is in a workflow or in history (no profile yet) the filter has nothing to go on and lists everything.
 */
export function visibleStatuses(
  statuses: StatusRowState[],
  overrides: DomainStageRowState[],
  touched: ReadonlySet<string>,
  showAll: boolean,
): StatusRowState[] {
  if (showAll || !statuses.some((s) => s.inWorkflow || s.seenInHistory)) return statuses;
  return statuses.filter((s) => s.inWorkflow || s.seenInHistory || statusHasUserChoice(s, overrides) || touched.has(s.statusId));
}

/** The status ids whose per-domain overrides differ between two override lists (added, removed or restaged). */
export function changedOverrideStatusIds(before: DomainStageRowState[], after: DomainStageRowState[]): string[] {
  const key = (o: DomainStageRowState) => JSON.stringify([o.domainKey, o.statusId, o.stage]);
  const beforeKeys = new Set(before.map(key));
  const afterKeys = new Set(after.map(key));
  const changed = [...before.filter((o) => !afterKeys.has(key(o))), ...after.filter((o) => !beforeKeys.has(key(o)))];
  return [...new Set(changed.map((o) => o.statusId))];
}
