import { describe, expect, test } from "vitest";
import { epochMillisToIsoDateInZone } from "./isoDate";
import {
  activePeriodChoice,
  applyReportFilter,
  snapBacklogStaleness,
  snapToStalenessStep,
  dropDomainWithTeam,
  dropMember,
  domainStandsForTeam,
  dropReportSpecific,
  hasReportFilterParams,
  normalizeWipFilter,
  parseReportFilter,
  presetRange,
  rebaseReportFilter,
  reportQuery,
  requiredTeamId,
  serializeReportFilter,
  wipColumnAvailable,
  withFilterKey,
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
      epicId: "FLO-33",
      activityType: "Bug",
      workCategory: "Maintenance",
      bucket: "MONTH",
      by: "COLUMN",
      itemKind: "BOTH",
      connectionId: 2,
    };
    expect(parseReportFilter(serializeReportFilter(filter))).toEqual(filter);
  });

  test("the long tail is an integer 0..25 and the default 5 is never stored: out of range or malformed is dropped", () => {
    expect(parse("tailPercent=10")).toEqual({ tailPercent: 10 });
    expect(parse("tailPercent=0")).toEqual({ tailPercent: 0 });
    expect(parse("tailPercent=25")).toEqual({ tailPercent: 25 });
    expect(parse("tailPercent=5")).toEqual({});
    for (const bad of ["26", "-1", "2.5", "abc", ""]) expect(parse(`tailPercent=${bad}`), bad).toEqual({});
    // Canonical: last in the key order, and absent for the default — so the default is never in the request or the query key.
    expect(reportQuery({ tailPercent: 0, teamId: 2 })).toBe("teamId=2&tailPercent=0");
    expect(reportQuery({})).toBe("");
    expect(parseReportFilter(serializeReportFilter({ tailPercent: 15 }))).toEqual({ tailPercent: 15 });
  });

  test("the backlog's staleness window is an integer 0..3650 (0 = no limit, kept) serialized last; out of range or malformed is dropped", () => {
    expect(parse("touchedWithinDays=30")).toEqual({ touchedWithinDays: 30 });
    expect(parse("touchedWithinDays=0")).toEqual({ touchedWithinDays: 0 });
    expect(parse("touchedWithinDays=3650")).toEqual({ touchedWithinDays: 3650 });
    for (const bad of ["3651", "-1", "1.5", "abc", ""]) expect(parse(`touchedWithinDays=${bad}`), bad).toEqual({});
    expect(reportQuery({ touchedWithinDays: 0, teamId: 2, tailPercent: 10 })).toBe("teamId=2&tailPercent=10&touchedWithinDays=0");
    expect(parseReportFilter(serializeReportFilter({ touchedWithinDays: 180, teamId: 2 }))).toEqual({ touchedWithinDays: 180, teamId: 2 });
  });

  test("an epic key is text: kept as written, a blank one dropped, and serialized right after the domain", () => {
    expect(parse("epicId=FLO-33")).toEqual({ epicId: "FLO-33" });
    expect(parse("epicId=%20%20")).toEqual({});
    expect(reportQuery({ activityType: "Bug", epicId: "FLO-33", domain: "FLO" })).toBe("domain=FLO&epicId=FLO-33&activityType=Bug");
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
    expect(parse("domainView=SIDEWAYS&bucket=DAY&connectionId=0&by=ROW&itemKind=STORY")).toEqual({});
    expect(parse("domain=&activityType=%20")).toEqual({});
  });

  test("the WIP report's keying and item kind are ordinary managed params, serialized before the connection", () => {
    expect(parse("itemKind=EPIC&by=STATUS&connectionId=2")).toEqual({ by: "STATUS", itemKind: "EPIC", connectionId: 2 });
    expect(reportQuery({ itemKind: "TASK", by: "STAGE", teamId: 1 })).toBe("teamId=1&by=STAGE&itemKind=TASK");
    expect(hasReportFilterParams(new URLSearchParams("by=STAGE"))).toBe(true);
  });

  test("board columns are available for one team only: not the unit, unassigned, or a domain slice", () => {
    expect(wipColumnAvailable({ teamId: 1 })).toBe(true);
    expect(wipColumnAvailable({ teamId: 1, accountId: "a" })).toBe(true);
    expect(wipColumnAvailable({})).toBe(false);
    expect(wipColumnAvailable({ teamId: 0 })).toBe(false);
    expect(wipColumnAvailable({ teamId: 1, domain: "FLO" })).toBe(false);
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

  test("a repeated accountId reads as its first value on every page that has not opted into several members", () => {
    expect(parse("teamId=1&accountId=a&accountId=b")).toEqual({ teamId: 1, accountId: "a" });
  });

  test("a multi-member page reads repeated accountId as accountIds: distinct, in order, at most eight, still needing a team", () => {
    const multi = (query: string) => parseReportFilter(new URLSearchParams(query), { multiAccount: true });
    expect(multi("teamId=1&accountId=b&accountId=a&accountId=b&accountId=")).toEqual({ teamId: 1, accountIds: ["b", "a"] });
    expect(multi("teamId=1&accountId=a")).toEqual({ teamId: 1, accountIds: ["a"] });
    expect(multi("accountId=a&accountId=b")).toEqual({});
    const many = Array.from({ length: 10 }, (_, i) => `accountId=u${i}`).join("&");
    expect(multi(`teamId=1&${many}`).accountIds).toEqual(Array.from({ length: 8 }, (_, i) => `u${i}`));
  });

  test("a teamless page (Compare people) reads repeated accountId as accountIds WITHOUT a team; every other page still needs one", () => {
    const teamless = (query: string) => parseReportFilter(new URLSearchParams(query), { teamless: true });
    expect(teamless("accountId=b&accountId=a&accountId=b&from=2026-01-01&to=2026-02-01")).toEqual({
      accountIds: ["b", "a"],
      from: "2026-01-01",
      to: "2026-02-01",
    });
    // A team in the link is still parsed (the page's normalizer drops it and rewrites the URL).
    expect(teamless("teamId=2&accountId=a&accountId=b")).toEqual({ teamId: 2, accountIds: ["a", "b"] });
    expect(parseReportFilter(new URLSearchParams("accountId=a&accountId=b"), { multiAccount: true })).toEqual({});
    expect(parseReportFilter(new URLSearchParams("accountId=a&accountId=b"))).toEqual({});
  });

  test("accountIds serialize without a team too, in order, one key per person", () => {
    expect(reportQuery({ from: "2026-01-01", to: "2026-02-01", accountIds: ["a", "b"], domainView: "TASK" })).toBe(
      "from=2026-01-01&to=2026-02-01&accountId=a&accountId=b&domainView=TASK",
    );
  });

  test("accountIds serialize as repeated accountId keys right after the team, deduplicated and capped, and round-trip", () => {
    const filter: ReportFilterState = { lastSprints: 3, teamId: 1, accountIds: ["b", "a", "b"], bucket: "MONTH" };
    expect(reportQuery(filter)).toBe("lastSprints=3&teamId=1&accountId=b&accountId=a&bucket=MONTH");
    expect(parseReportFilter(serializeReportFilter(filter), { multiAccount: true })).toEqual({
      lastSprints: 3,
      teamId: 1,
      accountIds: ["b", "a"],
      bucket: "MONTH",
    });
    const ten = Array.from({ length: 10 }, (_, i) => `u${i}`);
    expect(serializeReportFilter({ teamId: 1, accountIds: ten }).getAll("accountId")).toHaveLength(8);
    expect(applyReportFilter(new URLSearchParams("teamId=1&accountId=x&accountId=y&utm=1"), { teamId: 1, accountId: "z" }).toString()).toBe(
      "utm=1&teamId=1&accountId=z",
    );
  });

  test("serialization uses one canonical key order", () => {
    expect(reportQuery({ accountId: "a", teamId: 1, to: "2026-02-01", from: "2026-01-01" })).toBe(
      "from=2026-01-01&to=2026-02-01&teamId=1&accountId=a",
    );
  });

  test("applyReportFilter replaces managed keys and keeps unrelated ones", () => {
    const next = applyReportFilter(new URLSearchParams("teamId=1&lastSprints=3&utm=x"), { teamId: 2, bucket: "MONTH" });
    expect(next.toString()).toBe("utm=x&teamId=2&bucket=MONTH");
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

  test("a history horizon raises a preset's from to its first day and leaves shorter presets alone", () => {
    expect(presetRange("last365", today, "2026-03-29")).toEqual({ from: "2026-03-29", to: today });
    expect(presetRange("last365", today, null)).toEqual({ from: "2025-09-30", to: today });
    expect(presetRange("last90", today, "2026-03-29")).toEqual({ from: "2026-07-02", to: today });
    expect(presetRange("lastMonth", today, "2026-03-29")).toEqual({ from: "2026-08-01", to: "2026-08-31" });
  });

  test("activePeriodChoice recognises a preset that the horizon cut", () => {
    const cut = presetRange("last365", today, "2026-03-29");
    expect(activePeriodChoice(cut, today, "2026-03-29")).toBe("last365");
    expect(activePeriodChoice(cut, today)).toBe("custom");
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

describe("dropReportSpecific", () => {
  const all: ReportFilterState = {
    teamId: 1,
    accountId: "a1",
    lastSprints: 3,
    connectionId: 2,
    domainView: "TASK",
    domain: "FLO",
    epicId: "FLO-1",
    activityType: "Bug",
    workCategory: "Run",
    bucket: "WEEK",
    by: "STATUS",
    itemKind: "EPIC",
    tailPercent: 10,
    touchedWithinDays: 30,
  };

  test("drops every report-specific param except the kept ones, and never the shared ones", () => {
    expect(dropReportSpecific(all, new Set())).toEqual({ teamId: 1, accountId: "a1", lastSprints: 3, connectionId: 2 });
    expect(dropReportSpecific(all, new Set(["tailPercent"]))).toEqual({
      teamId: 1,
      accountId: "a1",
      lastSprints: 3,
      connectionId: 2,
      tailPercent: 10,
    });
    expect(dropReportSpecific(all, new Set(["domain", "epicId"]))).toEqual({
      teamId: 1,
      accountId: "a1",
      lastSprints: 3,
      connectionId: 2,
      domain: "FLO",
      epicId: "FLO-1",
    });
  });

  test("returns a copy and leaves its input alone", () => {
    const input: ReportFilterState = { domain: "FLO", bucket: "WEEK" };
    const out = dropReportSpecific(input, new Set(["domain"]));
    expect(out).not.toBe(input);
    expect(input).toEqual({ domain: "FLO", bucket: "WEEK" });
  });
});

describe("dropDomainWithTeam and normalizeWipFilter", () => {
  test("a team and a domain together: the team wins; either alone is untouched (and the same object)", () => {
    expect(dropDomainWithTeam({ teamId: 1, domain: "FLO", lastSprints: 3 })).toEqual({ teamId: 1, lastSprints: 3 });
    expect(dropDomainWithTeam({ teamId: 0, domain: "FLO" })).toEqual({ teamId: 0 });
    const domainOnly: ReportFilterState = { domain: "FLO" };
    expect(dropDomainWithTeam(domainOnly)).toBe(domainOnly);
    const teamOnly: ReportFilterState = { teamId: 1 };
    expect(dropDomainWithTeam(teamOnly)).toBe(teamOnly);
  });

  test("WIP keeps a column keying only for one team, and judges it AFTER the domain conflict is resolved", () => {
    expect(normalizeWipFilter({ by: "COLUMN" })).toEqual({});
    expect(normalizeWipFilter({ by: "COLUMN", teamId: 0 })).toEqual({ teamId: 0 });
    expect(normalizeWipFilter({ by: "COLUMN", domain: "FLO" })).toEqual({ domain: "FLO" });
    expect(normalizeWipFilter({ by: "COLUMN", teamId: 1 })).toEqual({ by: "COLUMN", teamId: 1 });
    // The team wins over the domain, which leaves the columns valid.
    expect(normalizeWipFilter({ by: "COLUMN", teamId: 1, domain: "FLO" })).toEqual({ by: "COLUMN", teamId: 1 });
    expect(normalizeWipFilter({ by: "STATUS", itemKind: "EPIC" })).toEqual({ by: "STATUS", itemKind: "EPIC" });
  });

  test("WIP drops an itemKind=BOTH (no longer a WIP kind, the server answers 400), so it falls back to the default TASK", () => {
    expect(normalizeWipFilter({ teamId: 1, itemKind: "BOTH" })).toEqual({ teamId: 1 });
    expect(normalizeWipFilter({ by: "COLUMN", itemKind: "BOTH" })).toEqual({});
    const clean: ReportFilterState = { teamId: 1, itemKind: "TASK" };
    expect(normalizeWipFilter(clean)).toBe(clean);
  });
});

describe("withFilterKey", () => {
  test("a value sets the key without touching the others or the input", () => {
    const filter: ReportFilterState = { teamId: 3 };
    expect(withFilterKey(filter, "domain", "PAY")).toEqual({ teamId: 3, domain: "PAY" });
    expect(filter).toEqual({ teamId: 3 });
  });

  test("null and undefined drop the key", () => {
    const filter: ReportFilterState = { teamId: 3, domain: "PAY" };
    expect(withFilterKey(filter, "domain", null)).toEqual({ teamId: 3 });
    expect(withFilterKey(filter, "domain", undefined as unknown as null)).toEqual({ teamId: 3 });
    expect("domain" in withFilterKey(filter, "domain", null)).toBe(false);
  });
});

describe("rebaseReportFilter", () => {
  test("keys the control did not touch keep the current value", () => {
    const base: ReportFilterState = { teamId: 3 };
    const current: ReportFilterState = { teamId: 3, from: "2026-09-25", to: "2026-10-24" };
    expect(rebaseReportFilter(base, withFilterKey(base, "accountId", "acc-ann"), current)).toEqual({
      teamId: 3,
      from: "2026-09-25",
      to: "2026-10-24",
      accountId: "acc-ann",
    });
  });

  test("a removal deletes the key from the current filter", () => {
    const base: ReportFilterState = { teamId: 3, accountId: "acc-ann" };
    const current: ReportFilterState = { teamId: 3, accountId: "acc-ann", domain: "PAY" };
    const merged = rebaseReportFilter(base, withFilterKey(base, "accountId", null), current);
    expect(merged).toEqual({ teamId: 3, domain: "PAY" });
    expect("accountId" in merged).toBe(false);
  });

  test("coupled period keys move together: from/to set, lastSprints dropped, the rest kept", () => {
    const base: ReportFilterState = { lastSprints: 3, teamId: 1 };
    const current: ReportFilterState = { lastSprints: 3, teamId: 1, accountId: "acc-ann" };
    expect(rebaseReportFilter(base, { from: "2026-09-25", to: "2026-10-24", teamId: 1 }, current)).toEqual({
      from: "2026-09-25",
      to: "2026-10-24",
      teamId: 1,
      accountId: "acc-ann",
    });
  });

  test("coupled team keys move together: picking another team drops the member the control dropped", () => {
    const base: ReportFilterState = { teamId: 1, accountId: "acc-ann", from: "2026-09-25", to: "2026-10-24" };
    const current: ReportFilterState = { ...base, domain: "PAY" };
    expect(rebaseReportFilter(base, { teamId: 2, from: "2026-09-25", to: "2026-10-24" }, current)).toEqual({
      teamId: 2,
      from: "2026-09-25",
      to: "2026-10-24",
      domain: "PAY",
    });
  });

  test("arrays are compared by value: an equal list is untouched, a different one replaces the current", () => {
    const base: ReportFilterState = { accountIds: ["a", "b"] };
    const current: ReportFilterState = { accountIds: ["a", "b", "c"], teamId: 1 };
    expect(rebaseReportFilter(base, { accountIds: ["a", "b"] }, current)).toEqual(current);
    expect(rebaseReportFilter(base, { accountIds: ["b", "a"] }, current)).toEqual({ accountIds: ["b", "a"], teamId: 1 });
  });

  test("does not mutate its inputs", () => {
    const base: ReportFilterState = { teamId: 1 };
    const current: ReportFilterState = { teamId: 1, domain: "PAY" };
    rebaseReportFilter(base, { teamId: 2 }, current);
    expect(current).toEqual({ teamId: 1, domain: "PAY" });
  });
});

describe("domainStandsForTeam", () => {
  test("only a teamOrDomain report read for a domain and no team goes without its required team", () => {
    expect(domainStandsForTeam({ teamOrDomain: true }, { domain: "FLO" })).toBe(true);
    expect(domainStandsForTeam({ teamOrDomain: true }, { domain: "FLO", lastSprints: 3 })).toBe(true);
    // A team beside the domain is the team case (the domain is dropped by normalizeWipFilter), neither is the missing-scope case.
    expect(domainStandsForTeam({ teamOrDomain: true }, { teamId: 1, domain: "FLO" })).toBe(false);
    expect(domainStandsForTeam({ teamOrDomain: true }, {})).toBe(false);
    // A report that did not opt in never lets a domain stand in for its team.
    expect(domainStandsForTeam({}, { domain: "FLO" })).toBe(false);
  });

  test("a single epic stands in for the team only on a report that opted in with epicScope", () => {
    expect(domainStandsForTeam({ teamOrDomain: true, epicScope: true }, { epicId: "FLO-33" })).toBe(true);
    expect(domainStandsForTeam({ teamOrDomain: true, epicScope: true }, { epicId: "FLO-33", lastSprints: 3 })).toBe(true);
    // A team beside the epic is the team case; no epic and no domain is the missing-scope case.
    expect(domainStandsForTeam({ teamOrDomain: true, epicScope: true }, { teamId: 1, epicId: "FLO-33" })).toBe(false);
    expect(domainStandsForTeam({ teamOrDomain: true, epicScope: true }, {})).toBe(false);
    // WIP and the backlog do not read an epic: it never replaces their team.
    expect(domainStandsForTeam({ teamOrDomain: true }, { epicId: "FLO-33" })).toBe(false);
    expect(domainStandsForTeam({ epicScope: true }, { epicId: "FLO-33" })).toBe(false);
  });
});

describe("requiredTeamId", () => {
  const teams = [
    { id: 2, name: "Beta", sprints: [{ sprintId: 21 }] },
    { id: 1, name: "alpha", sprints: [{ sprintId: 11 }] },
    { id: 3, name: "Gamma", sprints: [] },
  ];

  test("a filter that already has a listed team needs none", () => {
    expect(requiredTeamId({ teamId: 3 }, teams, 1)).toBeUndefined();
    expect(requiredTeamId({ teamId: 2, sprintId: 11 }, teams, 3)).toBeUndefined();
  });

  test("a team the list does not know (deleted, or 0 = unassigned) counts as absent", () => {
    expect(requiredTeamId({ teamId: 99 }, teams, 3)).toBe(3);
    expect(requiredTeamId({ teamId: 0 }, teams, 99)).toBe(1);
    expect(requiredTeamId({ teamId: 99, sprintId: 21 }, teams, 3)).toBe(2);
    expect(requiredTeamId({ teamId: 99 }, [], undefined)).toBeUndefined();
  });

  test("the team that lists a linked sprint wins, then the remembered team, then the first by name", () => {
    expect(requiredTeamId({ sprintId: 21 }, teams, 3)).toBe(2);
    expect(requiredTeamId({ sprintId: 999 }, teams, 3)).toBe(3);
    expect(requiredTeamId({}, teams, 3)).toBe(3);
    expect(requiredTeamId({}, teams, 99)).toBe(1);
    expect(requiredTeamId({}, teams, undefined)).toBe(1);
  });

  test("no teams, no team", () => {
    expect(requiredTeamId({}, [], undefined)).toBeUndefined();
  });
});

describe("the backlog's staleness steps", () => {
  test("a value on a step stays, any other snaps to the nearest step (a tie to the larger), 0 stays no limit", () => {
    for (const step of [7, 14, 30, 60, 90, 180, 365]) expect(snapToStalenessStep(step), String(step)).toBe(step);
    expect(snapToStalenessStep(0)).toBe(0);
    expect(snapToStalenessStep(1)).toBe(7);
    expect(snapToStalenessStep(10)).toBe(7);
    expect(snapToStalenessStep(11)).toBe(14);
    expect(snapToStalenessStep(45)).toBe(60);
    expect(snapToStalenessStep(120)).toBe(90);
    expect(snapToStalenessStep(135)).toBe(180);
    expect(snapToStalenessStep(1000)).toBe(365);
    expect(snapToStalenessStep(3650)).toBe(365);
  });

  test("the filter is snapped, the default 90 is dropped, and an untouched filter keeps its identity", () => {
    expect(snapBacklogStaleness({ teamId: 1, touchedWithinDays: 45 })).toEqual({ teamId: 1, touchedWithinDays: 60 });
    expect(snapBacklogStaleness({ teamId: 1, touchedWithinDays: 90 })).toEqual({ teamId: 1 });
    expect(snapBacklogStaleness({ touchedWithinDays: 100 })).toEqual({});
    expect(snapBacklogStaleness({ touchedWithinDays: 0 })).toEqual({ touchedWithinDays: 0 });
    const onStep = { touchedWithinDays: 30 };
    expect(snapBacklogStaleness(onStep)).toBe(onStep);
    const absent = { teamId: 1 };
    expect(snapBacklogStaleness(absent)).toBe(absent);
  });
});

describe("dropMember", () => {
  test("drops the one and the several members, keeping everything else", () => {
    expect(dropMember({ teamId: 1, accountId: "a", lastSprints: 3 })).toEqual({ teamId: 1, lastSprints: 3 });
    expect(dropMember({ teamId: 1, accountIds: ["a", "b"], activityType: "Bug" })).toEqual({ teamId: 1, activityType: "Bug" });
  });

  test("returns the same object when there is no member (a stable identity, so nothing rewrites the URL)", () => {
    const filter = { teamId: 1, lastSprints: 3 };
    expect(dropMember(filter)).toBe(filter);
  });
});
