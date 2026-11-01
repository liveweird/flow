import { describe, expect, test } from "vitest";
import { normalizeTaskCodeFilter, TASK_CODE_CONTROLS } from "./taskCodeReport";
import { codeActivityPath, reportHref, taskCodePath } from "./reportLinks";

describe("Tasks and code filter", () => {
  test("the report is read for a team or a domain, with the Cycle time slices, a connection and no member", () => {
    expect(TASK_CODE_CONTROLS).toEqual({
      teamRequired: true,
      teamOrDomain: true,
      noMember: true,
      domainView: "TASK",
      domain: true,
      activityType: true,
      workCategory: true,
      connection: true,
    });
  });

  test("normalizing drops the members and every other report's key, and keeps the scope, the slices, the period, the connection and the tail", () => {
    const next = normalizeTaskCodeFilter({
      teamId: 1,
      accountId: "acc-ann",
      accountIds: ["acc-ann", "acc-bob"],
      from: "2026-07-01",
      to: "2026-09-30",
      tailPercent: 10,
      domain: "FLO",
      domainView: "EPIC",
      activityType: "Bug",
      workCategory: "Run",
      connectionId: 3,
      bucket: "MONTH",
      itemKind: "TASK",
      by: "STATUS",
      touchedWithinDays: 30,
    });
    expect(next).toEqual({
      teamId: 1,
      from: "2026-07-01",
      to: "2026-09-30",
      tailPercent: 10,
      domain: "FLO",
      domainView: "EPIC",
      activityType: "Bug",
      workCategory: "Run",
      connectionId: 3,
    });
  });

  test("a sprint period and a domain with no team are kept as they are", () => {
    expect(normalizeTaskCodeFilter({ domain: "FLO", lastSprints: 3 })).toEqual({ domain: "FLO", lastSprints: 3 });
    expect(normalizeTaskCodeFilter({ teamId: 1, sprintId: 11 })).toEqual({ teamId: 1, sprintId: 11 });
  });

  test("the tab link from Code activity keeps the team and period, drops the members; the way back keeps only the tail", () => {
    const search = "?teamId=1&accountId=acc-ann&accountId=acc-bob&from=2026-07-01&to=2026-09-30&tailPercent=10&drill=code%3AMRS_MERGED";
    expect(reportHref(taskCodePath, search)).toBe(`${taskCodePath}?teamId=1&from=2026-07-01&to=2026-09-30&tailPercent=10`);
    expect(reportHref(codeActivityPath, `?teamId=1&domain=FLO&activityType=Bug&from=2026-07-01&to=2026-09-30&tailPercent=10`)).toBe(
      `${codeActivityPath}?teamId=1&from=2026-07-01&to=2026-09-30&tailPercent=10`,
    );
  });
});
