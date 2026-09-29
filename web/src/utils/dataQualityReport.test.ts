import { describe, expect, test } from "vitest";
import { connectionName, formatDriftDelta, formatDriftValue, moreCount, normalizeDataQualityFilter, teamName } from "./dataQualityReport";
import { FILTERS } from "../test/reportFixtures";

describe("normalizeDataQualityFilter", () => {
  test("drops every param the page has no control for, keeps the period, the drill, the domain and its view, and the connection", () => {
    expect(
      normalizeDataQualityFilter({
        from: "2026-09-01",
        to: "2026-09-29",
        teamId: 1,
        accountId: "acc-ann",
        domainView: "EPIC",
        domain: "FLO",
        connectionId: 2,
        activityType: "Bug",
        workCategory: "Maintenance",
        breakdown: "DOMAIN",
        bucket: "WEEK",
        by: "STATUS",
        itemKind: "EPIC",
        epicId: "FLO-33",
      }),
    ).toEqual({
      from: "2026-09-01",
      to: "2026-09-29",
      teamId: 1,
      accountId: "acc-ann",
      domainView: "EPIC",
      domain: "FLO",
      connectionId: 2,
    });
  });

  test("a filter that needs no change is equal to itself (so the URL is not rewritten)", () => {
    const filter = { lastSprints: 3, teamId: 0 };
    expect(normalizeDataQualityFilter(filter)).toEqual(filter);
  });
});

describe("moreCount", () => {
  test("is what the capped list left out, never negative", () => {
    expect(moreCount({ total: 75, items: new Array(50) })).toBe(25);
    expect(moreCount({ total: 3, items: new Array(3) })).toBe(0);
    expect(moreCount({ total: 0, items: [] })).toBe(0);
    expect(moreCount({ total: 2, items: new Array(5) })).toBe(0);
  });
});

describe("names from the reference data", () => {
  test("a connection or team no longer listed falls back to its id; the unassigned bucket to the given text", () => {
    expect(connectionName(FILTERS, 1)).toBe("Stub");
    expect(connectionName(FILTERS, 9)).toBe("#9");
    expect(teamName(FILTERS, 1, "Unassigned")).toBe("Alpha");
    expect(teamName(FILTERS, 9, "Unassigned")).toBe("#9");
    expect(teamName(FILTERS, null, "Unassigned")).toBe("Unassigned");
  });
});

describe("drift figures", () => {
  test("man-days keep two decimals, items are whole, the load keeps three; a missing side is a dash", () => {
    expect(formatDriftValue("capacityMd", 40)).toBe("40");
    expect(formatDriftValue("deliveredMd", 12.345)).toBe("12.35");
    expect(formatDriftValue("committedItems", 9)).toBe("9");
    expect(formatDriftValue("load", 0.87549)).toBe("0.875");
    expect(formatDriftValue("load", null)).toBe("—");
  });

  test("the difference carries a sign in the figure's own unit, with a true minus", () => {
    expect(formatDriftDelta("capacityMd", 8)).toBe("+8");
    expect(formatDriftDelta("capacityMd", -0.5)).toBe("−0.5");
    expect(formatDriftDelta("addedItems", 1)).toBe("+1");
    expect(formatDriftDelta("addedItems", -2)).toBe("−2");
    expect(formatDriftDelta("addedItems", 0)).toBe("0");
    expect(formatDriftDelta("load", -0.225)).toBe("−0.225");
    expect(formatDriftDelta("load", 0.0001)).toBe("0");
    expect(formatDriftDelta("load", null)).toBe("—");
  });
});
