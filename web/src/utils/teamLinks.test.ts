import { describe, expect, test } from "vitest";
import { teamPath, teamsPath } from "./teamLinks";

describe("teamLinks", () => {
  test("builds the list and detail paths", () => {
    expect(teamsPath).toBe("/teams");
    expect(teamPath(7)).toBe("/teams/7");
  });
});
