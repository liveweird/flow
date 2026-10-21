import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { deepDiveEpic, deepDiveReport, deepDiveTask } from "../test/deepDiveFixtures";
import {
  dateProblem,
  draftOf,
  isComplete,
  issueLabel,
  knownLabels,
  learnPicks,
  missingOf,
  pickConnections,
  pruneSprints,
  selectionOf,
  selectionSummary,
  withDomain,
  withMode,
  type DeepDiveDraft,
} from "./deepDivePanel";

const draft = (partial: Partial<DeepDiveDraft>): DeepDiveDraft => ({
  mode: "SPRINTS",
  domain: null,
  sprints: [],
  epics: [],
  picked: {},
  awaitingSprintCheck: false,
  removed: null,
  from: "",
  to: "",
  ...partial,
});

const page = (...values: string[]) => ({ options: values.map((value) => ({ value })), total: values.length });

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

  test("a draft is read from the URL's selection, defaulting to the sprints mode; the link's connection rides on its picks", () => {
    expect(draftOf({})).toEqual(draft({}));
    expect(draftOf({ domain: "FLO", sprintIds: [2, 1], connectionId: 3, from: "2026-01-01" })).toEqual(
      draft({
        domain: "FLO",
        sprints: ["2", "1"],
        picked: { "2": { connectionId: 3 }, "1": { connectionId: 3 } },
        from: "2026-01-01",
      }),
    );
    expect(draftOf({ epicIds: ["A-1", "A-2"] })).toEqual(
      draft({ mode: "EPICS", epics: ["A-1", "A-2"], picked: { "A-1": { connectionId: null }, "A-2": { connectionId: null } } }),
    );
  });

  test("a link's connection the reference data does not list is not put on its picks", () => {
    expect(draftOf({ epicIds: ["A-1"], connectionId: 2 }, [1, 2]).picked["A-1"]).toEqual({ connectionId: 2 });
    expect(draftOf({ epicIds: ["A-1"], connectionId: 9 }, [1, 2]).picked["A-1"]).toEqual({ connectionId: null });
    expect(draftOf({ epicIds: ["A-1"], connectionId: 9 }).picked["A-1"]).toEqual({ connectionId: 9 });
  });

  test("a mode change drops every pick and keeps the domain and the dates", () => {
    const picked = draft({
      domain: "FLO",
      sprints: ["1"],
      epics: ["A-1"],
      picked: { "1": { connectionId: 2 } },
      awaitingSprintCheck: true,
      removed: { kind: "sprints", count: 1, domain: "Flow" },
      from: "2026-01-01",
      to: "2026-02-01",
    });
    expect(withMode(picked, "EPICS")).toEqual(draft({ mode: "EPICS", domain: "FLO", from: "2026-01-01", to: "2026-02-01" }));
  });

  test("the selection is built from the draft's own mode only: epics never carry the domain", () => {
    const all = { domain: "FLO", sprints: ["1"], epics: ["A-1", "A-2"] };
    expect(selectionOf(draft({ ...all, mode: "SPRINTS" }))).toEqual({ domain: "FLO", sprintIds: [1] });
    expect(selectionOf(draft({ ...all, mode: "EPICS" }))).toEqual({ epicIds: ["A-1", "A-2"] });
  });

  test("the connection is written only with several connections, and only when the picks name exactly one", () => {
    const picked = draft({ domain: "FLO", sprints: ["1", "2"], picked: { "1": { connectionId: 7 }, "2": { connectionId: 7 } } });
    expect(selectionOf(picked)).toEqual({ domain: "FLO", sprintIds: [1, 2] });
    expect(selectionOf(picked, true)).toEqual({ domain: "FLO", sprintIds: [1, 2], connectionId: 7 });
    // Unknown connections add none; two different ones write none (Show is blocked then).
    expect(selectionOf(draft({ domain: "FLO", sprints: ["1"], picked: { "1": { connectionId: null } } }), true).connectionId).toBeUndefined();
    const split = draft({ domain: "FLO", sprints: ["1", "2"], picked: { "1": { connectionId: 7 }, "2": { connectionId: 8 } } });
    expect(pickConnections(split)).toEqual([7, 8]);
    expect(selectionOf(split, true).connectionId).toBeUndefined();
    // Only the mode's own picks count.
    expect(pickConnections({ ...split, mode: "EPICS", epics: [] })).toEqual([]);
  });

  test("a draft is complete when its own mode's picks are there", () => {
    expect(isComplete(draft({ mode: "SPRINTS", domain: "FLO" }))).toBe(false);
    expect(isComplete(draft({ mode: "SPRINTS", domain: "FLO", sprints: ["1"] }))).toBe(true);
    expect(isComplete(draft({ mode: "EPICS" }))).toBe(false);
    expect(isComplete(draft({ mode: "EPICS", epics: ["A-1"] }))).toBe(true);
    // The domain only filters the epic list: it does not make an epic selection incomplete or turn it into sprints.
    expect(isComplete(draft({ mode: "EPICS", domain: "FLO", epics: ["A-1"] }))).toBe(true);
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

  test("the first thing still missing is named per mode, then the connection, then the dates; nothing when complete", () => {
    expect(missingOf(draft({ mode: "SPRINTS" }))).toBe("domainAndSprints");
    expect(missingOf(draft({ mode: "SPRINTS", domain: "FLO" }))).toBe("sprints");
    expect(missingOf(draft({ mode: "EPICS" }))).toBe("epics");
    expect(missingOf(draft({ mode: "EPICS", epics: ["A-1"], from: "x" }))).toBe("dates");
    expect(missingOf(draft({ mode: "EPICS", epics: ["A-1"] }))).toBeNull();
    const split = { mode: "EPICS" as const, epics: ["A-1", "A-2"], picked: { "A-1": { connectionId: 1 }, "A-2": { connectionId: 2 } } };
    expect(missingOf(draft(split))).toBe("oneConnection");
    expect(missingOf(draft({ ...split, from: "x" }))).toBe("oneConnection");
  });

  describe("withDomain", () => {
    const epics = draft({
      mode: "EPICS",
      epics: ["A-1", "A-2", "A-3", "A-4"],
      picked: {
        "A-1": { connectionId: 1, domain: "FLO" },
        "A-2": { connectionId: 1, domain: "OPS" },
        "A-3": { connectionId: null },
        "A-4": { connectionId: 1, domain: null },
      },
    });

    test("in Epics every pick stays whatever domain it sits in (a selection may span domains), and nothing is counted as removed", () => {
      const next = withDomain(epics, "FLO");
      expect(next.domain).toBe("FLO");
      expect(next.epics).toEqual(["A-1", "A-2", "A-3", "A-4"]);
      expect(next.picked).toBe(epics.picked);
      expect(next.removed).toBeNull();
      expect(next.awaitingSprintCheck).toBe(false);
      const cleared = withDomain({ ...epics, domain: "FLO" }, null);
      expect(cleared.epics).toEqual(epics.epics);
      expect(cleared.domain).toBeNull();
    });

    test("in Sprints the picks stay and a check against the new domain's list is pending", () => {
      const sprints = draft({ domain: "FLO", sprints: ["1", "2"] });
      expect(withDomain(sprints, "OPS")).toMatchObject({ domain: "OPS", sprints: ["1", "2"], awaitingSprintCheck: true });
      expect(withDomain(draft({ domain: "FLO" }), "OPS").awaitingSprintCheck).toBe(false);
      expect(withDomain(sprints, null).awaitingSprintCheck).toBe(false);
    });
  });

  describe("pruneSprints", () => {
    const pending = draft({ domain: "OPS", sprints: ["1", "2", "3"], awaitingSprintCheck: true });

    test("an unfiltered answer that lists everything drops the sprints it does not list, and says so", () => {
      const next = pruneSprints(pending, page("2", "9"), undefined, "Ops");
      expect(next.sprints).toEqual(["2"]);
      expect(next.awaitingSprintCheck).toBe(false);
      expect(next.removed).toEqual({ kind: "sprints", count: 2, domain: "Ops" });
    });

    test("a truncated list leaves the picks alone and ends the check", () => {
      const next = pruneSprints(pending, { options: [{ value: "9" }], total: 150 }, undefined, "Ops");
      expect(next.sprints).toEqual(["1", "2", "3"]);
      expect(next.awaitingSprintCheck).toBe(false);
      expect(next.removed).toBeNull();
    });

    test("a filtered answer, or no pending check, changes nothing", () => {
      expect(pruneSprints(pending, page("2"), "sprint", "Ops")).toBe(pending);
      const settled = { ...pending, awaitingSprintCheck: false };
      expect(pruneSprints(settled, page("2"), undefined, "Ops")).toBe(settled);
    });

    test("a pick is matched by connection: a known connection the list does not place the value in is dropped, an unknown one stays", () => {
      const placed = { options: [{ value: "1", connectionId: 2 }, { value: "2", connectionId: 1 }, { value: "2", connectionId: 2 }], total: 3 };
      const picked = draft({
        domain: "OPS",
        sprints: ["1", "2", "3"],
        picked: { "1": { connectionId: 1 }, "2": { connectionId: 2 }, "3": { connectionId: null } },
        awaitingSprintCheck: true,
      });
      const next = pruneSprints(picked, placed, undefined, "Ops");
      // 1@1 is not listed (1 is only in connection 2); 2@2 is; 3 is not listed at all.
      expect(next.sprints).toEqual(["2"]);
      expect(next.removed).toEqual({ kind: "sprints", count: 2, domain: "Ops" });
      const unknown = pruneSprints({ ...picked, sprints: ["1"], picked: { "1": { connectionId: null } } }, placed, undefined, "Ops");
      expect(unknown.sprints).toEqual(["1"]);
    });

    test("every sprint still listed leaves no note", () => {
      const next = pruneSprints(pending, page("1", "2", "3"), undefined, "Ops");
      expect(next.sprints).toEqual(["1", "2", "3"]);
      expect(next.removed).toBeNull();
    });
  });

  describe("learnPicks", () => {
    test("fills in the connection and domain a link's pick lacked, only from an unambiguous option", () => {
      const linked = draft({ mode: "EPICS", epics: ["A-1", "A-2"], picked: { "A-1": { connectionId: null }, "A-2": { connectionId: null } } });
      const next = learnPicks(linked, [
        { value: "A-1", connectionId: 4, domain: "FLO" },
        { value: "A-2", connectionId: 4, domain: "FLO" },
        { value: "A-2", connectionId: 5, domain: "FLO" },
      ]);
      expect(next.picked["A-1"]).toEqual({ connectionId: 4, domain: "FLO" });
      expect(next.picked["A-2"]).toEqual({ connectionId: null, domain: undefined });
    });

    test("keeps what a pick already knows and returns the same draft when nothing is new", () => {
      const known = draft({ mode: "EPICS", epics: ["A-1"], picked: { "A-1": { connectionId: 1, domain: null } } });
      expect(learnPicks(known, [{ value: "A-1", connectionId: 9, domain: "FLO" }])).toBe(known);
    });
  });
});

