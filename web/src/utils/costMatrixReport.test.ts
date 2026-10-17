import { describe, expect, test } from "vitest";
import { bucketHeading, costSprintSplitAvailable, drillSearch, isSprintRelative, maxCellMd, normalizeCostMatrixFilter } from "./costMatrixReport";
import {
  COST_MATRIX_EMPTY,
  COST_MATRIX_SPRINT_SPLIT,
  COST_MATRIX_SPRINTS,
  COST_MATRIX_TEAM,
  COST_MATRIX_UNIT,
  COST_MATRIX_WEEKS,
  FILTERS,
} from "../test/reportFixtures";
import i18n from "../i18n";

describe("normalizeCostMatrixFilter", () => {
  test("keeps the period, the drill, the domain and its view, the activity type, and the work category — drops the other reports' own params and a stale connection", () => {
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
    });
  });

  test("a filter that needs no change is equal to itself (so the URL is not rewritten)", () => {
    const filter = { lastSprints: 3, teamId: 0 };
    expect(normalizeCostMatrixFilter(filter)).toEqual(filter);
  });
});

describe("the time split", () => {
  test("the sprint split needs a real team: not the unit, not the unassigned bucket", () => {
    expect(costSprintSplitAvailable({ teamId: 1 })).toBe(true);
    expect(costSprintSplitAvailable({ teamId: 1, accountId: "acc-ann" })).toBe(true);
    expect(costSprintSplitAvailable({})).toBe(false);
    expect(costSprintSplitAvailable({ teamId: 0 })).toBe(false);
  });

  test("the normalizer keeps a split it can answer and drops a sprint split no team backs (it would be a 400)", () => {
    expect(normalizeCostMatrixFilter({ split: "WEEK" })).toEqual({ split: "WEEK" });
    expect(normalizeCostMatrixFilter({ teamId: 0, split: "WEEK" })).toEqual({ teamId: 0, split: "WEEK" });
    expect(normalizeCostMatrixFilter({ teamId: 2, split: "SPRINT" })).toEqual({ teamId: 2, split: "SPRINT" });
    expect(normalizeCostMatrixFilter({ lastSprints: 3, split: "SPRINT" })).toEqual({ lastSprints: 3 });
    expect(normalizeCostMatrixFilter({ teamId: 0, split: "SPRINT" })).toEqual({ teamId: 0 });
  });

  test("a split carries through a drill", () => {
    expect(drillSearch(new URLSearchParams("split=WEEK&lastSprints=3"), FILTERS, { teamId: 1 })).toBe("lastSprints=3&teamId=1&split=WEEK");
  });

  test("the heat scale is measured over the cells shown: the buckets' cells when split, not the period's", () => {
    // The period's FLO cell is 10, but a split shows only the buckets' own cells (the largest is 6).
    expect(maxCellMd(COST_MATRIX_UNIT)).toBe(10);
    expect(maxCellMd(COST_MATRIX_WEEKS)).toBe(6);
    expect(maxCellMd(COST_MATRIX_SPRINT_SPLIT)).toBe(3);
  });

  test("a group is headed by a week's Monday, a sprint's name, or 'Outside sprints'", () => {
    const t = i18n.t.bind(i18n);
    expect(COST_MATRIX_WEEKS.split?.buckets.map((bucket) => bucketHeading(bucket, "WEEK", t))).toEqual(["2026-09-21", "2026-09-28"]);
    expect(COST_MATRIX_SPRINT_SPLIT.split?.buckets.map((bucket) => bucketHeading(bucket, "SPRINT", t))).toEqual([
      "Alpha 1",
      "Alpha 2",
      "Outside sprints",
    ]);
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
