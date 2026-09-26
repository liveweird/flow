import { describe, expect, test } from "vitest";
import { ACCOUNT_NAV, activeNavPath, homePath, visibleSections } from "./navigation";

describe("visibleSections", () => {
  test("a regular session sees Overview and Administration — Teams stays, the admin-only leaves drop", () => {
    const sections = visibleSections(false);
    expect(sections.map((s) => s.label)).toEqual(["appShell.section.overview", "appShell.section.administration"]);
    const paths = sections.flatMap((s) => s.items.map((l) => l.to));
    expect(paths).toContain("/teams");
    expect(paths).not.toContain("/users");
    expect(paths).not.toContain("/feature-flags");
  });

  test("an admin session gets Teams, Users and Feature flags in the Administration section", () => {
    const admin = visibleSections(true).find((s) => s.label === "appShell.section.administration");
    expect(admin?.items.map((l) => l.to)).toEqual(["/teams", "/users", "/feature-flags"]);
  });

  test("the account leaves never sit in a section", () => {
    const sectionPaths = visibleSections(true).flatMap((s) => s.items.map((l) => l.to));
    for (const leaf of ACCOUNT_NAV) expect(sectionPaths).not.toContain(leaf.to);
    expect(ACCOUNT_NAV.map((l) => l.to)).toEqual(["/change-password", "/changelog"]);
  });
});

describe("activeNavPath", () => {
  const leaves = visibleSections(true).flatMap((s) => s.items);

  test("resolves the longest matching prefix", () => {
    expect(activeNavPath("/users/new", leaves)).toBe("/users");
    expect(activeNavPath("/users/3/edit", leaves)).toBe("/users");
    expect(activeNavPath("/feature-flags", leaves)).toBe("/feature-flags");
    expect(activeNavPath("/teams/3", leaves)).toBe("/teams");
  });

  test("the root matches only exactly", () => {
    expect(activeNavPath(homePath, leaves)).toBe("/");
    expect(activeNavPath("/nowhere", leaves)).toBeNull();
  });
});
