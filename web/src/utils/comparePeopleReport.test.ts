import { describe, expect, test } from "vitest";
import type { CompareRow } from "../api/reports";
import i18n from "../i18n";
import { COMPARE_PEOPLE, COMPARE_PEOPLE_CAPACITY } from "../test/comparePeopleFixtures";
import {
  betterLabel,
  cellCountOf,
  cellText,
  differenceText,
  drillCount,
  mixKeyLabel,
  normalizeComparePeopleFilter,
  viewColumns,
} from "./comparePeopleReport";

const t = i18n.t.bind(i18n);
const rowOf = (key: CompareRow["key"], report = COMPARE_PEOPLE_CAPACITY): CompareRow => report.rows.find((row) => row.key === key) as CompareRow;

describe("normalizeComparePeopleFilter", () => {
  test("drops a team, a single member and a sprint-relative period, and keeps the two people, the dates and the slices", () => {
    expect(
      normalizeComparePeopleFilter({
        teamId: 2,
        accountId: "acc-x",
        lastSprints: 3,
        sprintId: 9,
        from: "2026-01-01",
        to: "2026-02-01",
        accountIds: ["acc-ann", "acc-cy"],
        domainView: "EPIC",
        domain: "FLO",
        activityType: "Bug",
        workCategory: "Maintenance",
        tailPercent: 10,
        connectionId: 1,
      }),
    ).toEqual({
      from: "2026-01-01",
      to: "2026-02-01",
      accountIds: ["acc-ann", "acc-cy"],
      domainView: "EPIC",
      domain: "FLO",
      activityType: "Bug",
      workCategory: "Maintenance",
      tailPercent: 10,
      connectionId: 1,
    });
  });

  test("drops the keys of other reports and any people beyond the first two (the server answers three with a 400)", () => {
    expect(
      normalizeComparePeopleFilter({ bucket: "MONTH", by: "STATUS", itemKind: "EPIC", epicId: "FLO-1", split: "WEEK", accountIds: ["a", "b", "c"] }),
    ).toEqual({ accountIds: ["a", "b"] });
  });

  test("a filter without people is left without them", () => {
    expect(normalizeComparePeopleFilter({ from: "2026-01-01" })).toEqual({ from: "2026-01-01" });
  });
});

describe("a row's figures", () => {
  test("each unit is written in its own way, a withheld or empty figure as a dash", () => {
    expect(cellText(rowOf("deliveredMd"), rowOf("deliveredMd").a)).toBe("31.5");
    expect(cellText(rowOf("deliveredItems"), rowOf("deliveredItems").a)).toBe("14");
    expect(cellText(rowOf("cycleWorkingDaysP50"), rowOf("cycleWorkingDaysP50").b)).toBe("4.9");
    expect(cellText(rowOf("flowEfficiencyP50"), rowOf("flowEfficiencyP50").a)).toBe("42%");
    expect(cellText(rowOf("reportedTimeRatioP50"), rowOf("reportedTimeRatioP50").b)).toBe("1.3");
    expect(cellText(rowOf("avgWip"), rowOf("avgWip").a)).toBe("2.4");
    expect(cellText(rowOf("deliveredItemsPerAvailableMd"), rowOf("deliveredItemsPerAvailableMd").a)).toBe("0.7");
    // The median change of an estimate is a signed percentage; withheld under the minimum sample it is a dash.
    expect(cellText(rowOf("adjustmentMedianChange"), rowOf("adjustmentMedianChange").a)).toBe("+25%");
    expect(cellText(rowOf("adjustmentMedianChange"), rowOf("adjustmentMedianChange").b)).toBe("—");
  });

  test("the difference is the absolute gap, no sign; shares and the estimate change are percentage POINTS; no value on either side is a dash", () => {
    expect(differenceText(rowOf("deliveredMd"), t)).toBe("7.5");
    expect(differenceText(rowOf("cycleWorkingDaysP50"), t)).toBe("1.4");
    // A tie (0.02 days) prints at the figure's one decimal, so as a plain zero.
    expect(differenceText(rowOf("cycleWorkingDaysP90"), t)).toBe("0");
    expect(differenceText(rowOf("flowEfficiencyP50"), t)).toBe("12 pp");
    expect(differenceText(rowOf("accuracyAtStartPred25"), t)).toBe("0 pp");
    expect(differenceText(rowOf("blockedShareOfCycle"), t)).toBe("2 pp");
    expect(differenceText(rowOf("adjustmentMedianChange"), t)).toBe("—");
  });

  test("a gap that prints as zero carries no sign", () => {
    expect(differenceText({ ...rowOf("deliveredMd"), difference: 0.001 }, t)).toBe("0");
  });
});

