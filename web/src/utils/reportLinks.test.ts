import { describe, expect, test } from "vitest";
import { deepDiveBasePath, reportHref } from "./reportLinks";

describe("reportHref", () => {
  test("keeps the shared params (period, team, member, connection) on every report", () => {
    const search = "lastSprints=3&teamId=2&accountId=a&connectionId=1";
    for (const path of ["/reports/commitments", "/reports/throughput", "/reports/cycle-time"]) {
      expect(reportHref(path, search)).toBe(`${path}?${search}`);
    }
  });

  test("sprint consistency, epic accuracy, epic progress and the estimated backlog are read for the whole team: the member is dropped, the rest kept", () => {
    const search = "lastSprints=3&teamId=2&accountId=a&connectionId=1";
    const wholeTeam = ["/reports/sprint-consistency", "/reports/epic-estimation-accuracy", "/reports/epic-progress", "/reports/backlog"];
    for (const path of wholeTeam) {
      expect(reportHref(path, search)).toBe(`${path}?lastSprints=3&teamId=2&connectionId=1`);
    }
    for (const path of wholeTeam) {
      expect(reportHref(path, "teamId=2&accountId=a&accountId=b"), path).toBe(`${path}?teamId=2`);
    }
  });

  test("drops the report-specific params the target has no control for", () => {
    const search = "teamId=2&domainView=EPIC&domain=FLO&activityType=Bug&workCategory=X&bucket=MONTH";
    expect(reportHref("/reports/commitments", search)).toBe("/reports/commitments?teamId=2");
    // Sprint consistency slices by activity type and work category only.
    expect(reportHref("/reports/sprint-consistency", search)).toBe("/reports/sprint-consistency?teamId=2&activityType=Bug&workCategory=X");
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
    expect(reportHref("/reports/commitments", search)).toBe("/reports/commitments?teamId=2");
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
    expect(reportHref("/reports/commitments", search)).toBe("/reports/commitments?teamId=2");
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

  test("the cost matrix keeps its split; every other report drops it", () => {
    const search = "teamId=2&domainView=TASK&split=SPRINT";
    expect(reportHref("/reports/cost-matrix", search)).toBe("/reports/cost-matrix?teamId=2&domainView=TASK&split=SPRINT");
    expect(reportHref("/reports/throughput", search)).toBe("/reports/throughput?teamId=2&domainView=TASK");
    expect(reportHref("/reports/commitments", search)).toBe("/reports/commitments?teamId=2");
  });

  test("an empty query, or one that only held dropped params, is the bare path; foreign params survive", () => {
    expect(reportHref("/reports/commitments", "")).toBe("/reports/commitments");
    expect(reportHref("/reports/commitments", "?bucket=WEEK")).toBe("/reports/commitments");
    expect(reportHref("/reports/commitments", "utm=x&bucket=WEEK")).toBe("/reports/commitments?utm=x");
  });
});

describe("reportHref — the long tail", () => {
  const search = "teamId=2&bucket=MONTH&tailPercent=10";

  test("goes only to the seven reports with a histogram, and is dropped everywhere else", () => {
    for (const path of [
      "/reports/cycle-time",
      "/reports/task-estimation-accuracy",
      "/reports/epic-estimation-accuracy",
      "/reports/estimate-adjustments",
      "/reports/reported-time-ratio",
      "/reports/blocked-time",
      "/reports/data-quality",
    ]) {
      expect(reportHref(path, search), path).toContain("tailPercent=10");
    }
    for (const path of [
      "/reports/commitments",
      "/reports/throughput",
      "/reports/sprint-consistency",
      "/reports/wip",
      "/reports/backlog",
      "/reports/aging-wip",
      "/reports/epic-progress",
      "/reports/cost-matrix",
    ]) {
      expect(reportHref(path, search), path).not.toContain("tailPercent");
    }
  });
});

describe("reportHref — Compare people", () => {
  const search = "accountId=a&accountId=b&from=2026-01-01&to=2026-02-01&domainView=EPIC&domain=FLO&activityType=Bug&workCategory=X&tailPercent=10&bucket=MONTH&by=STATUS&split=WEEK";

  test("keeps both people, the dates, the slices and the long tail — and nothing of another report's", () => {
    expect(reportHref("/reports/compare-people", search)).toBe(
      "/reports/compare-people?accountId=a&accountId=b&from=2026-01-01&to=2026-02-01&domainView=EPIC&domain=FLO&activityType=Bug&workCategory=X&tailPercent=10",
    );
  });

  test("moving between the multi-person reports keeps the picks, leaving for a one-person report keeps the first", () => {
    expect(reportHref("/reports/throughput", "teamId=2&accountId=a&accountId=b")).toBe("/reports/throughput?teamId=2&accountId=a&accountId=b");
    expect(reportHref("/reports/aging-wip", search)).toContain("accountId=a&");
    expect(reportHref("/reports/aging-wip", search)).not.toContain("accountId=b");
  });
});

describe("reportHref — the backlog's staleness window", () => {
  const search = "teamId=2&touchedWithinDays=30&tailPercent=10";

  test("follows a link to the estimated backlog only", () => {
    expect(reportHref("/reports/backlog", search)).toBe("/reports/backlog?teamId=2&touchedWithinDays=30");
    for (const path of ["/reports/wip", "/reports/aging-wip", "/reports/epic-progress", "/reports/commitments", "/reports/cycle-time"]) {
      expect(reportHref(path, search), path).not.toContain("touchedWithinDays");
    }
  });
});

describe("reportHref — several members", () => {
  const search = "teamId=2&accountId=a&accountId=b&accountId=c&lastSprints=3";

  test("commitments, throughput, cycle time, reported time, adjustments, task accuracy and WIP keep every picked member, so moving between them keeps all the picks", () => {
    expect(reportHref("/reports/commitments", search)).toBe(`/reports/commitments?${search}`);
    expect(reportHref("/reports/throughput", search)).toBe(`/reports/throughput?${search}`);
    expect(reportHref("/reports/cycle-time", search)).toBe(`/reports/cycle-time?${search}`);
    expect(reportHref("/reports/task-estimation-accuracy", search)).toBe(`/reports/task-estimation-accuracy?${search}`);
    expect(reportHref("/reports/reported-time-ratio", search)).toBe(`/reports/reported-time-ratio?${search}`);
    expect(reportHref("/reports/estimate-adjustments", search)).toBe(`/reports/estimate-adjustments?${search}`);
    expect(reportHref("/reports/wip", search)).toBe(`/reports/wip?${search}`);
  });

  test("any other report keeps only the FIRST member, in place, so the address bar matches what that page shows", () => {
    expect(reportHref("/reports/aging-wip", search)).toBe("/reports/aging-wip?teamId=2&accountId=a&lastSprints=3");
  });

  test("a single member is untouched everywhere", () => {
    expect(reportHref("/reports/throughput", "teamId=2&accountId=a")).toBe("/reports/throughput?teamId=2&accountId=a");
  });
});

describe("reportHref — the throughput chart view", () => {
  const search = "teamId=2&view=cumulative&bucket=MONTH";

  test("follows a link only to throughput, which reads it; every other report drops it", () => {
    expect(reportHref("/reports/throughput", search)).toBe(`/reports/throughput?${search}`);
    for (const path of ["/reports/commitments", "/reports/cycle-time", "/reports/sprint-consistency", "/reports/wip"]) {
      expect(reportHref(path, search)).not.toContain("view=");
    }
  });
});

describe("reportHref — the aging WIP kind switch", () => {
  const search = "teamId=2&kind=epics&domain=FLO";

  test("follows a link only to aging WIP, which reads it; every other report drops it", () => {
    expect(reportHref("/reports/aging-wip", search)).toBe(`/reports/aging-wip?${search}`);
    for (const path of ["/reports/wip", "/reports/blocked-time", "/reports/throughput", "/reports/epic-progress"]) {
      expect(reportHref(path, search), path).not.toContain("kind=");
    }
  });

  test("hopping to aging WIP drops the throughput view, and leaving throughput for it keeps nothing of it", () => {
    expect(reportHref("/reports/aging-wip", "teamId=2&view=cumulative")).toBe("/reports/aging-wip?teamId=2");
  });
});

describe("reportHref — an open drill", () => {
  test("never follows a tab link, not even to the same report: a bar means nothing anywhere else", () => {
    const search = "teamId=2&drill=bucket%3A2026-09-07%3Bs%3Dacc-bob&view=cumulative";
    expect(reportHref("/reports/throughput", search)).toBe("/reports/throughput?teamId=2&view=cumulative");
    for (const path of ["/reports/commitments", "/reports/cycle-time", "/reports/wip"]) {
      expect(reportHref(path, search)).toBe(`${path}?teamId=2`);
    }
    expect(reportHref("/reports/throughput", "drill=bucket%3A2026-09-07")).toBe("/reports/throughput");
  });
});

describe("deepDiveBasePath", () => {
  test("is the Deep dive's own route", () => {
    expect(deepDiveBasePath).toBe("/reports/deep-dive");
  });
});
