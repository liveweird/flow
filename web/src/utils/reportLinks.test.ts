import { describe, expect, test } from "vitest";
import { deepDiveBasePath, deepDivePath, reportHref } from "./reportLinks";

describe("reportHref", () => {
  test("keeps the shared params (period, team, member, connection) on every report", () => {
    const search = "lastSprints=3&teamId=2&accountId=a&connectionId=1";
    for (const path of ["/reports/velocity", "/reports/throughput", "/reports/sprint-consistency"]) {
      expect(reportHref(path, search)).toBe(`${path}?${search}`);
    }
  });

  test("drops the report-specific params the target has no control for", () => {
    const search = "teamId=2&domainView=EPIC&domain=FLO&activityType=Bug&workCategory=X&bucket=MONTH";
    expect(reportHref("/reports/velocity", search)).toBe("/reports/velocity?teamId=2");
    expect(reportHref("/reports/sprint-consistency", search)).toBe("/reports/sprint-consistency?teamId=2");
    // Throughput keeps its own five.
    expect(reportHref("/reports/throughput", search)).toBe(
      "/reports/throughput?teamId=2&domainView=EPIC&domain=FLO&activityType=Bug&workCategory=X&bucket=MONTH",
    );
  });

  test("estimation reports keep exactly the controls they show", () => {
    const search = "teamId=2&domainView=EPIC&domain=FLO&activityType=Bug&workCategory=X&bucket=MONTH";
    expect(reportHref("/reports/task-estimation-accuracy", search)).toBe(
      "/reports/task-estimation-accuracy?teamId=2&domainView=EPIC&domain=FLO&activityType=Bug&workCategory=X",
    );
    expect(reportHref("/reports/estimate-adjustments", search)).toBe(
      "/reports/estimate-adjustments?teamId=2&domainView=EPIC&domain=FLO&activityType=Bug&workCategory=X",
    );
    // Epics have no activity type and no domain-view toggle: those would be hidden filters there.
    expect(reportHref("/reports/epic-estimation-accuracy", search)).toBe(
      "/reports/epic-estimation-accuracy?teamId=2&domain=FLO&workCategory=X",
    );
  });

  test("cycle time keeps its trend bucket, reported time does not", () => {
    const search = "teamId=2&domainView=EPIC&domain=FLO&activityType=Bug&workCategory=X&bucket=MONTH";
    expect(reportHref("/reports/cycle-time", search)).toBe(`/reports/cycle-time?${search}`);
    expect(reportHref("/reports/reported-time-ratio", search)).toBe(
      "/reports/reported-time-ratio?teamId=2&domainView=EPIC&domain=FLO&activityType=Bug&workCategory=X",
    );
  });

  test("the flow reports keep exactly the controls they show", () => {
    const search = "teamId=2&domainView=EPIC&domain=FLO&activityType=Bug&workCategory=X&bucket=MONTH&by=STATUS&itemKind=EPIC";
    // WIP: a domain, what it counts and how it keys — no domain view, activity type, work category or bucket.
    expect(reportHref("/reports/wip", search)).toBe("/reports/wip?teamId=2&domain=FLO&by=STATUS&itemKind=EPIC");
    // The backlog has neither by nor itemKind.
    expect(reportHref("/reports/backlog", search)).toBe("/reports/backlog?teamId=2&domain=FLO");
    // …and no other report carries WIP's own two.
    expect(reportHref("/reports/velocity", search)).toBe("/reports/velocity?teamId=2");
    expect(reportHref("/reports/cycle-time", search)).not.toContain("by=");
  });

  test("aging WIP slices like the fact reports; blocked time also keeps its item kind — neither keeps by, bucket or a domain view", () => {
    const search = "teamId=2&domainView=EPIC&domain=FLO&activityType=Bug&workCategory=X&bucket=MONTH&by=STATUS&itemKind=EPIC";
    expect(reportHref("/reports/aging-wip", search)).toBe("/reports/aging-wip?teamId=2&domain=FLO&activityType=Bug&workCategory=X");
    expect(reportHref("/reports/blocked-time", search)).toBe(
      "/reports/blocked-time?teamId=2&domain=FLO&activityType=Bug&workCategory=X&itemKind=EPIC",
    );
  });

  test("epic progress keeps only a domain and an epic scope — never a domain view, activity type, work category, bucket, by or item kind", () => {
    const search = "teamId=2&domainView=EPIC&domain=FLO&epicId=FLO-33&activityType=Bug&workCategory=X&bucket=MONTH&by=STATUS&itemKind=EPIC";
    expect(reportHref("/reports/epic-progress", search)).toBe("/reports/epic-progress?teamId=2&domain=FLO&epicId=FLO-33");
    // The epic is this report's own scope: no other report carries it.
    expect(reportHref("/reports/wip", search)).not.toContain("epicId");
    expect(reportHref("/reports/velocity", search)).toBe("/reports/velocity?teamId=2");
  });

  test("data quality keeps a domain and its view — never an activity type, work category, bucket, by, item kind or epic", () => {
    const search = "teamId=2&domainView=EPIC&domain=FLO&epicId=FLO-33&activityType=Bug&workCategory=X&bucket=MONTH&by=STATUS&itemKind=EPIC";
    expect(reportHref("/reports/data-quality", search)).toBe("/reports/data-quality?teamId=2&domainView=EPIC&domain=FLO");
  });

  test("the cost matrix keeps the domain, its view, the activity type and the work category — never a bucket, by, item kind or epic", () => {
    const search = "teamId=2&domainView=TASK&domain=FLO&epicId=FLO-33&activityType=Bug&workCategory=X&bucket=MONTH&by=STATUS&itemKind=EPIC";
    expect(reportHref("/reports/cost-matrix", search)).toBe(
      "/reports/cost-matrix?teamId=2&domainView=TASK&domain=FLO&activityType=Bug&workCategory=X",
    );
  });

  test("an empty query, or one that only held dropped params, is the bare path; foreign params survive", () => {
    expect(reportHref("/reports/velocity", "")).toBe("/reports/velocity");
    expect(reportHref("/reports/velocity", "?bucket=WEEK")).toBe("/reports/velocity");
    expect(reportHref("/reports/velocity", "utm=x&bucket=WEEK")).toBe("/reports/velocity?utm=x");
  });
});

describe("deepDivePath", () => {
  test("is the bare route for no selection and carries the canonical query for one", () => {
    expect(deepDiveBasePath).toBe("/reports/deep-dive");
    expect(deepDivePath()).toBe("/reports/deep-dive");
    expect(deepDivePath({})).toBe("/reports/deep-dive");
    expect(deepDivePath({ sprintIds: [5, 4], domain: "FLO" })).toBe("/reports/deep-dive?domain=FLO&sprintId=4&sprintId=5");
    expect(deepDivePath({ epicIds: ["FLO-10", "FLO-2"], connectionId: 1 })).toBe("/reports/deep-dive?epicId=FLO-2&epicId=FLO-10&connectionId=1");
  });

  test("an invalid selection is dropped, never written into the link", () => {
    expect(deepDivePath({ sprintIds: [4] })).toBe("/reports/deep-dive");
  });
});
