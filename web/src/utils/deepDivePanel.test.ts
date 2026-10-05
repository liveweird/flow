import { describe, expect, test } from "vitest";
import { deepDiveEpic, deepDiveReport, deepDiveTask } from "../test/deepDiveFixtures";
import { dateProblem, draftOf, isComplete, issueLabel, knownLabels, selectionOf, withMode, type DeepDiveDraft } from "./deepDivePanel";

const draft = (partial: Partial<DeepDiveDraft>): DeepDiveDraft => ({
  mode: "SPRINTS",
  domain: null,
  sprints: [],
  epics: [],
  tasks: [],
  connectionId: null,
  from: "",
  to: "",
  ...partial,
});

describe("deepDivePanel", () => {
  test("a label is the key and the summary, or the key alone", () => {
    expect(issueLabel("FLO-1", "Onboarding")).toBe("FLO-1 Onboarding");
    expect(issueLabel("FLO-1", null)).toBe("FLO-1");
    expect(issueLabel("FLO-1", "")).toBe("FLO-1");
  });

  test("known labels come from the report's sprints, epics and tasks, and skip the no-epic row", () => {
    const report = deepDiveReport({
      sprints: [{ id: 41, name: "Sprint 4", startDay: 0, endDay: 3 }],
      epics: [deepDiveEpic("FLO-1", { summary: "Onboarding" }), deepDiveEpic(null)],
      tasks: [deepDiveTask("FLO-11", { summary: "Sign-up" }), deepDiveTask("FLO-12")],
    });
    expect(knownLabels(report)).toEqual({ "41": "Sprint 4", "FLO-1": "FLO-1 Onboarding", "FLO-11": "FLO-11 Sign-up", "FLO-12": "FLO-12" });
    expect(knownLabels(undefined)).toEqual({});
  });

  test("a draft is read from the URL's selection, defaulting to the sprints mode", () => {
    expect(draftOf({})).toEqual(draft({}));
    expect(draftOf({ domain: "FLO", sprintIds: [2, 1], connectionId: 3, from: "2026-01-01" })).toEqual(
      draft({ domain: "FLO", sprints: ["2", "1"], connectionId: "3", from: "2026-01-01" }),
    );
    expect(draftOf({ epicIds: ["A-1"], issueIds: ["A-2"] })).toEqual(draft({ mode: "TASKS", epics: ["A-1"], tasks: ["A-2"] }));
    expect(draftOf({ epicIds: ["A-1", "A-2"] }).mode).toBe("EPICS");
  });

  test("a mode change drops every pick and keeps the connection and the dates", () => {
    const picked = draft({ domain: "FLO", sprints: ["1"], epics: ["A-1"], tasks: ["A-2"], connectionId: "2", from: "2026-01-01", to: "2026-02-01" });
    expect(withMode(picked, "EPICS")).toEqual(draft({ mode: "EPICS", connectionId: "2", from: "2026-01-01", to: "2026-02-01" }));
  });

  test("the selection is built from the draft's own mode only", () => {
    const all = { domain: "FLO", sprints: ["1"], epics: ["A-1", "A-2"], tasks: ["A-3"] };
    expect(selectionOf(draft({ ...all, mode: "SPRINTS" }))).toEqual({ domain: "FLO", sprintIds: [1] });
    expect(selectionOf(draft({ ...all, mode: "EPICS" }))).toEqual({ epicIds: ["A-1", "A-2"] });
    expect(selectionOf(draft({ ...all, mode: "TASKS" }))).toEqual({ epicIds: ["A-1"], issueIds: ["A-3"] });
  });

  test("a draft is complete when its own mode's picks are there", () => {
    expect(isComplete(draft({ mode: "SPRINTS", domain: "FLO" }))).toBe(false);
    expect(isComplete(draft({ mode: "SPRINTS", domain: "FLO", sprints: ["1"] }))).toBe(true);
    expect(isComplete(draft({ mode: "EPICS" }))).toBe(false);
    expect(isComplete(draft({ mode: "EPICS", epics: ["A-1"] }))).toBe(true);
    // A task pick needs its epic, and an epic alone is the EPICS mode, not a task selection.
    expect(isComplete(draft({ mode: "TASKS", tasks: ["A-2"] }))).toBe(false);
    expect(isComplete(draft({ mode: "TASKS", epics: ["A-1"] }))).toBe(false);
    expect(isComplete(draft({ mode: "TASKS", epics: ["A-1"], tasks: ["A-2"] }))).toBe(true);
  });

  test("dates: empty is fine, malformed and reversed or too wide are named, and either blocks completeness", () => {
    const base = draft({ mode: "EPICS", epics: ["A-1"] });
    expect(dateProblem(base)).toBeNull();
    expect(dateProblem({ ...base, from: "2026-01-01" })).toBeNull();
    expect(dateProblem({ ...base, to: "2026-02-30" })).toBe("format");
    expect(dateProblem({ ...base, from: "2026-03-02", to: "2026-03-01" })).toBe("range");
    expect(dateProblem({ ...base, from: "2020-01-01", to: "2026-01-01" })).toBe("range");
    expect(isComplete({ ...base, from: "2026-03-02", to: "2026-03-01" })).toBe(false);
    expect(isComplete({ ...base, from: "2026-03-01", to: "2026-03-01" })).toBe(true);
  });
});
