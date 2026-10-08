import { describe, expect, test } from "vitest";
import {
  buildInitialState,
  buildRequest,
  changedBoardIds,
  changedOverrideStatusIds,
  currentDomainKeys,
  defaultStageForCategory,
  METRICS_STAGES,
  mergeWorkCategoryValues,
  orphanOverrideDomains,
  orphanOverrideStatuses,
  setDomainKeyForProject,
  setDomainStage,
  setOwnerTeamForDomainGroup,
  visibleStatuses,
  type BoardRowState,
  type DomainRowState,
  type MetricsConfigFormState,
  type StatusRowState,
} from "./metricsConfigForm";
import type { DataSourceMetricsConfigOptions, DataSourceMetricsConfigResponse } from "../api/metrics";

const EMPTY_CONFIG: DataSourceMetricsConfigResponse = {
  configured: false,
  statusStages: [],
  domainStatusStages: [],
  fields: { estimateTask: null, estimateEpic: null, epicStart: null, epicDue: null, workCategory: null },
  domains: [],
  boards: [],
  activityTypes: [],
  workCategories: [],
  blockedStatuses: [],
  sprintCapacities: [],
};

const OPTIONS: DataSourceMetricsConfigOptions = {
  statuses: [
    { statusId: "3", name: "In Progress", category: "IN_PROGRESS", inWorkflow: true, seenInHistory: true, inEpicWorkflow: false, inTaskWorkflow: false },
    { statusId: "10", name: "Done", category: "DONE", inWorkflow: true, seenInHistory: true, inEpicWorkflow: false, inTaskWorkflow: false },
  ],
  fields: [{ fieldId: "customfield_10001", name: "Story points", type: "number", detectedRole: "STORY_POINTS", inScheme: true, nonNullCount: 3, inEpicScheme: null, inTaskScheme: null }],
  projects: ["ENG"],
  boards: [{ boardId: 1, name: "Board A", projectKey: "ENG" }],
  issueTypes: ["Story"],
  workCategoryValues: [],
  workCategoryValuesTruncated: false,
  sprints: [{ sprintId: 11, boardId: 1, name: "Sprint 1", state: "active" }],
};

describe("buildInitialState", () => {
  test("gives every reference item a row, unmapped/blank when nothing is stored", () => {
    const state = buildInitialState(EMPTY_CONFIG, OPTIONS);
    expect(state.statuses).toEqual([
      { statusId: "3", name: "In Progress", category: "IN_PROGRESS", stage: "", blocked: false, inWorkflow: true, seenInHistory: true, inEpicWorkflow: false, inTaskWorkflow: false },
      { statusId: "10", name: "Done", category: "DONE", stage: "", blocked: false, inWorkflow: true, seenInHistory: true, inEpicWorkflow: false, inTaskWorkflow: false },
    ]);
    expect(state.domains).toEqual([{ projectKey: "ENG", domainKey: "ENG", domainName: "ENG", ownerTeamId: "" }]);
    expect(state.boards).toEqual([{ boardId: 1, name: "Board A", projectKey: "ENG", teamId: "" }]);
    expect(state.activityTypes).toEqual([{ issueType: "Story", activityType: "Story" }]);
    expect(state.sprintCapacities).toEqual([{ sprintId: 11, boardId: 1, name: "Sprint 1", state: "active", capacityMd: "" }]);
    expect(state.workCategories).toEqual([]);
    expect(state.domainStages).toEqual([]);
  });

  test("carries stored per-domain overrides into the form state", () => {
    const config: DataSourceMetricsConfigResponse = {
      ...EMPTY_CONFIG,
      configured: true,
      domainStatusStages: [{ domainKey: "ENG", statusId: "10", stage: "IN_PROGRESS" }],
    };
    expect(buildInitialState(config, OPTIONS).domainStages).toEqual([{ domainKey: "ENG", statusId: "10", stage: "IN_PROGRESS" }]);
  });

  test("carries over a stored mapping onto its matching reference row", () => {
    const config: DataSourceMetricsConfigResponse = {
      ...EMPTY_CONFIG,
      configured: true,
      statusStages: [{ statusId: "3", stage: "IN_PROGRESS" }],
      blockedStatuses: ["3"],
      domains: [{ projectKey: "ENG", domainKey: "ENGINEERING", domainName: "Engineering", ownerTeamId: 5 }],
      boards: [{ boardId: 1, teamId: 5 }],
      activityTypes: [{ issueType: "Story", activityType: "Feature work" }],
      sprintCapacities: [{ sprintId: 11, capacityMd: 12.5 }],
    };
    const state = buildInitialState(config, OPTIONS);
    expect(state.statuses[0]).toEqual({ statusId: "3", name: "In Progress", category: "IN_PROGRESS", stage: "IN_PROGRESS", blocked: true, inWorkflow: true, seenInHistory: true, inEpicWorkflow: false, inTaskWorkflow: false });
    expect(state.domains[0]).toEqual({
      projectKey: "ENG",
      domainKey: "ENGINEERING",
      domainName: "Engineering",
      ownerTeamId: "5",
    });
    expect(state.boards[0].teamId).toBe("5");
    expect(state.activityTypes[0].activityType).toBe("Feature work");
    expect(state.sprintCapacities[0].capacityMd).toBe("12.5");
  });

  test("carries a null projectKey/boardId through unchanged (a Kanban board, a boardless sprint)", () => {
    const options: DataSourceMetricsConfigOptions = {
      ...OPTIONS,
      boards: [{ boardId: 2, name: "Board B", projectKey: null }],
      sprints: [{ sprintId: 12, boardId: null, name: "Sprint 2", state: "future" }],
    };
    const state = buildInitialState(EMPTY_CONFIG, options);
    expect(state.boards).toEqual([{ boardId: 2, name: "Board B", projectKey: null, teamId: "" }]);
    expect(state.sprintCapacities).toEqual([{ sprintId: 12, boardId: null, name: "Sprint 2", state: "future", capacityMd: "" }]);
  });
});

