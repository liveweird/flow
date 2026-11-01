import { describe, expect, test } from "vitest";
import type { ReportDrillItem } from "../api/reportDrill";
import i18n from "../i18n";
import { drillItemColumns, parseDrill, serializeDrill, withSegment, type DrillTarget } from "./reportDrill";

describe("the drill param codec", () => {
  test.each<[string, DrillTarget, string]>([
    ["a bucket", { kind: "bucket", bucketStart: "2026-03-02" }, "bucket:2026-03-02"],
    [
      "one person's segment of a bucket (Jira ids carry colons)",
      { kind: "bucket", bucketStart: "2026-03-02", segmentAccountId: "712020:abc-def" },
      "bucket:2026-03-02;s=712020:abc-def",
    ],
    ["a sprint's bucket", { kind: "sprint", teamId: 4, sprintId: 123, bucket: "COMMITTED" }, "sprint:4:123:COMMITTED"],
    ["the actual cost of a throughput week", { kind: "ac-bucket", bucketStart: "2026-03-02" }, "ac-bucket:2026-03-02"],
    [
      "one person's logged time in a week (Jira ids carry colons)",
      { kind: "ac-bucket", bucketStart: "2026-03-02", segmentAccountId: "712020:abc-def" },
      "ac-bucket:2026-03-02;s=712020:abc-def",
    ],
    ["the actual cost of a sprint", { kind: "ac-sprint", teamId: 4, sprintId: 123 }, "ac-sprint:4:123"],
    [
      "one person's logged time in a sprint",
      { kind: "ac-sprint", teamId: 4, sprintId: 123, segmentAccountId: "712020:abc-def" },
      "ac-sprint:4:123;s=712020:abc-def",
    ],
    ["one team's Home figure", { kind: "home", view: "TASK_PRED25", teamId: 7 }, "home:TASK_PRED25:7"],
    ["one team's delivery week on Home", { kind: "week", teamId: 7, bucketStart: "2026-07-06" }, "week:7:2026-07-06"],
    ["one point of a Person trend row", { kind: "point", row: "cycleWorkingDaysP90", end: "2026-07-06" }, "point:cycleWorkingDaysP90:2026-07-06"],
    [
      "one person's segment of a sprint's bucket",
      { kind: "sprint", teamId: 4, sprintId: 123, bucket: "CARRIED_OVER", segmentAccountId: "712020:abc-def" },
      "sprint:4:123:CARRIED_OVER;s=712020:abc-def",
    ],
    [
      "a sprint bar narrowed to one epic segment",
      { kind: "sprint", teamId: 4, sprintId: 123, bucket: "COMMITTED", epicSegment: "101" },
      "sprint:4:123:COMMITTED:e=101",
    ],
    [
      "a sprint bar of the work without an epic",
      { kind: "sprint", teamId: 4, sprintId: 123, bucket: "FINAL", epicSegment: "NO_EPIC" },
      "sprint:4:123:FINAL:e=NO_EPIC",
    ],
    [
      "the folded 'Other epics' segment names the chart's top epics, and keeps a person's segment after them",
      { kind: "sprint", teamId: 4, sprintId: 123, bucket: "DELIVERED", epicSegment: "OTHER_EPICS", topEpicIds: [101, 102], segmentAccountId: "712020:abc-def" },
      "sprint:4:123:DELIVERED:e=OTHER_EPICS,101,102;s=712020:abc-def",
    ],
    ["a histogram bar", { kind: "hist", view: "WORKING_DAYS", bucketIndex: 3 }, "hist:WORKING_DAYS:3"],
    [
      "one person's segment of a histogram bar",
      { kind: "hist", view: "FLOW_EFFICIENCY", bucketIndex: 0, segmentAccountId: "712020:abc-def" },
      "hist:FLOW_EFFICIENCY:0;s=712020:abc-def",
    ],
    ["a cost slice", { kind: "cost", by: "ACTIVITY_TYPE", keys: ["Development"] }, "cost:ACTIVITY_TYPE:Development"],
    [
      "the folded 'Other' slice: keys with a space, a comma, a colon, a semicolon and a percent sign stay one key each",
      { kind: "cost", by: "WORK_CATEGORY", keys: ["Run ops", "A,B", "C:D", "E;s=F", "100%"] },
      "cost:WORK_CATEGORY:Run%20ops,A%2CB,C%3AD,E%3Bs%3DF,100%25",
    ],
    ["a Costs sprint's whole bar", { kind: "cost-sprint", teamId: 1, connectionId: 3, sprintId: 12 }, "cost-sprint:1:3:12"],
    ["a Costs sprint's epic segment", { kind: "cost-sprint", teamId: 1, connectionId: 3, sprintId: 12, epicSegment: "102" }, "cost-sprint:1:3:12:e=102"],
    [
      "a Costs sprint's 'Other epics' segment with the chart's top epics",
      { kind: "cost-sprint", teamId: 1, connectionId: 3, sprintId: 12, epicSegment: "OTHER_EPICS", topEpicIds: [101, 102] },
      "cost-sprint:1:3:12:e=OTHER_EPICS,101,102",
    ],
    [
      "a Compare people cell (always one person's)",
      { kind: "cell", view: "WIP_AT_END", segmentAccountId: "712020:abc-def" },
      "cell:WIP_AT_END;s=712020:abc-def",
    ],
    ["a Code activity tile", { kind: "code", view: "MRS_MERGED" }, "code:MRS_MERGED"],
    ["a Code activity member's cell", { kind: "code", view: "COMMITS", segmentAccountId: "712020:abc-def" }, "code:COMMITS;s=712020:abc-def"],
    ["a Code activity weekly bar", { kind: "code", view: "WEEK", bucketStart: "2026-03-02" }, "code:WEEK:2026-03-02"],
    ["a Tasks and code count", { kind: "taskcode", view: "TASKS_WITH_MR" }, "taskcode:TASKS_WITH_MR"],
    ["a Tasks and code histogram bar", { kind: "hist", view: "CODE_LEAD_TIME", bucketIndex: 2 }, "hist:CODE_LEAD_TIME:2"],
    [
      "a Compare people work-mix share (the key is percent-encoded)",
      { kind: "mix", by: "activityType", key: "Run: ops, 100%", segmentAccountId: "acc-ann" },
      "mix:activityType:Run%3A%20ops%2C%20100%25;s=acc-ann",
    ],
  ])("%s round-trips", (_name, target, spelled) => {
    expect(serializeDrill(target)).toBe(spelled);
    expect(parseDrill(spelled)).toEqual(target);
  });

  test.each([
    ["absent", null],
    ["empty", ""],
    ["an unknown kind", "pie:123:COMMITTED"],
    ["a sprint without a bucket", "sprint:4:123"],
    ["a sprint without its team (the pre-team spelling)", "sprint:123:COMMITTED"],
    ["a sprint with an unknown bucket", "sprint:4:123:ALL"],
    ["a sprint bucket in the wrong case", "sprint:4:123:committed"],
    ["a sprint id that is no whole number", "sprint:4:12.5:COMMITTED"],
    ["a sprint id of zero", "sprint:4:0:COMMITTED"],
    ["a sprint id in exponent form", "sprint:4:1e3:COMMITTED"],
    ["a team id that is no whole number", "sprint:x:123:COMMITTED"],
    ["a team id of zero", "sprint:0:123:COMMITTED"],
    ["a sprint with a stray part", "sprint:4:123:COMMITTED:extra"],
    ["a by-epic sprint with a stray part after the epic", "sprint:4:123:COMMITTED:e=101:extra"],
    ["a by-epic sprint whose epic is no epic id", "sprint:4:123:COMMITTED:e=FLO-10"],
    ["a by-epic sprint with an epic id of zero", "sprint:4:123:COMMITTED:e=0"],
    ["a by-epic sprint with a lowercase fixed key", "sprint:4:123:COMMITTED:e=no_epic"],
    ["'Other epics' without the chart's top epics", "sprint:4:123:COMMITTED:e=OTHER_EPICS"],
    ["'Other epics' with more top epics than a chart has", "sprint:4:123:COMMITTED:e=OTHER_EPICS,1,2,3,4,5,6,7"],
    ["top epics on a single epic", "sprint:4:123:COMMITTED:e=101,102"],
    ["an empty epic part", "sprint:4:123:COMMITTED:e="],
    ["an epic part on a bucket target", "bucket:2026-03-02:e=101"],
    ["an actual-cost week that is no date", "ac-bucket:2026-02-30"],
    ["an actual-cost week with a stray part", "ac-bucket:2026-03-02:extra"],
    ["an actual-cost sprint without a sprint", "ac-sprint:4"],
    ["an actual-cost sprint with a team id of zero", "ac-sprint:0:123"],
    ["an actual-cost sprint with a bucket (it has none)", "ac-sprint:4:123:DELIVERED"],
    ["an actual-cost sprint with an epic part", "ac-sprint:4:123:e=101"],
    ["an epic part on a histogram bar", "hist:WORKING_DAYS:1:e=101"],
    ["no identity", "bucket:"],
    ["a date that is no date", "bucket:2026-02-30"],
    ["a date in the wrong shape", "bucket:03/02/2026"],
    ["a stray part", "bucket:2026-03-02:extra"],
    ["an empty segment", "bucket:2026-03-02;s="],
    ["a histogram bar without an index", "hist:WORKING_DAYS"],
    ["a Costs sprint without a sprint", "cost-sprint:1:3"],
    ["a Costs sprint without a connection (the two-part spelling)", "cost-sprint:1:12"],
    ["a Costs sprint with a team id of zero", "cost-sprint:0:3:12"],
    ["a Costs sprint with a connection id of zero", "cost-sprint:1:0:12"],
    ["a Costs sprint with a bucket (it has none)", "cost-sprint:1:3:12:DELIVERED"],
    ["a Costs sprint with a malformed epic part", "cost-sprint:1:3:12:e=abc"],
    ["a Costs sprint's 'Other epics' without its top epics", "cost-sprint:1:3:12:e=OTHER_EPICS"],
    ["a Costs sprint with a stray part", "cost-sprint:1:3:12:e=101:extra"],
    ["a Costs sprint narrowed to a person (its bars are never one person's)", "cost-sprint:1:3:12;s=acc-ann"],
    ["a cost slice without keys", "cost:ACTIVITY_TYPE"],
    ["a cost slice with an empty key list", "cost:ACTIVITY_TYPE:"],
    ["a cost slice with an empty key between commas", "cost:ACTIVITY_TYPE:A,,B"],
    ["a cost slice of an unknown breakdown", "cost:DOMAIN:A"],
    ["a cost slice with a malformed percent escape", "cost:WORK_CATEGORY:100%"],
    ["a cost slice with an unencoded colon", "cost:WORK_CATEGORY:A:B"],
    ["a cost slice with more keys than the server takes", `cost:ACTIVITY_TYPE:${Array.from({ length: 101 }, (_, i) => `k${i}`).join(",")}`],
    ["the retired epic-table target of the old cost matrix", "epic:-:10"],
    ["a histogram bar with a negative index", "hist:WORKING_DAYS:-1"],
    ["a histogram bar with a fractional index", "hist:WORKING_DAYS:1.5"],
    ["a histogram bar with a stray part", "hist:WORKING_DAYS:1:2"],
    ["a histogram view in the wrong case", "hist:working_days:1"],
    ["a histogram view with no name", "hist::1"],
    ["a Home figure without a team", "home:TASK_CYCLE"],
    ["a Home figure of a view the server does not answer", "home:TASK_NOPE:1"],
    ["a Home figure of team 0 (only real teams)", "home:TASK_CYCLE:0"],
    ["a Home figure with a stray part", "home:TASK_CYCLE:1:2"],
    ["a Home figure with a person (its lists are never one)", "home:TASK_CYCLE:1;s=acc-ann"],
    ["a Home figure with a view in the wrong case", "home:task_cycle:1"],
    ["a delivery week without a date", "week:1"],
    ["a delivery week that is no date", "week:1:2026-02-30"],
    ["a delivery week without a real team", "week:x:2026-07-06"],
    ["a delivery week with a person", "week:1:2026-07-06;s=acc-ann"],
    ["a delivery week with a stray part", "week:1:2026-07-06:extra"],
    ["a trend point without a day", "point:deliveredItems"],
    ["a trend point that is no date", "point:deliveredItems:2026-02-30"],
    ["a trend point of a row key in the wrong case", "point:DeliveredItems:2026-07-06"],
    ["a trend point with a person (the filter names the person)", "point:deliveredItems:2026-07-06;s=acc-ann"],
    ["a trend point with a stray part", "point:deliveredItems:2026-07-06:extra"],
    ["a Compare people cell with no person", "cell:DELIVERED"],
    ["a Compare people cell with a stray part", "cell:DELIVERED:2;s=acc-ann"],
    ["a Compare people cell view in the wrong case", "cell:delivered;s=acc-ann"],
    ["a work-mix share with no person", "mix:domain:FLO"],
    ["a work-mix share of an unknown slice", "mix:team:Alpha;s=acc-ann"],
    ["a work-mix share with no key", "mix:domain;s=acc-ann"],
    ["a work-mix share with an unencoded colon", "mix:domain:A:B;s=acc-ann"],
    ["a work-mix share with a malformed percent escape", "mix:domain:100%;s=acc-ann"],
    ["a Code activity figure of a view the server does not answer", "code:NOPE"],
    ["a Code activity figure in the wrong case", "code:commits"],
    ["a Code activity histogram view (a bar is a hist target)", "code:MR_SIZE_LINES"],
    ["a Code activity week without a date", "code:WEEK"],
    ["a Code activity week that is no date", "code:WEEK:2026-02-30"],
    ["a Code activity figure with a date (only the week has one)", "code:COMMITS:2026-03-02"],
    ["a Code activity figure with a stray part", "code:WEEK:2026-03-02:extra"],
    ["a Tasks and code figure of a view the server does not answer as a count", "taskcode:NOPE"],
    ["a Tasks and code histogram view (a bar is a hist target)", "taskcode:CODE_LEAD_TIME"],
    ["a Tasks and code figure in the wrong case", "taskcode:tasks_with_mr"],
    ["a Tasks and code figure with a stray part", "taskcode:TASKS_WITH_MR:extra"],
    ["a Tasks and code figure narrowed to a person (the report has none)", "taskcode:MERGED_AFTER_DONE;s=acc-ann"],
  ])("%s parses to nothing (a hand-edited link is ignored, never an error)", (_name, raw) => {
    expect(parseDrill(raw)).toBeNull();
  });

  test("withSegment sets and clears a histogram bar's segment too", () => {
    const whole: DrillTarget = { kind: "hist", view: "RATIO", bucketIndex: 2 };
    const person = withSegment(whole, "acc-ann");
    expect(person).toEqual({ kind: "hist", view: "RATIO", bucketIndex: 2, segmentAccountId: "acc-ann" });
    expect(withSegment(person, undefined)).toEqual(whole);
    expect("segmentAccountId" in withSegment(person, undefined)).toBe(false);
  });

  test("withSegment sets and clears the segment without touching the bucket", () => {
    const whole: DrillTarget = { kind: "bucket", bucketStart: "2026-03-02" };
    const person = withSegment(whole, "acc-bob");
    expect(person).toEqual({ kind: "bucket", bucketStart: "2026-03-02", segmentAccountId: "acc-bob" });
    expect(withSegment(person, undefined)).toEqual(whole);
    expect("segmentAccountId" in withSegment(person, undefined)).toBe(false);
  });

  test("withSegment works on a sprint target too", () => {
    const whole: DrillTarget = { kind: "sprint", teamId: 2, sprintId: 7, bucket: "FINAL" };
    const person = withSegment(whole, "acc-bob");
    expect(person).toEqual({ kind: "sprint", teamId: 2, sprintId: 7, bucket: "FINAL", segmentAccountId: "acc-bob" });
    expect(withSegment(person, undefined)).toEqual(whole);
  });
});

