import { describe, expect, test } from "vitest";
import {
  applyBarChange,
  evmRows,
  hasOriginalPlan,
  indexVerdict,
  normalizeEpicProgressFilter,
  scopedSearch,
} from "./epicProgressReport";

describe("normalizeEpicProgressFilter", () => {
  test("drops what the report answers 400 to, or ignores: domain view, member, activity type, work category and the rest", () => {
    expect(
      normalizeEpicProgressFilter({
        lastSprints: 3,
        teamId: 1,
        accountId: "acc-ann",
        domainView: "TASK",
        activityType: "Bug",
        workCategory: "Maintenance",
        bucket: "MONTH",
        by: "STATUS",
        itemKind: "EPIC",
        connectionId: 2,
      }),
    ).toEqual({ lastSprints: 3, teamId: 1, connectionId: 2 });
  });

  test("keeps ONE scope: the epic over the domain over the team", () => {
    expect(normalizeEpicProgressFilter({ epicId: "FLO-33", domain: "FLO", teamId: 1 })).toEqual({ epicId: "FLO-33" });
    expect(normalizeEpicProgressFilter({ domain: "FLO", teamId: 1 })).toEqual({ domain: "FLO" });
    expect(normalizeEpicProgressFilter({ teamId: 0 })).toEqual({ teamId: 0 });
    expect(normalizeEpicProgressFilter({ from: "2026-01-01", to: "2026-01-31" })).toEqual({ from: "2026-01-01", to: "2026-01-31" });
  });
});

describe("applyBarChange", () => {
  test("picking a team replaces the epic and the domain", () => {
    expect(applyBarChange({ epicId: "FLO-33" }, { epicId: "FLO-33", teamId: 2 })).toEqual({ teamId: 2 });
    expect(applyBarChange({ domain: "FLO" }, { domain: "FLO", teamId: 2 })).toEqual({ teamId: 2 });
  });

  test("picking a domain replaces the epic and the team", () => {
    expect(applyBarChange({ epicId: "FLO-33" }, { epicId: "FLO-33", domain: "OPS" })).toEqual({ domain: "OPS" });
    expect(applyBarChange({ teamId: 2 }, { teamId: 2, domain: "OPS" })).toEqual({ domain: "OPS" });
  });

  test("a period change, or clearing a scope, leaves the scope alone", () => {
    expect(applyBarChange({ teamId: 2 }, { teamId: 2, lastSprints: 3 })).toEqual({ teamId: 2, lastSprints: 3 });
    expect(applyBarChange({ domain: "FLO" }, {})).toEqual({});
    expect(applyBarChange({ epicId: "FLO-33" }, { epicId: "FLO-33", from: "2026-01-01" })).toEqual({
      epicId: "FLO-33",
      from: "2026-01-01",
    });
  });
});

describe("scopedSearch", () => {
  const params = new URLSearchParams("from=2026-07-01&to=2026-09-29&teamId=1&accountId=acc-ann&activityType=Bug&utm=x");

  test("replaces the scope, keeps the period and foreign params, drops what the report cannot answer", () => {
    expect(scopedSearch(params, { domain: "FLO" })).toBe("utm=x&from=2026-07-01&to=2026-09-29&domain=FLO");
    expect(scopedSearch(params, { epicId: "FLO-33" })).toBe("utm=x&from=2026-07-01&to=2026-09-29&epicId=FLO-33");
    expect(scopedSearch(new URLSearchParams("domain=FLO&lastSprints=3"), { teamId: 0 })).toBe("lastSprints=3&teamId=0");
  });

  test("dropSprint removes a one-sprint period and nothing else", () => {
    const withSprint = new URLSearchParams("sprintId=11&connectionId=2");
    expect(scopedSearch(withSprint, { teamId: 2 }, { dropSprint: true })).toBe("teamId=2&connectionId=2");
    expect(scopedSearch(withSprint, { teamId: 2 })).toBe("sprintId=11&teamId=2&connectionId=2");
  });

  test("no scope is the unit: every scope param is gone", () => {
    expect(scopedSearch(new URLSearchParams("epicId=FLO-33&lastSprints=6"), {})).toBe("lastSprints=6");
  });
});

describe("the curves", () => {
  test("rows are the series with a missing original plan as null", () => {
    const rows = evmRows([
      { date: "2026-09-28", pv: 1, ev: 0, ac: 0, pvOriginal: null },
      { date: "2026-09-29", pv: 2, ev: 1, ac: 1, pvOriginal: 3 },
    ]);
    expect(rows).toEqual([
      { date: "2026-09-28", pv: 1, ev: 0, ac: 0, pvOriginal: null },
      { date: "2026-09-29", pv: 2, ev: 1, ac: 1, pvOriginal: 3 },
    ]);
    expect(hasOriginalPlan(rows)).toBe(true);
    expect(hasOriginalPlan(rows.slice(0, 1))).toBe(false);
    expect(hasOriginalPlan([])).toBe(false);
  });

  test("an index is on target at the two decimals it prints", () => {
    expect(indexVerdict(1)).toBe("on");
    expect(indexVerdict(0.996)).toBe("on");
    expect(indexVerdict(1.004)).toBe("on");
    expect(indexVerdict(1.02)).toBe("above");
    expect(indexVerdict(0.75)).toBe("below");
  });
});