describe("mergeWorkCategoryValues", () => {
  test("prefers an in-progress edit over the stored category", () => {
    const merged = mergeWorkCategoryValues(
      [{ valueId: "v1", valueName: "Bug", category: "Defect (edited)" }],
      [{ valueId: "v1", valueName: "Bug" }],
      [{ valueId: "v1", category: "Defect (stored)" }],
    );
    expect(merged).toEqual([{ valueId: "v1", valueName: "Bug", category: "Defect (edited)" }]);
  });

  test("falls back to the stored category when nothing has been edited yet", () => {
    const merged = mergeWorkCategoryValues([], [{ valueId: "v1", valueName: "Bug" }], [{ valueId: "v1", category: "Defect (stored)" }]);
    expect(merged).toEqual([{ valueId: "v1", valueName: "Bug", category: "Defect (stored)" }]);
  });

  test("leaves a brand-new value uncategorized", () => {
    const merged = mergeWorkCategoryValues([], [{ valueId: "v2", valueName: null }], []);
    expect(merged).toEqual([{ valueId: "v2", valueName: null, category: "" }]);
  });
});

describe("buildRequest", () => {
  const baseState: MetricsConfigFormState = {
    statuses: [
      { statusId: "3", name: "In Progress", category: "IN_PROGRESS", stage: "IN_PROGRESS", blocked: true, inWorkflow: true, seenInHistory: true, inEpicWorkflow: false, inTaskWorkflow: false },
      { statusId: "10", name: "Done", category: "DONE", stage: "", blocked: false, inWorkflow: true, seenInHistory: true, inEpicWorkflow: false, inTaskWorkflow: false },
    ],
    domainStages: [{ domainKey: "ENG", statusId: "10", stage: "IN_PROGRESS" }],
    fields: { estimateTask: "", estimateEpic: "", epicStart: "", epicDue: "duedate", workCategory: "customfield_10002" },
    domains: [{ projectKey: "ENG", domainKey: "ENG", domainName: "ENG", ownerTeamId: "5" }],
    boards: [
      { boardId: 1, name: "Board A", projectKey: "ENG", teamId: "5" },
      { boardId: 2, name: "Board B", projectKey: "ENG", teamId: "" },
    ],
    activityTypes: [{ issueType: "Story", activityType: "Story" }],
    workCategories: [
      { valueId: "v1", valueName: "Bug", category: "Defect" },
      { valueId: "v2", valueName: "Task", category: "" },
    ],
    sprintCapacities: [
      { sprintId: 11, boardId: 1, name: "Sprint 1", state: "active", capacityMd: "10" },
      { sprintId: 12, boardId: 1, name: "Sprint 2", state: "future", capacityMd: "" },
    ],
  };

  test("omits unmapped statuses, boards and uncategorized/blank values", () => {
    const request = buildRequest(baseState);
    expect(request).toEqual({
      statusStages: [{ statusId: "3", stage: "IN_PROGRESS" }],
      domainStatusStages: [{ domainKey: "ENG", statusId: "10", stage: "IN_PROGRESS" }],
      fields: { estimateTask: null, estimateEpic: null, epicStart: null, epicDue: "duedate", workCategory: "customfield_10002" },
      domains: [{ projectKey: "ENG", domainKey: "ENG", domainName: "ENG", ownerTeamId: 5 }],
      boards: [{ boardId: 1, teamId: 5 }],
      activityTypes: [{ issueType: "Story", activityType: "Story" }],
      workCategories: [{ valueId: "v1", valueName: "Bug", category: "Defect" }],
      blockedStatuses: ["3"],
      sprintCapacities: [{ sprintId: 11, capacityMd: 10 }],
    });
  });

  test("submits no work categories when no work-category field is chosen", () => {
    const request = buildRequest({ ...baseState, fields: { ...baseState.fields, workCategory: "" } });
    expect(request.workCategories).toEqual([]);
  });

  test("carries an unset owner team through as null", () => {
    const request = buildRequest({ ...baseState, domains: [{ projectKey: "ENG", domainKey: "ENG", domainName: "ENG", ownerTeamId: "" }] });
    expect(request.domains).toEqual([{ projectKey: "ENG", domainKey: "ENG", domainName: "ENG", ownerTeamId: null }]);
  });

  test("carries a chosen field id through instead of null", () => {
    const request = buildRequest({
      ...baseState,
      fields: { estimateTask: "customfield_10001", estimateEpic: "customfield_10001", epicStart: "customfield_10003", epicDue: "duedate", workCategory: "" },
    });
    expect(request.fields).toEqual({
      estimateTask: "customfield_10001",
      estimateEpic: "customfield_10001",
      epicStart: "customfield_10003",
      epicDue: "duedate",
      workCategory: null,
    });
  });
});

