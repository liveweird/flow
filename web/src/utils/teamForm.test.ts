import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import { teamFormValidation, teamSaveErrorMessage, toTeamBody, toTeamFormValues } from "./teamForm";

const t = i18n.t;

describe("teamForm", () => {
  test("validation mirrors the server's length rules", () => {
    const rules = teamFormValidation(t);
    expect(rules.name("")).toBe("Name must be 1–100 characters");
    expect(rules.name("  ")).not.toBeNull();
    expect(rules.name("x".repeat(101))).not.toBeNull();
    expect(rules.name("Payments")).toBeNull();
    expect(rules.description("d".repeat(501))).not.toBeNull();
    expect(rules.description("")).toBeNull();
  });

  test("the body trims and sends a blank description as null; values round-trip from a response", () => {
    expect(toTeamBody({ name: "  Payments ", description: "   " })).toEqual({ name: "Payments", description: null });
    expect(toTeamBody({ name: "A", description: " movers " })).toEqual({ name: "A", description: "movers" });
    expect(
      toTeamFormValues({ id: 1, name: "A", description: null, members: [], createdAt: 0, updatedAt: 0 }),
    ).toEqual({ name: "A", description: "" });
  });

  test("save errors map to the fixed vocabulary", () => {
    expect(teamSaveErrorMessage(new ApiError(403, null), t)).toBe(t("teams.saveForbidden"));
    expect(teamSaveErrorMessage(new ApiError(404, null), t)).toBe(t("teams.saveGone"));
    expect(teamSaveErrorMessage(new ApiError(400, null), t)).toBe(t("teams.saveInvalid"));
    expect(teamSaveErrorMessage(new ApiError(500, null), t)).toBe("Save failed (500)");
    expect(teamSaveErrorMessage(new DOMException("t", "TimeoutError"), t)).toBe(t("common.error.timeout"));
    expect(teamSaveErrorMessage(new Error("net"), t)).toBe(t("common.error.saveFailedNetwork"));
  });
});
