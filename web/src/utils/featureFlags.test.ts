import { describe, expect, test } from "vitest";
import { isFeatureEnabled, withFeatureEnabled } from "./featureFlags";

describe("featureFlags", () => {
  test("a feature is enabled unless it is in the disabled set", () => {
    expect(isFeatureEnabled([], "MFA")).toBe(true);
    expect(isFeatureEnabled(["MFA"], "MFA")).toBe(false);
  });

  test("withFeatureEnabled adds or removes the feature without duplicating it", () => {
    expect(withFeatureEnabled([], "MFA", false)).toEqual(["MFA"]);
    expect(withFeatureEnabled(["MFA"], "MFA", false)).toEqual(["MFA"]);
    expect(withFeatureEnabled(["MFA"], "MFA", true)).toEqual([]);
    expect(withFeatureEnabled([], "MFA", true)).toEqual([]);
  });
});