describe("changedBoardIds", () => {
  test("names only boards whose team assignment actually changed to a non-empty value", () => {
    const before: BoardRowState[] = [
      { boardId: 1, name: "Board A", projectKey: "ENG", teamId: "" },
      { boardId: 2, name: "Board B", projectKey: "ENG", teamId: "6" },
    ];
    const after: BoardRowState[] = [
      { boardId: 1, name: "Board A", projectKey: "ENG", teamId: "5" },
      { boardId: 2, name: "Board B", projectKey: "ENG", teamId: "6" },
    ];
    expect(changedBoardIds(before, after)).toEqual(new Set([1]));
  });

  test("ignores a board cleared back to unmapped", () => {
    const before: BoardRowState[] = [{ boardId: 1, name: "Board A", projectKey: "ENG", teamId: "5" }];
    const after: BoardRowState[] = [{ boardId: 1, name: "Board A", projectKey: "ENG", teamId: "" }];
    expect(changedBoardIds(before, after)).toEqual(new Set());
  });
});

describe("setOwnerTeamForDomainGroup", () => {
  test("sets the owner on every row sharing the changed row's domain key", () => {
    const domains: DomainRowState[] = [
      { projectKey: "ENG", domainKey: "SHARED", domainName: "Shared", ownerTeamId: "5" },
      { projectKey: "ENG2", domainKey: "SHARED", domainName: "Shared", ownerTeamId: "5" },
      { projectKey: "OTHER", domainKey: "OTHER", domainName: "Other", ownerTeamId: "5" },
    ];
    const next = setOwnerTeamForDomainGroup(domains, "ENG", "6");
    expect(next).toEqual([
      { projectKey: "ENG", domainKey: "SHARED", domainName: "Shared", ownerTeamId: "6" },
      { projectKey: "ENG2", domainKey: "SHARED", domainName: "Shared", ownerTeamId: "6" },
      { projectKey: "OTHER", domainKey: "OTHER", domainName: "Other", ownerTeamId: "5" },
    ]);
  });

  test("is a no-op when the changed project key is unknown", () => {
    const domains: DomainRowState[] = [{ projectKey: "ENG", domainKey: "ENG", domainName: "ENG", ownerTeamId: "5" }];
    expect(setOwnerTeamForDomainGroup(domains, "UNKNOWN", "6")).toBe(domains);
  });
});

