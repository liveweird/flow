import { describe, expect, test } from "vitest";
import {
  buildInitialState,
  buildRequest,
  changedBoardIds,
  mergeWorkCategoryValues,
  type BoardRowState,
  type MetricsConfigFormState,
} from "./metricsConfigForm";
import type { DataSourceMetricsConfigOptions, DataSourceMetricsConfigResponse } from "../api/metrics";

const EMPTY_CONFIG: DataSourceMetricsConfigResponse = {
  configured: false,
  statusStages: [],
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
    { statusId: "3", name: "In Progress", category: "IN_PROGRESS" },
    { statusId: "10", name: "Done", category: "DONE" },
  ],
  fields: [{ fieldId: "customfield_10001", name: "Story points", type: "number", detectedRole: "STORY_POINTS" }],
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
      { statusId: "3", name: "In Progress", category: "IN_PROGRESS", stage: "", blocked: false },
      { statusId: "10", name: "Done", category: "DONE", stage: "", blocked: false },
    ]);
    expect(state.domains).toEqual([{ projectKey: "ENG", domainKey: "ENG", domainName: "ENG" }]);
    expect(state.boards).toEqual([{ boardId: 1, name: "Board A", projectKey: "ENG", teamId: "" }]);
    expect(state.activityTypes).toEqual([{ issueType: "Story", activityType: "Story" }]);
    expect(state.sprintCapacities).toEqual([{ sprintId: 11, boardId: 1, name: "Sprint 1", state: "active", capacityMd: "" }]);
    expect(state.workCategories).toEqual([]);
  });

  test("carries over a stored mapping onto its matching reference row", () => {
    const config: DataSourceMetricsConfigResponse = {
      ...EMPTY_CONFIG,
      configured: true,
      statusStages: [{ statusId: "3", stage: "IN_PROGRESS" }],
      blockedStatuses: ["3"],
      domains: [{ projectKey: "ENG", domainKey: "ENGINEERING", domainName: "Engineering" }],
      boards: [{ boardId: 1, teamId: 5 }],
      activityTypes: [{ issueType: "Story", activityType: "Feature work" }],
      sprintCapacities: [{ sprintId: 11, capacityMd: 12.5 }],
    };
    const state = buildInitialState(config, OPTIONS);
    expect(state.statuses[0]).toEqual({ statusId: "3", name: "In Progress", category: "IN_PROGRESS", stage: "IN_PROGRESS", blocked: true });
    expect(state.domains[0]).toEqual({ projectKey: "ENG", domainKey: "ENGINEERING", domainName: "Engineering" });
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
      { statusId: "3", name: "In Progress", category: "IN_PROGRESS", stage: "IN_PROGRESS", blocked: true },
      { statusId: "10", name: "Done", category: "DONE", stage: "", blocked: false },
    ],
    fields: { estimateTask: "", estimateEpic: "", epicStart: "", epicDue: "duedate", workCategory: "customfield_10002" },
    domains: [{ projectKey: "ENG", domainKey: "ENG", domainName: "ENG" }],
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
      fields: { estimateTask: null, estimateEpic: null, epicStart: null, epicDue: "duedate", workCategory: "customfield_10002" },
      domains: [{ projectKey: "ENG", domainKey: "ENG", domainName: "ENG" }],
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
