import { describe, expect, test } from "vitest";
import { capacityPath, capacitySprintPath, capacityTeamPath, parseCapacitySelection } from "./capacityLinks";

describe("capacity links", () => {
  test("the route family is spelled out once", () => {
    expect(capacityPath).toBe("/capacity");
    expect(capacityTeamPath(4)).toBe("/capacity?teamId=4");
    expect(capacitySprintPath(4, 31)).toBe("/capacity?teamId=4&sprintId=31");
  });

  test("a link round-trips through the parser", () => {
    const query = capacitySprintPath(4, 31).split("?")[1];
    expect(parseCapacitySelection(new URLSearchParams(query))).toEqual({ teamId: 4, sprintId: 31 });
  });

  test("a sprint without a team, or a malformed id, reads as not picked", () => {
    expect(parseCapacitySelection(new URLSearchParams("sprintId=31"))).toEqual({ teamId: undefined, sprintId: undefined });
    expect(parseCapacitySelection(new URLSearchParams("teamId=abc&sprintId=1"))).toEqual({ teamId: undefined, sprintId: undefined });
    expect(parseCapacitySelection(new URLSearchParams("teamId=0"))).toEqual({ teamId: undefined, sprintId: undefined });
    expect(parseCapacitySelection(new URLSearchParams("teamId=4&sprintId=-2"))).toEqual({ teamId: 4, sprintId: undefined });
    expect(parseCapacitySelection(new URLSearchParams(""))).toEqual({ teamId: undefined, sprintId: undefined });
  });
});