describe("betterLabel — the words behind the better cell", () => {
  test("names the better side and by how much, in the row's unit", () => {
    expect(betterLabel(rowOf("cycleWorkingDaysP50"), t)).toBe("better by 1.4 days");
    expect(betterLabel(rowOf("cycleElapsedDaysP50"), t)).toBe("better by 0.8 days");
    expect(betterLabel({ ...rowOf("cycleElapsedDaysP50"), margin: 1 }, t)).toBe("better by 1 day");
    expect(betterLabel(rowOf("deliveredMd"), t)).toBe("better by 7.5 MD");
    expect(betterLabel(rowOf("deliveredItems"), t)).toBe("better by 4 items");
    expect(betterLabel({ ...rowOf("deliveredItems"), margin: 1 }, t)).toBe("better by 1 item");
    expect(betterLabel(rowOf("flowEfficiencyP50"), t)).toBe("better by 12 pp");
    expect(betterLabel(rowOf("avgWip"), t)).toBe("better by 0.7 items per day");
    expect(betterLabel(rowOf("deliveredItemsPerAvailableMd"), t)).toBe("better by 0.2 items per available MD");
    expect(betterLabel(rowOf("deliveredMdPerAvailableMd"), t)).toBe("better by 0.05");
  });

  test("a row with a target says the side is closer to it", () => {
    expect(betterLabel(rowOf("reportedTimeRatioP50"), t)).toBe("closer to 1.0 by 0.1");
    expect(betterLabel(rowOf("loggedPerAvailableMd"), t)).toBe("closer to 1.0 by 0.12");
  });

  test("a tie, a neutral row and a side under the minimum sample have none", () => {
    expect(betterLabel(rowOf("cycleWorkingDaysP90"), t)).toBeUndefined();
    expect(betterLabel(rowOf("adjustedShare"), t)).toBeUndefined();
    expect(betterLabel(rowOf("adjustmentMedianChange"), t)).toBeUndefined();
    // A better side with no margin cannot be worded: no cue rather than a half-sentence.
    expect(betterLabel({ ...rowOf("deliveredMd"), margin: undefined }, t)).toBeUndefined();
  });

  test("the Polish names inflect days and items by the printed number", () => {
    const pl = i18n.getFixedT("pl");
    expect(betterLabel({ ...rowOf("cycleElapsedDaysP50"), margin: 1 }, pl)).toBe("lepiej o 1 dzień");
    expect(betterLabel({ ...rowOf("cycleElapsedDaysP50"), margin: 3 }, pl)).toBe("lepiej o 3 dni");
    expect(betterLabel({ ...rowOf("cycleElapsedDaysP50"), margin: 1.4 }, pl)).toBe("lepiej o 1.4 dnia");
    expect(betterLabel({ ...rowOf("deliveredItems"), margin: 5 }, pl)).toBe("lepiej o 5 zadań");
    expect(betterLabel(rowOf("reportedTimeRatioP50"), pl)).toBe("bliżej 1.0 o 0.1");
    expect(betterLabel({ ...rowOf("avgWip"), margin: 2 }, pl)).toBe("lepiej o 2 zadania na dzień");
    expect(betterLabel({ ...rowOf("avgWip"), margin: 5 }, pl)).toBe("lepiej o 5 zadań na dzień");
    expect(betterLabel({ ...rowOf("avgWip"), margin: 1 }, t)).toBe("better by 1 item per day");
  });
});