describe("setDomainKeyForProject", () => {
  const rows = (): DomainRowState[] => [
    { projectKey: "P1", domainKey: "P1", domainName: "P1", ownerTeamId: "5" },
    { projectKey: "P2", domainKey: "P2", domainName: "P2", ownerTeamId: "6" },
    { projectKey: "P3", domainKey: "P3", domainName: "P3", ownerTeamId: "" },
  ];

  test("merging into another domain gives the group the edited row's own owner, never two owners", () => {
    const next = setDomainKeyForProject(rows(), "P2", "P1");
    expect(next.filter((d) => d.domainKey === "P1").map((d) => d.ownerTeamId)).toEqual(["6", "6"]);
    expect(next.find((d) => d.projectKey === "P3")?.ownerTeamId).toBe("");
  });

  test("an edited row without an owner adopts the group's existing owner", () => {
    const next = setDomainKeyForProject(rows(), "P3", "P1");
    expect(next.filter((d) => d.domainKey === "P1").map((d) => d.ownerTeamId)).toEqual(["5", "5"]);
  });

  test("a rename into a fresh key keeps the row's own owner and touches no other row", () => {
    const next = setDomainKeyForProject(rows(), "P1", "NEW");
    expect(next).toEqual([
      { projectKey: "P1", domainKey: "NEW", domainName: "P1", ownerTeamId: "5" },
      { projectKey: "P2", domainKey: "P2", domainName: "P2", ownerTeamId: "6" },
      { projectKey: "P3", domainKey: "P3", domainName: "P3", ownerTeamId: "" },
    ]);
  });

  test("is a no-op when the project key is unknown", () => {
    const domains = rows();
    expect(setDomainKeyForProject(domains, "UNKNOWN", "P1")).toBe(domains);
  });
});

describe("per-domain stage overrides", () => {
  test("setDomainStage adds an override, replaces the same (domain, status) one and leaves the others alone", () => {
    const first = setDomainStage([], "ENG", "3", "DONE");
    expect(first).toEqual([{ domainKey: "ENG", statusId: "3", stage: "DONE" }]);
    const other = setDomainStage(first, "OPS", "3", "NOT_STARTED");
    const replaced = setDomainStage(other, "ENG", "3", "IN_PROGRESS");
    expect(replaced).toHaveLength(2);
    expect(replaced).toEqual(
      expect.arrayContaining([
        { domainKey: "ENG", statusId: "3", stage: "IN_PROGRESS" },
        { domainKey: "OPS", statusId: "3", stage: "NOT_STARTED" },
      ]),
    );
  });

  test('setDomainStage with "" removes only that (domain, status) override', () => {
    const start = [
      { domainKey: "ENG", statusId: "3", stage: "DONE" as const },
      { domainKey: "ENG", statusId: "10", stage: "DONE" as const },
    ];
    expect(setDomainStage(start, "ENG", "3", "")).toEqual([{ domainKey: "ENG", statusId: "10", stage: "DONE" }]);
    expect(setDomainStage(start, "ENG", "99", "")).toEqual(start);
  });

  test("currentDomainKeys is distinct and skips a blank key", () => {
    const domains: DomainRowState[] = [
      { projectKey: "A", domainKey: "X", domainName: "A", ownerTeamId: "" },
      { projectKey: "B", domainKey: "X", domainName: "B", ownerTeamId: "" },
      { projectKey: "C", domainKey: "", domainName: "C", ownerTeamId: "" },
      { projectKey: "D", domainKey: "Y", domainName: "D", ownerTeamId: "" },
    ];
    expect(currentDomainKeys(domains)).toEqual(["X", "Y"]);
  });

  test("orphanOverrideDomains names override domains the Domains tab no longer defines", () => {
    const domains: DomainRowState[] = [{ projectKey: "A", domainKey: "RENAMED", domainName: "A", ownerTeamId: "" }];
    const overrides = [
      { domainKey: "RENAMED", statusId: "3", stage: "DONE" as const },
      { domainKey: "OLD", statusId: "3", stage: "DONE" as const },
      { domainKey: "OLD", statusId: "10", stage: "DONE" as const },
    ];
    expect(orphanOverrideDomains(domains, overrides)).toEqual(["OLD"]);
  });

  test("orphanOverrideStatuses names overrides on statuses the form has no row for", () => {
    const statuses = buildInitialState(EMPTY_CONFIG, OPTIONS).statuses;
    const overrides = [
      { domainKey: "ENG", statusId: "3", stage: "DONE" as const },
      { domainKey: "ENG", statusId: "gone", stage: "DONE" as const },
    ];
    expect(orphanOverrideStatuses(statuses, overrides)).toEqual([{ domainKey: "ENG", statusId: "gone", stage: "DONE" }]);
  });
});

