import { describe, expect, test } from "vitest";
import { breakdownAnchor, costDrillAnchor, isSprintRelative, normalizeCostsFilter } from "./costsReport";
import { COSTS_SPRINTS, COSTS_TEAM } from "../test/reportFixtures";

describe("normalizeCostsFilter", () => {
  test("keeps the period, the member, the domain and its view, the activity type, and the work category — drops the other reports' own params and a stale connection", () => {
    expect(
      normalizeCostsFilter({
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
    const filter = { lastSprints: 3, teamId: 1 };
    expect(normalizeCostsFilter(filter)).toEqual(filter);
  });
});

describe("isSprintRelative", () => {
  test("a period without dates is the sprints' window", () => {
    expect(isSprintRelative(COSTS_SPRINTS.meta)).toBe(true);
    expect(isSprintRelative(COSTS_TEAM.meta)).toBe(false);
  });
});

describe("costDrillAnchor", () => {
  test("a donut's drill returns focus to its card; any other target to the first card", () => {
    expect(costDrillAnchor({ kind: "cost", by: "WORK_CATEGORY", keys: ["Run"] })).toBe("cost-work-category");
    expect(costDrillAnchor({ kind: "cost", by: "ACTIVITY_TYPE", keys: ["Dev"] })).toBe(breakdownAnchor("ACTIVITY_TYPE"));
    expect(costDrillAnchor({ kind: "bucket", bucketStart: "2026-09-07" })).toBe("cost-activity-type");
  });
});