describe("drillCount — what decides whether a figure is a button", () => {
  test("a distribution or a count lists its sample", () => {
    expect(drillCount(rowOf("deliveredMd"), rowOf("deliveredMd").a)).toBe(14);
    expect(drillCount(rowOf("cycleWorkingDaysP50"), rowOf("cycleWorkingDaysP50").b)).toBe(7);
    expect(drillCount(rowOf("loggedMd"), rowOf("loggedMd").a)).toBe(15);
  });

  test("a share lists its numerator: a zero share, or full coverage, has nothing to open", () => {
    expect(drillCount(rowOf("lateLoggingShare"), rowOf("lateLoggingShare").a)).toBe(15);
    expect(drillCount(rowOf("noEstimateShare"), rowOf("noEstimateShare").a)).toBe(0);
    expect(drillCount(rowOf("foreignShare"), rowOf("foreignShare").b)).toBe(0);
    expect(drillCount(rowOf("worklogCoverage"), rowOf("worklogCoverage").a)).toBe(14);
    expect(drillCount(rowOf("worklogCoverage"), rowOf("worklogCoverage").b)).toBe(0);
  });

  test("a withheld figure, one with no value and a row with no view have nothing to open", () => {
    expect(drillCount(rowOf("adjustmentMedianChange"), rowOf("adjustmentMedianChange").b)).toBe(0);
    expect(drillCount(rowOf("deliveredMd"), { value: null, n: 3, hidden: false })).toBe(0);
    expect(drillCount(rowOf("availableMd"), rowOf("availableMd").a)).toBe(0);
  });
});

describe("cellCountOf — the count the drawer is compared with", () => {
  test("is the row's sample for a view that lists exactly what the sample counts", () => {
    expect(cellCountOf(COMPARE_PEOPLE, "DELIVERED", 0)).toBe(14);
    expect(cellCountOf(COMPARE_PEOPLE, "DELIVERED", 1)).toBe(10);
    expect(cellCountOf(COMPARE_PEOPLE, "LOGGED", 1)).toBe(12);
  });

  test("is none where the sample is not the list's length (a share's numerator, the average WIP's days) or the report has no such row", () => {
    expect(cellCountOf(COMPARE_PEOPLE, "LATE", 0)).toBeUndefined();
    expect(cellCountOf(COMPARE_PEOPLE, "WIP_TASK_DAYS", 0)).toBeUndefined();
    expect(cellCountOf(COMPARE_PEOPLE, "NO_WORKLOGS", 0)).toBeUndefined();
    expect(cellCountOf({ rows: [] }, "DELIVERED", 0)).toBeUndefined();
  });
});

describe("mixKeyLabel", () => {
  const domains = [{ domainKey: "FLO", domainName: "Flow" }];
  test("prints a key as the bars do", () => {
    expect(mixKeyLabel("activityType", "Bug", t, domains)).toBe("Bug");
    expect(mixKeyLabel("workCategory", "UNCATEGORIZED", t, domains)).toBe("(uncategorized)");
    expect(mixKeyLabel("workCategory", "Maintenance", t, domains)).toBe("Maintenance");
    expect(mixKeyLabel("domain", "FLO", t, domains)).toBe("Flow");
    expect(mixKeyLabel("domain", "OLD", t, domains)).toBe("OLD");
  });

  test("a task without a value is '(no …)'", () => {
    expect(mixKeyLabel("activityType", null, t, domains)).toBe("(no activity type)");
    expect(mixKeyLabel("domain", null, t, domains)).toBe("(no domain)");
  });
});

describe("viewColumns", () => {
  test("each view reads in its own unit and date; an unknown one reads as delivered tasks", () => {
    expect(viewColumns("CYCLE_ELAPSED_DAYS")).toEqual({ unit: "days", anchor: "done" });
    expect(viewColumns("LOGGED")).toEqual({ unit: "loggedMd", anchor: "worklog" });
    expect(viewColumns("WIP_TASK_DAYS")).toEqual({ unit: "heldDays", anchor: "none" });
    expect(viewColumns("SOMETHING_NEW")).toEqual({ unit: "md", anchor: "done" });
  });
});