describe("drillItemColumns", () => {
  const t = i18n.t.bind(i18n);
  const item: ReportDrillItem = {
    issueKey: "FLO-1",
    summary: null,
    itemKind: "TASK",
    accountId: null,
    assignee: null,
    teamId: null,
    anchorAt: Date.UTC(2026, 2, 2, 23, 30),
    figure: null,
  };

  test("headers are the issue, summary, assignee, the done date and the MD figure", () => {
    expect(drillItemColumns(t, "md", "done", "UTC").map((c) => c.header)).toEqual(["Issue", "Summary", "Assignee", "Done", "Delivered (MD)"]);
  });

  test("a sprint drill's estimate and completion columns", () => {
    const columns = drillItemColumns(t, "estimateMd", "completed", "UTC");
    expect(columns.map((c) => c.header)).toEqual(["Issue", "Summary", "Assignee", "Sprint completed", "Estimate (MD)"]);
    expect(columns.map((c) => c.render({ ...item, figure: 3 }))).toEqual(["FLO-1", "—", "Unassigned", "2026-03-02", "3"]);
    expect(columns[3].render({ ...item, anchorAt: null })).toBe("—");
  });

  test("Compare people's lists: a started or logged-on date, or none at all, and the held-days, changes and age figures", () => {
    const headers = (unit: Parameters<typeof drillItemColumns>[1], anchor: Parameters<typeof drillItemColumns>[2]) =>
      drillItemColumns(t, unit, anchor, "UTC").map((c) => c.header);
    expect(headers("changes", "started")).toEqual(["Issue", "Summary", "Assignee", "Started", "Estimate changes"]);
    expect(headers("loggedMd", "worklog")).toEqual(["Issue", "Summary", "Authors", "Logged on", "Logged (MD)"]);
    // No date column when a list has no instant (the tasks held in progress): the days held are the figure.
    expect(headers("heldDays", "none")).toEqual(["Issue", "Summary", "Assignee", "Days in progress"]);
    expect(headers("ageWd", "started").at(-1)).toBe("Age (working days)");
    // Home's lists: the logged cost keeps the assignee (it is not a worklog list), the epic gap is signed.
    expect(headers("actualMd", "done")).toEqual(["Issue", "Summary", "Assignee", "Done", "Logged (MD)"]);
    expect(headers("gapMd", "done").at(-1)).toBe("Gap (MD)");
    expect(drillItemColumns(t, "gapMd", "done", "UTC").at(-1)?.render({ ...item, figure: -4 })).toBe("−4");
    const row = { ...item, figure: 4.25 };
    expect(drillItemColumns(t, "changes", "started", "UTC").at(-1)?.render(row)).toBe("4.25");
    expect(drillItemColumns(t, "heldDays", "none", "UTC").at(-1)?.render(row)).toBe("4.25");
    expect(drillItemColumns(t, "ageWd", "started", "UTC").at(-1)?.render(row)).toBe("4.3");
  });

  test("missing values print as a dash or 'Unassigned', the date is read in the configured zone, MD has no trailing zeros", () => {
    const cells = (row: ReportDrillItem, zone = "UTC") => drillItemColumns(t, "md", "done", zone).map((c) => c.render(row));
    expect(cells(item)).toEqual(["FLO-1", "—", "Unassigned", "2026-03-02", "—"]);
    // 23:30 UTC is already the next day in Warsaw.
    expect(cells(item, "Europe/Warsaw")[3]).toBe("2026-03-03");
    expect(cells({ ...item, summary: "Ship it", assignee: "Ann", figure: 2.5 })).toEqual(["FLO-1", "Ship it", "Ann", "2026-03-02", "2.5"]);
    expect(cells({ ...item, anchorAt: null })[3]).toBe("—");
  });
});