describe("selectionSummary", () => {
  const en = i18n.getFixedT("en");
  const pl = i18n.getFixedT("pl");
  const domainName = (key: string) => (key === "FLO" ? "Flow" : key);
  const names = { "41": "FLO Sprint 4", "42": "FLO Sprint 5" };

  test("sprints: domain name, the sprints by name, the range", () => {
    const selection = { domain: "FLO", sprintIds: [41, 42], from: "2026-09-01", to: "2026-10-15" };
    expect(selectionSummary(selection, names, domainName, en)).toBe("Flow · Sprints: FLO Sprint 4, FLO Sprint 5 · 2026-09-01 → 2026-10-15");
    expect(selectionSummary(selection, names, domainName, pl)).toBe("Flow · Sprinty: FLO Sprint 4, FLO Sprint 5 · 2026-09-01 → 2026-10-15");
  });

  test("epics: the keys, three at most then +N more (in both languages), a half-open range", () => {
    const selection = { epicIds: ["A-1", "A-2", "A-3", "A-4", "A-5"], to: "2026-10-15" };
    expect(selectionSummary(selection, {}, domainName, en)).toBe("Epics: A-1, A-2, A-3 +2 more · … → 2026-10-15");
    expect(selectionSummary(selection, {}, domainName, pl)).toBe("Epiki: A-1, A-2, A-3 +2 więcej · … → 2026-10-15");
  });

  test("an unnamed sprint reads by its id and no range adds no dates part", () => {
    expect(selectionSummary({ domain: "OPS", sprintIds: [7] }, {}, domainName, en)).toBe("OPS · Sprints: 7");
  });
});
