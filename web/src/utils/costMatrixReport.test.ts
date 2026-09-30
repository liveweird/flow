import { describe, expect, test } from "vitest";
import { drillSearch, isSprintRelative, maxCellMd, normalizeCostMatrixFilter } from "./costMatrixReport";
import { COST_MATRIX_EMPTY, COST_MATRIX_SPRINTS, COST_MATRIX_TEAM, COST_MATRIX_UNIT, FILTERS } from "../test/reportFixtures";

describe("normalizeCostMatrixFilter", () => {
  test("keeps the period, the drill, the domain and its view, the activity type, the work category and the connection — drops the other reports' own params", () => {
    expect(
      normalizeCostMatrixFilter({
        from: "2026-09-01",
        to: "2026-09-29",
        teamId: 1,
        accountId: "acc-ann",
        domainView: "TASK",
        domain: "FLO",
        activityType: "Bug",
        workCategory: "Maintenance",
        connectionId: 2,
        epicId: "FLO-33",
        bucket: "WEEK",
        by: "STATUS",
        itemKind: "EPIC",
      }),
    ).toEqual({
      from: "2026-09-01",
      to: "2026-09-29",
      teamId: 1,
      accountId: "acc-ann",
      domainView: "TASK",
      domain: "FLO",
      activityType: "Bug",
      workCategory: "Maintenance",
      connectionId: 2,
    });
  });

  test("a filter that needs no change is equal to itself (so the URL is not rewritten)", () => {
    const filter = { lastSprints: 3, teamId: 0 };
    expect(normalizeCostMatrixFilter(filter)).toEqual(filter);
  });
});

describe("isSprintRelative", () => {
  test("a period without dates is the sprints' window", () => {
    expect(isSprintRelative(COST_MATRIX_SPRINTS.meta)).toBe(true);
    expect(isSprintRelative(COST_MATRIX_UNIT.meta)).toBe(false);
  });
});

describe("maxCellMd", () => {
  test("is the largest single cell — never a total — and 0 when there is none", () => {
    expect(maxCellMd(COST_MATRIX_UNIT)).toBe(10);
    expect(maxCellMd(COST_MATRIX_TEAM)).toBe(4);
    expect(maxCellMd(COST_MATRIX_EMPTY)).toBe(0);
  });
});

describe("drillSearch", () => {
  const drill = (query: string, target: { teamId: number; accountId?: string }) => drillSearch(new URLSearchParams(query), FILTERS, target);

  test("re-scopes the same report, the period and the slices travelling along, in the canonical order", () => {
    expect(drill("domain=FLO&from=2026-09-01&to=2026-09-29&domainView=TASK", { teamId: 1 })).toBe(
      "from=2026-09-01&to=2026-09-29&teamId=1&domainView=TASK&domain=FLO",
    );
    expect(drill("lastSprints=3&teamId=1", { teamId: 1, accountId: "acc-ann" })).toBe("lastSprints=3&teamId=1&accountId=acc-ann");
  });

  test("a drill to a team replaces the member; foreign params of the URL survive", () => {
    expect(drill("teamId=2&accountId=acc-cy&x=1", { teamId: 1 })).toBe("x=1&teamId=1");
  });

  test("a one-sprint period stays only for a team that lists the sprint", () => {
    expect(drill("sprintId=11", { teamId: 1 })).toBe("sprintId=11&teamId=1");
    expect(drill("sprintId=21", { teamId: 1 })).toBe("teamId=1");
    expect(drill("sprintId=11", { teamId: 99 })).toBe("teamId=99");
  });

  test("params only other reports own never follow the drill", () => {
    expect(drill("bucket=WEEK&epicId=FLO-1&itemKind=EPIC", { teamId: 1 })).toBe("teamId=1");
  });
});