describe("drillItemColumns — Code activity's lists", () => {
  const t = i18n.t.bind(i18n);
  const merge: ReportDrillItem = {
    issueKey: "grp/app!12",
    summary: "Add the export",
    itemKind: "MERGE_REQUEST",
    accountId: "acc-ann",
    assignee: "Ann Author",
    teamId: 1,
    anchorAt: Date.UTC(2026, 2, 2, 10),
    figure: 250,
  };

  test("a commit or merge request names its Person, and the lines, files and merged date have their own headers", () => {
    const columns = drillItemColumns(t, "lines", "merged", "UTC", false, undefined, true);
    expect(columns.map((c) => c.header)).toEqual(["Issue", "Summary", "Person", "Merged", "Lines changed"]);
    expect(columns.map((c) => c.render(merge))).toEqual(["grp/app!12", "Add the export", "Ann Author", "2026-03-02", "250"]);
    expect(columns[2].render({ ...merge, assignee: null })).toBe("—");
    expect(drillItemColumns(t, "files", "committed", "UTC", false, undefined, true).map((c) => c.header).slice(3)).toEqual(["Committed", "Files changed"]);
    expect(drillItemColumns(t, "ageWd", "created", "UTC", false, undefined, true).map((c) => c.header).slice(3)).toEqual(["Opened", "Age (working days)"]);
  });

  test("a list of review acts has no figure column, only the date of the act", () => {
    const columns = drillItemColumns(t, "none", "event", "UTC", false, undefined, true);
    expect(columns.map((c) => c.header)).toEqual(["Issue", "Summary", "Person", "Done on"]);
  });

  test("the kind column names a commit and a merge request", () => {
    const kind = drillItemColumns(t, "lines", "committed", "UTC", true, undefined, true)[2];
    expect(kind.render({ ...merge, itemKind: "COMMIT" })).toBe("Commit");
    expect(kind.render(merge)).toBe("Merge request");
  });
});

