import { describe, expect, test } from "vitest";
import { editUserPath, newUserPath, userFeaturesPath, usersPath } from "./userLinks";

describe("userLinks", () => {
  test("builds the list, create, edit and features paths", () => {
    expect(usersPath).toBe("/users");
    expect(newUserPath).toBe("/users/new");
    expect(editUserPath(7)).toBe("/users/7/edit");
    expect(userFeaturesPath(7)).toBe("/users/7/features");
  });
});
