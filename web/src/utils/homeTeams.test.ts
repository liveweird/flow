import { describe, expect, test } from "vitest";
import type { ReportFilterTeam } from "../api/reports";
import { defaultHomeTeams, effectiveHomeTeams, HOME_MAX_TEAMS, isTeamPick, livePick } from "./homeTeams";

const team = (id: number, managerUserId: number | null = null): ReportFilterTeam => ({
  id,
  name: `Team ${id}`,
  managerUserId,
  sprints: [],
  members: [],
});
// Name order is the array order, as /reports/filters lists them.
const TEAMS = [team(1), team(2, 7), team(3), team(4, 7), team(5)];

describe("isTeamPick", () => {
  test("accepts null and up to three positive whole ids, nothing else", () => {
    expect(isTeamPick(null)).toBe(true);
    expect(isTeamPick([])).toBe(true);
    expect(isTeamPick([1, 2, 3])).toBe(true);
    for (const bad of [undefined, "1", [1, 2, 3, 4], [0], [-1], [1.5], ["1"], [null], {}]) expect(isTeamPick(bad)).toBe(false);
  });
});

describe("defaultHomeTeams", () => {
  test("the teams the viewer manages first, then the rest by name, in the list's order", () => {
    expect(HOME_MAX_TEAMS).toBe(3);
    expect(defaultHomeTeams(TEAMS, 7)).toEqual([1, 2, 4]);
  });
  test("without a manager match (or a user id) it is the first three by name", () => {
    expect(defaultHomeTeams(TEAMS, 99)).toEqual([1, 2, 3]);
    expect(defaultHomeTeams(TEAMS, null)).toEqual([1, 2, 3]);
  });
  test("more than three managed teams are cut at three; fewer teams than three are all listed", () => {
    expect(defaultHomeTeams([team(1, 7), team(2, 7), team(3, 7), team(4, 7)], 7)).toEqual([1, 2, 3]);
    expect(defaultHomeTeams([team(1)], 7)).toEqual([1]);
    expect(defaultHomeTeams([], 7)).toEqual([]);
  });
});

describe("livePick and effectiveHomeTeams", () => {
  test("a stored pick is limited to existing teams, in the list's order", () => {
    expect(livePick([4, 2, 99], TEAMS)).toEqual([2, 4]);
    expect(effectiveHomeTeams([4, 2, 99], TEAMS, 7)).toEqual([2, 4]);
  });
  test("nothing stored, or nothing of it left, is the default", () => {
    expect(livePick(null, TEAMS)).toBeNull();
    expect(livePick([99], TEAMS)).toBeNull();
    expect(effectiveHomeTeams([99], TEAMS, 7)).toEqual([1, 2, 4]);
    expect(effectiveHomeTeams(null, TEAMS, null)).toEqual([1, 2, 3]);
  });
});
