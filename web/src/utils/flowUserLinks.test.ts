import { describe, expect, test } from "vitest";
import { editFlowUserPath, flowUserFeaturesPath, flowUsersPath, newFlowUserPath } from "./flowUserLinks";

describe("flowUserLinks", () => {
  test("builds the list, create, edit and features paths", () => {
    expect(flowUsersPath).toBe("/flow-users");
    expect(newFlowUserPath).toBe("/flow-users/new");
    expect(editFlowUserPath(7)).toBe("/flow-users/7/edit");
    expect(flowUserFeaturesPath(7)).toBe("/flow-users/7/features");
  });
});