describe("the default status filter", () => {
  const status = (statusId: string, patch: Partial<StatusRowState> = {}): StatusRowState => ({
    statusId,
    name: statusId,
    category: "DONE",
    stage: "DONE",
    blocked: false,
    inWorkflow: false,
    seenInHistory: false,
    inEpicWorkflow: false,
    inTaskWorkflow: false,
    ...patch,
  });
  const none = new Set<string>();

  test("buildInitialState carries the two relevance flags from the options", () => {
    const options: DataSourceMetricsConfigOptions = {
      ...OPTIONS,
      statuses: [{ statusId: "3", name: "In Progress", category: "IN_PROGRESS", inWorkflow: true, seenInHistory: false, inEpicWorkflow: false, inTaskWorkflow: false }],
    };
    const [row] = buildInitialState(EMPTY_CONFIG, options).statuses;
    expect([row.inWorkflow, row.seenInHistory]).toEqual([true, false]);
  });

  test("buildInitialState carries the epic/task workflow flags from the options", () => {
    const options: DataSourceMetricsConfigOptions = {
      ...OPTIONS,
      statuses: [{ statusId: "3", name: "On Hold", category: "IN_PROGRESS", inWorkflow: true, seenInHistory: false, inEpicWorkflow: true, inTaskWorkflow: false }],
    };
    const [row] = buildInitialState(EMPTY_CONFIG, options).statuses;
    expect([row.inEpicWorkflow, row.inTaskWorkflow]).toEqual([true, false]);
  });

  test("maps a Jira category to its default stage; UNKNOWN stays unmapped", () => {
    expect(["TODO", "IN_PROGRESS", "DONE", "UNKNOWN"].map(defaultStageForCategory)).toEqual(["NOT_STARTED", "IN_PROGRESS", "DONE", ""]);
  });

  test("WAITING is a selectable stage between In progress and Done, and no category defaults to it (A30)", () => {
    expect(METRICS_STAGES).toEqual(["NOT_STARTED", "IN_PROGRESS", "WAITING", "DONE"]);
    expect(["TODO", "IN_PROGRESS", "DONE", "UNKNOWN", "WAITING"].map(defaultStageForCategory)).not.toContain("WAITING");
  });

  test("a WAITING mapping is a user choice (the status stays listed) and travels in the request", () => {
    const rows = [status("ready", { category: "IN_PROGRESS", stage: "WAITING", inWorkflow: false })];
    const other = status("plain", { category: "IN_PROGRESS", stage: "IN_PROGRESS" });
    expect(visibleStatuses([...rows, other, status("wf", { inWorkflow: true })], [], none, false).map((s) => s.statusId)).toEqual(["ready", "wf"]);
    const state = { ...buildInitialState(EMPTY_CONFIG, OPTIONS), statuses: rows, domainStages: [{ domainKey: "ENG", statusId: "ready", stage: "WAITING" as const }] };
    expect(buildRequest(state).statusStages).toEqual([{ statusId: "ready", stage: "WAITING" }]);
    expect(buildRequest(state).domainStatusStages).toEqual([{ domainKey: "ENG", statusId: "ready", stage: "WAITING" }]);
  });

  test("lists workflow and history statuses plus any with a choice or a session edit, hides the rest", () => {
    const rows = [
      status("wf", { inWorkflow: true }),
      status("hist", { seenInHistory: true, inEpicWorkflow: false, inTaskWorkflow: false }),
      status("plain"),
      status("stage", { stage: "IN_PROGRESS" }),
      status("unmapped", { stage: "" }),
      status("blocked", { blocked: true }),
      status("override"),
      status("touched"),
      status("unknown", { category: "UNKNOWN", stage: "" }),
    ];
    const overrides = [{ domainKey: "ENG", statusId: "override", stage: "DONE" as const }];
    const ids = visibleStatuses(rows, overrides, new Set(["touched"]), false).map((s) => s.statusId);
    expect(ids).toEqual(["wf", "hist", "stage", "unmapped", "blocked", "override", "touched"]);
  });

  test("showAll lists everything, and so does a connection with no workflow or history information", () => {
    const rows = [status("a", { inWorkflow: true }), status("b")];
    expect(visibleStatuses(rows, [], none, true)).toBe(rows);
    const noInfo = [status("a"), status("b")];
    expect(visibleStatuses(noInfo, [], none, false)).toBe(noInfo);
  });

  test("changedOverrideStatusIds reports added, removed and restaged overrides once per status", () => {
    const before = [
      { domainKey: "ENG", statusId: "1", stage: "DONE" as const },
      { domainKey: "ENG", statusId: "2", stage: "DONE" as const },
      { domainKey: "OPS", statusId: "3", stage: "DONE" as const },
    ];
    const after = [
      { domainKey: "ENG", statusId: "1", stage: "IN_PROGRESS" as const },
      { domainKey: "ENG", statusId: "2", stage: "DONE" as const },
      { domainKey: "OPS", statusId: "4", stage: "DONE" as const },
    ];
    expect(changedOverrideStatusIds(before, after).sort()).toEqual(["1", "3", "4"]);
    expect(changedOverrideStatusIds(before, before)).toEqual([]);
  });
});