describe("drillItemColumns — a distribution's units", () => {
  const t = i18n.t.bind(i18n);
  const item: ReportDrillItem = {
    issueKey: "FLO-1",
    summary: "Ship it",
    itemKind: "TASK",
    accountId: "acc-ann",
    assignee: "Ann",
    teamId: 1,
    anchorAt: Date.UTC(2026, 2, 2, 10),
    figure: 0.456,
  };

  test.each([
    ["wd", "Working days", "0.5"],
    ["days", "Elapsed days", "0.5"],
    ["ratio", "Reported ÷ cycle", "0.46"],
    ["share", "Flow efficiency", "45.6%"],
    ["accuracy", "Actual ÷ estimate", "0.46"],
    ["blockedWd", "Blocked working days", "0.5"],
    ["blockedShare", "Blocked share of cycle", "45.6%"],
    ["change", "Change in estimate", "+45.6%"],
  ] as const)("%s reads its figure with the report's own format under its own header", (unit, header, figure) => {
    const columns = drillItemColumns(t, unit, "done", "UTC");
    expect(columns[4].header).toBe(header);
    expect(columns[4].render(item)).toBe(figure);
    expect(columns[4].render({ ...item, figure: null })).toBe("—");
  });

  test("an epic has no assignee (a dash, not 'Unassigned'), and the kind column only appears when asked for", () => {
    const epic: ReportDrillItem = { ...item, itemKind: "EPIC", accountId: null, assignee: null };
    const plain = drillItemColumns(t, "blockedWd", "done", "UTC");
    expect(plain.map((c) => c.render(epic))[2]).toBe("—");
    expect(plain.map((c) => c.render({ ...item, assignee: null }))[2]).toBe("Unassigned");
    const withKind = drillItemColumns(t, "blockedWd", "done", "UTC", true);
    expect(withKind.map((c) => c.header)).toEqual(["Issue", "Summary", "Kind", "Assignee", "Done", "Blocked working days"]);
    expect(withKind.map((c) => c.render(epic))).toEqual(["FLO-1", "Ship it", "Epic", "—", "2026-03-02", "0.5"]);
    expect(withKind[2].render(item)).toBe("Task");
  });

  test("the cost list names its AUTHORS (several joined by the server, or 'No known author'), the last logged day and the logged man-days", () => {
    const logged: ReportDrillItem = { ...item, assignee: "Ann, Bob", accountId: null, figure: 1.75 };
    const columns = drillItemColumns(t, "loggedMd", "logged", "UTC", true);
    expect(columns.map((c) => c.header)).toEqual(["Issue", "Summary", "Kind", "Authors", "Last logged", "Logged (MD)"]);
    expect(columns.map((c) => c.render(logged))).toEqual(["FLO-1", "Ship it", "Task", "Ann, Bob", "2026-03-02", "1.75"]);
    expect(columns[3].render({ ...logged, assignee: null })).toBe("No known author");
    // An epic logged on directly is listed as an epic, and its authors are named like a task's.
    expect(columns[2].render({ ...logged, itemKind: "EPIC" })).toBe("Epic");
  });
});
