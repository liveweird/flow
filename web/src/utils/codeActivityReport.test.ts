import { describe, expect, test } from "vitest";
import { CODE_ACTIVITY_CONTROLS, normalizeCodeActivityFilter } from "./codeActivityReport";
import { reportHref } from "./reportLinks";

describe("Code activity filter", () => {
  test("the report is read per team (or Unassigned), with several members and no slice of its own", () => {
    expect(CODE_ACTIVITY_CONTROLS).toEqual({ teamRequired: true, teamUnassigned: true, multiMember: true });
  });

  test("normalizing drops every other report's key and the Jira connection, and keeps the team, members, period and tail", () => {
    const next = normalizeCodeActivityFilter({
      teamId: 1,
      accountIds: ["acc-ann", "acc-bob"],
      from: "2026-07-01",
      to: "2026-09-30",
      tailPercent: 10,
      domain: "FLO",
      domainView: "EPIC",
      activityType: "Bug",
      workCategory: "Run",
      bucket: "MONTH",
      itemKind: "TASK",
      connectionId: 3,
    });
    expect(next).toEqual({ teamId: 1, accountIds: ["acc-ann", "acc-bob"], from: "2026-07-01", to: "2026-09-30", tailPercent: 10 });
  });

  test("Unassigned (teamId=0) is read for the whole bucket: a member in such a link is dropped, the period and tail stay", () => {
    expect(normalizeCodeActivityFilter({ teamId: 0, accountId: "acc-ann", accountIds: ["acc-ann"], from: "2026-07-01", to: "2026-09-30", tailPercent: 10 })).toEqual({
      teamId: 0,
      from: "2026-07-01",
      to: "2026-09-30",
      tailPercent: 10,
    });
  });

  test("Unassigned resolves no sprints: a pasted or remembered sprint-relative period falls back to the default dates", () => {
    expect(normalizeCodeActivityFilter({ teamId: 0, lastSprints: 3 })).toEqual({ teamId: 0 });
    expect(normalizeCodeActivityFilter({ teamId: 0, sprintId: 11 })).toEqual({ teamId: 0 });
    // A team keeps its sprints.
    expect(normalizeCodeActivityFilter({ teamId: 1, lastSprints: 3 })).toEqual({ teamId: 1, lastSprints: 3 });
  });

  test("a tab hop onto the page keeps every member and the tail, and drops the slices the page cannot show", () => {
    expect(reportHref("/reports/code-activity", "teamId=1&accountId=a&accountId=b&domain=FLO&tailPercent=10&bucket=WEEK")).toBe(
      "/reports/code-activity?teamId=1&accountId=a&accountId=b&tailPercent=10",
    );
  });
});
