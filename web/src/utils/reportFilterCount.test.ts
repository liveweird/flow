import { describe, expect, test } from "vitest";
import { activeReportFilterCount } from "./reportFilterCount";
import { presetRange, type ReportControls, type ReportFilterState } from "./reportFilter";

const TODAY = "2026-06-15";
const count = (filter: ReportFilterState, controls: ReportControls = {}, connectionCount = 1) =>
  activeReportFilterCount(filter, controls, { today: TODAY, connectionCount });

describe("activeReportFilterCount", () => {
  test("nothing in the filter is nothing active", () => {
    expect(count({})).toBe(0);
    expect(count({}, { domainView: "EPIC", domain: true, bucket: true, wipBy: true, itemKind: ["TASK", "EPIC"] })).toBe(0);
  });

  test("the long tail is no narrowing: it never counts", () => {
    expect(count({ tailPercent: 0 })).toBe(0);
    expect(count({ tailPercent: 25 }, { domainView: "TASK", domain: true, bucket: true })).toBe(0);
  });

  test("the default 90-day preset is the default, any other period counts once", () => {
    expect(count(presetRange("last90", TODAY))).toBe(0);
    expect(count(presetRange("last30", TODAY))).toBe(1);
    expect(count({ from: "2026-01-02", to: "2026-02-03" })).toBe(1);
    expect(count({ lastSprints: 3 })).toBe(1);
    expect(count({ sprintId: 12 })).toBe(1);
  });

  test("a report that reads no period never counts one", () => {
    expect(count({ lastSprints: 3 }, { noPeriod: true })).toBe(0);
  });

  test("a team counts, and a member under it counts again", () => {
    expect(count({ teamId: 1 })).toBe(1);
    expect(count({ teamId: 0 })).toBe(1);
    expect(count({ teamId: 1, accountId: "acc-1" })).toBe(2);
    expect(count({ teamId: 1, accountId: "acc-1" }, { noMember: true })).toBe(1);
    expect(count({ accountId: "acc-1" })).toBe(0);
  });

  test("several members count once, and a required team never counts (the bar has no whole unit to compare with)", () => {
    expect(count({ teamId: 1, accountIds: ["a", "b"] }, { multiMember: true })).toBe(2);
    expect(count({ teamId: 1, accountIds: ["a", "b"] }, { noMember: true })).toBe(1);
    expect(count({ accountIds: ["a"] })).toBe(0);
    expect(count({ teamId: 1 }, { teamRequired: true })).toBe(0);
    expect(count({ teamId: 1, accountIds: ["a"] }, { teamRequired: true, multiMember: true })).toBe(1);
    expect(count({ teamId: 1, lastSprints: 3 }, { teamRequired: true })).toBe(1);
  });

  test("a slice counts only where the report offers its control", () => {
    const filter: ReportFilterState = { domain: "FLO", activityType: "Bug", workCategory: "Run", connectionId: 2 };
    expect(count(filter)).toBe(0);
    expect(count(filter, { domain: true })).toBe(1);
    expect(count(filter, { domain: true, activityType: true, workCategory: true })).toBe(3);
    expect(count(filter, { connection: true }, 2)).toBe(1);
  });

  test("the connection control is absent with a single connection, so it never counts", () => {
    expect(count({ connectionId: 1 }, { connection: true }, 1)).toBe(0);
  });

  test("the segmented toggles count when they differ from what the control shows by default", () => {
    expect(count({ domainView: "EPIC" }, { domainView: "EPIC" })).toBe(0);
    expect(count({ domainView: "TASK" }, { domainView: "EPIC" })).toBe(1);
    expect(count({}, { domainView: "TASK" })).toBe(0);
    expect(count({ bucket: "WEEK" }, { bucket: true })).toBe(0);
    expect(count({ bucket: "MONTH" }, { bucket: true })).toBe(1);
    expect(count({ bucket: "MONTH" })).toBe(0);
    expect(count({ split: "WEEK" }, { split: true })).toBe(1);
    expect(count({ split: "SPRINT", teamId: 1 }, { split: true, teamRequired: true })).toBe(1);
    expect(count({}, { split: true })).toBe(0);
    expect(count({ split: "WEEK" })).toBe(0);
    expect(count({ itemKind: "TASK" }, { itemKind: ["TASK", "EPIC"] })).toBe(0);
    expect(count({ itemKind: "EPIC" }, { itemKind: ["TASK", "EPIC"] })).toBe(1);
    expect(count({ by: "STATUS" }, { wipBy: true })).toBe(1);
    expect(count({ by: "STAGE" }, { wipBy: true })).toBe(0);
  });

  test("a board-column keying nothing can honour reads as the default, one team's board counts", () => {
    expect(count({ by: "COLUMN" }, { wipBy: true })).toBe(0);
    expect(count({ by: "COLUMN", teamId: 1, domain: "FLO" }, { wipBy: true })).toBe(1);
    expect(count({ by: "COLUMN", teamId: 1 }, { wipBy: true })).toBe(2);
  });

  test("the dimensions add up", () => {
    expect(
      count(
        { lastSprints: 3, teamId: 1, accountId: "acc-1", domainView: "TASK", domain: "FLO", bucket: "MONTH" },
        { domainView: "EPIC", domain: true, bucket: true },
      ),
    ).toBe(6);
  });
});
