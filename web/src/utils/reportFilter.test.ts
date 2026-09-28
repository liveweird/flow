import { describe, expect, test } from "vitest";
import { epochMillisToIsoDateInZone } from "./isoDate";
import {
  activePeriodChoice,
  applyReportFilter,
  filterLevel,
  parseReportFilter,
  presetRange,
  reportQuery,
  serializeReportFilter,
  withPeriod,
  type ReportFilterState,
} from "./reportFilter";

const parse = (query: string) => parseReportFilter(new URLSearchParams(query));

describe("parseReportFilter / serializeReportFilter", () => {
  test("a full filter round-trips through the URL", () => {
    const filter: ReportFilterState = {
      from: "2026-01-01",
      to: "2026-03-31",
      teamId: 4,
      accountId: "acc-1",
      domainView: "EPIC",
      domain: "FLO",
      activityType: "Bug",
      workCategory: "Maintenance",
      breakdown: "DOMAIN",
      connectionId: 2,
    };
    expect(parseReportFilter(serializeReportFilter(filter))).toEqual(filter);
  });

  test("the sprint-relative periods round-trip", () => {
    expect(parse("lastSprints=3&teamId=2")).toEqual({ lastSprints: 3, teamId: 2 });
    expect(parse("sprintId=17&teamId=2")).toEqual({ sprintId: 17, teamId: 2 });
  });

  test("an empty query is an empty filter", () => {
    expect(parse("")).toEqual({});
    expect(reportQuery({})).toBe("");
  });

  test("the period is exclusive: sprintId beats lastSprints beats dates", () => {
    expect(parse("sprintId=5&lastSprints=3&from=2026-01-01")).toEqual({ sprintId: 5 });
    expect(parse("lastSprints=3&from=2026-01-01&to=2026-02-01")).toEqual({ lastSprints: 3 });
  });

  test("invalid params are dropped, never forwarded", () => {
    expect(parse("from=2026-02-30&to=nope")).toEqual({});
    expect(parse("lastSprints=0")).toEqual({});
    expect(parse("lastSprints=53")).toEqual({});
    expect(parse("sprintId=-3&teamId=abc")).toEqual({});
    expect(parse("domainView=SIDEWAYS&breakdown=x&connectionId=0")).toEqual({});
    expect(parse("domain=&activityType=%20")).toEqual({});
  });

  test("a reversed or over-long range is dropped as a pair", () => {
    expect(parse("from=2026-03-01&to=2026-01-01")).toEqual({});
    expect(parse("from=2020-01-01&to=2026-01-01")).toEqual({});
    expect(parse("from=2026-01-01")).toEqual({ from: "2026-01-01" });
    expect(parse("to=2026-01-01")).toEqual({ to: "2026-01-01" });
  });

  test("accountId needs a teamId; teamId 0 is the unassigned bucket, a real value", () => {
    expect(parse("accountId=acc-1")).toEqual({});
    expect(parse("teamId=0&accountId=acc-1")).toEqual({ teamId: 0, accountId: "acc-1" });
  });

  test("serialization uses one canonical key order", () => {
    expect(reportQuery({ accountId: "a", teamId: 1, to: "2026-02-01", from: "2026-01-01" })).toBe(
      "from=2026-01-01&to=2026-02-01&teamId=1&accountId=a",
    );
  });

  test("applyReportFilter replaces managed keys and keeps unrelated ones", () => {
    const next = applyReportFilter(new URLSearchParams("teamId=1&lastSprints=3&bucket=MONTH"), { teamId: 2 });
    expect(next.toString()).toBe("bucket=MONTH&teamId=2");
  });
});

describe("filterLevel", () => {
  test("unit, team and user", () => {
    expect(filterLevel({})).toBe("UNIT");
    expect(filterLevel({ teamId: 3 })).toBe("TEAM");
    expect(filterLevel({ teamId: 3, accountId: "a" })).toBe("USER");
  });
});

describe("period presets", () => {
  const today = "2026-09-29";

  test("preset ranges are inclusive calendar ranges", () => {
    expect(presetRange("last30", today)).toEqual({ from: "2026-08-31", to: today });
    expect(presetRange("last90", today)).toEqual({ from: "2026-07-02", to: today });
    expect(presetRange("last365", today)).toEqual({ from: "2025-09-30", to: today });
    expect(presetRange("thisMonth", today)).toEqual({ from: "2026-09-01", to: today });
    expect(presetRange("lastMonth", today)).toEqual({ from: "2026-08-01", to: "2026-08-31" });
    expect(presetRange("thisQuarter", today)).toEqual({ from: "2026-07-01", to: today });
  });

  test("presets computed at 00:30 local on the 1st use the local date, not UTC's", () => {
    // 23:30Z on Oct 31 is 00:30 on Nov 1 in Warsaw — "this month" is November, "last month" October.
    const today = epochMillisToIsoDateInZone(Date.UTC(2026, 9, 31, 23, 30), "Europe/Warsaw");
    expect(today).toBe("2026-11-01");
    expect(presetRange("thisMonth", today)).toEqual({ from: "2026-11-01", to: "2026-11-01" });
    expect(presetRange("lastMonth", today)).toEqual({ from: "2026-10-01", to: "2026-10-31" });
    expect(presetRange("last30", today).to).toBe("2026-11-01");
  });

  test("last month crosses a year boundary", () => {
    expect(presetRange("lastMonth", "2026-01-15")).toEqual({ from: "2025-12-01", to: "2025-12-31" });
  });

  test("activePeriodChoice recognises presets, sprints and custom ranges", () => {
    expect(activePeriodChoice({}, today)).toBe("last90");
    expect(activePeriodChoice({ ...presetRange("lastMonth", today) }, today)).toBe("lastMonth");
    expect(activePeriodChoice({ from: presetRange("last30", today).from }, today)).toBe("last30");
    expect(activePeriodChoice({ from: "2026-01-01", to: "2026-01-31" }, today)).toBe("custom");
    expect(activePeriodChoice({ lastSprints: 6 }, today)).toBe("lastSprints:6");
    expect(activePeriodChoice({ sprintId: 9 }, today)).toBe("sprint");
  });

  test("withPeriod swaps only the period", () => {
    expect(withPeriod({ teamId: 2, sprintId: 9 }, { from: "2026-01-01", to: "2026-02-01" })).toEqual({
      teamId: 2,
      from: "2026-01-01",
      to: "2026-02-01",
    });
    expect(withPeriod({ teamId: 2, from: "2026-01-01" }, { lastSprints: 3 })).toEqual({ teamId: 2, lastSprints: 3 });
  });
});
