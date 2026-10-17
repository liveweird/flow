import { describe, expect, test } from "vitest";
import { FILTERS } from "../test/reportFixtures";
import { dropStaleReferences, readRememberedFilter, rememberedQuery, withoutRememberedTeam } from "./rememberedFilter";
import { agingWipPath, backlogPath, commitmentsPath, epicProgressPath, throughputPath } from "./reportLinks";

const params = (query: string) => new URLSearchParams(query);

describe("rememberedQuery", () => {
  test("is the canonical filter query plus the page's one non-default switch", () => {
    const filter = { teamId: 1, lastSprints: 3, domain: "FLO", activityType: "Bug" };
    expect(rememberedQuery(filter, params("view=cumulative&drill=bucket:2026-03-02"), throughputPath)).toBe(
      "lastSprints=3&teamId=1&domain=FLO&activityType=Bug&view=cumulative",
    );
  });

  test("leaves out what the page has no control for, a foreign page switch, an unknown switch value, the drill and the epic scope", () => {
    const filter = { teamId: 1, domain: "FLO", activityType: "Bug", bucket: "MONTH" as const, epicId: "FLO-1" };
    expect(rememberedQuery(filter, params("view=cumulative&kind=epics"), commitmentsPath)).toBe("teamId=1");
    expect(rememberedQuery(filter, params("view=weird&kind=epics"), throughputPath)).toBe("teamId=1&domain=FLO&activityType=Bug&bucket=MONTH");
    expect(rememberedQuery(filter, params("kind=epics"), agingWipPath)).toBe("teamId=1&domain=FLO&activityType=Bug&kind=epics");
    expect(rememberedQuery({ epicId: "FLO-1", domain: "FLO" }, params(""), epicProgressPath)).toBe("domain=FLO");
  });

  test("an empty filter is the empty string — the user cleared everything", () => {
    expect(rememberedQuery({}, params(""), commitmentsPath)).toBe("");
  });
});

describe("readRememberedFilter", () => {
  test("parses like a link, keeps several people only where asked, and filters to the page's controls", () => {
    const stored = "teamId=1&accountId=acc-ann&accountId=acc-bob&domain=FLO&touchedWithinDays=30&view=cumulative&kind=epics";
    expect(readRememberedFilter(stored, throughputPath, { multiAccount: true })).toEqual({
      filter: { teamId: 1, accountIds: ["acc-ann", "acc-bob"], domain: "FLO" },
      page: [["view", "cumulative"]],
    });
    expect(readRememberedFilter(stored, backlogPath, {}).filter).toEqual({ teamId: 1, accountId: "acc-ann", domain: "FLO", touchedWithinDays: 30 });
  });

  test("junk reads as nothing", () => {
    expect(readRememberedFilter("teamId=abc&from=nope&view=zzz", throughputPath, {})).toEqual({ filter: {}, page: [] });
    expect(readRememberedFilter("", throughputPath, {})).toEqual({ filter: {}, page: [] });
  });
});

describe("dropStaleReferences", () => {
  test("keeps everything the reference data still lists", () => {
    const filter = {
      teamId: 1,
      accountIds: ["acc-ann"],
      sprintId: 11,
      domain: "FLO",
      activityType: "Bug",
      workCategory: "Maintenance",
      connectionId: 1,
    };
    expect(dropStaleReferences(filter, FILTERS)).toEqual(filter);
  });

  test("drops a deleted team with its people, and the unassigned bucket keeps no roster", () => {
    expect(dropStaleReferences({ teamId: 99, accountId: "acc-ann", sprintId: 11 }, FILTERS)).toEqual({});
    expect(dropStaleReferences({ teamId: 0, accountId: "acc-ann", accountIds: ["acc-ann"] }, FILTERS)).toEqual({ teamId: 0 });
  });

  test("a teamless screen (Compare people) keeps its people — Jira accounts, not a roster — and drops a team, but still checks the slices", () => {
    expect(
      dropStaleReferences({ teamId: 1, accountId: "acc-x", accountIds: ["acc-ann", "acc-cy"], domain: "FLO", activityType: "Gone" }, FILTERS, true),
    ).toEqual({ accountIds: ["acc-ann", "acc-cy"], domain: "FLO" });
  });

  test("drops the people who left the team, keeping those who stayed", () => {
    expect(dropStaleReferences({ teamId: 1, accountIds: ["acc-ann", "acc-cy"], accountId: "acc-cy" }, FILTERS)).toEqual({ teamId: 1, accountIds: ["acc-ann"] });
    expect(dropStaleReferences({ teamId: 1, accountIds: ["acc-cy"] }, FILTERS)).toEqual({ teamId: 1 });
  });

  test("a sprint must be one the picked team lists, or any team's when no team is picked", () => {
    expect(dropStaleReferences({ teamId: 1, sprintId: 21 }, FILTERS)).toEqual({ teamId: 1 });
    expect(dropStaleReferences({ sprintId: 21 }, FILTERS)).toEqual({ sprintId: 21 });
    expect(dropStaleReferences({ sprintId: 999 }, FILTERS)).toEqual({});
  });

  test("drops an unknown domain, activity type, work category and connection", () => {
    expect(dropStaleReferences({ domain: "X", activityType: "Y", workCategory: "Z", connectionId: 7 }, FILTERS)).toEqual({});
  });

  test("does not mutate its input", () => {
    const filter = { teamId: 99 };
    dropStaleReferences(filter, FILTERS);
    expect(filter).toEqual({ teamId: 99 });
  });
});

describe("withoutRememberedTeam", () => {
  test("drops the team and the people, keeps the rest", () => {
    expect(withoutRememberedTeam("lastSprints=3&teamId=2&accountId=a&accountId=b&domain=FLO")).toBe("lastSprints=3&domain=FLO");
  });
});
