import { describe, expect, test } from "vitest";
import { ACCOUNT_NAV, activeNavPath, homePath, REPORT_PALETTE_LEAVES, visibleSections } from "./navigation";

describe("visibleSections", () => {
  test("a regular session sees Overview, Reports and Administration — Teams stays, the admin-only leaves drop", () => {
    const sections = visibleSections(false);
    expect(sections.map((s) => s.label)).toEqual([
      "appShell.section.overview",
      "appShell.section.reports",
      "appShell.section.administration",
    ]);
    const paths = sections.flatMap((s) => s.items.map((l) => l.to));
    expect(paths).toContain("/teams");
    expect(paths).toContain("/reports/velocity");
    expect(paths).not.toContain("/users");
    expect(paths).not.toContain("/feature-flags");
    expect(paths).not.toContain("/data-sources");
    expect(paths).not.toContain("/metrics-settings");
  });

  test("an admin session gets Teams, Users, Feature flags, Data sources and Metrics settings in the Administration section", () => {
    const admin = visibleSections(true).find((s) => s.label === "appShell.section.administration");
    expect(admin?.items.map((l) => l.to)).toEqual(["/teams", "/users", "/feature-flags", "/data-sources", "/metrics-settings"]);
  });

  test("Reports is visible to everyone — an admin sees the same leaves there", () => {
    for (const admin of [false, true]) {
      const reports = visibleSections(admin).find((s) => s.label === "appShell.section.reports");
      expect(reports?.items.map((l) => l.to)).toEqual(["/reports/velocity", "/reports/task-estimation-accuracy", "/reports/wip"]);
      expect(reports?.items[0].activeFor).toEqual([
        "/reports/velocity",
        "/reports/throughput",
        "/reports/sprint-consistency",
        "/reports/cycle-time",
      ]);
      expect(reports?.items[1].activeFor).toEqual([
        "/reports/task-estimation-accuracy",
        "/reports/epic-estimation-accuracy",
        "/reports/estimate-adjustments",
        "/reports/reported-time-ratio",
      ]);
      expect(reports?.items[2].activeFor).toEqual([
        "/reports/wip",
        "/reports/backlog",
        "/reports/aging-wip",
        "/reports/blocked-time",
        "/reports/epic-progress",
      ]);
      expect(reports?.items.some((l) => l.adminOnly)).toBe(false);
    }
  });

  test("every report tab is a palette-only leaf, never a sidebar leaf", () => {
    expect(REPORT_PALETTE_LEAVES.map((l) => l.to)).toEqual([
      "/reports/velocity",
      "/reports/throughput",
      "/reports/sprint-consistency",
      "/reports/cycle-time",
      "/reports/task-estimation-accuracy",
      "/reports/epic-estimation-accuracy",
      "/reports/estimate-adjustments",
      "/reports/reported-time-ratio",
      "/reports/wip",
      "/reports/backlog",
      "/reports/aging-wip",
      "/reports/blocked-time",
      "/reports/epic-progress",
    ]);
    const sidebar = visibleSections(true).flatMap((s) => s.items.map((l) => l.to));
    expect(sidebar).not.toContain("/reports/throughput");
    expect(sidebar).not.toContain("/reports/sprint-consistency");
    expect(sidebar).not.toContain("/reports/epic-estimation-accuracy");
    expect(sidebar).not.toContain("/reports/estimate-adjustments");
    expect(sidebar).not.toContain("/reports/cycle-time");
    expect(sidebar).not.toContain("/reports/reported-time-ratio");
    expect(sidebar).not.toContain("/reports/backlog");
    expect(sidebar).not.toContain("/reports/aging-wip");
    expect(sidebar).not.toContain("/reports/blocked-time");
    expect(sidebar).not.toContain("/reports/epic-progress");
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
    expect(activeNavPath("/reports/velocity", leaves)).toBe("/reports/velocity");
  });

  test("a leaf stays highlighted on every route it lists in activeFor, and still links to its own", () => {
    expect(activeNavPath("/reports/throughput", leaves)).toBe("/reports/velocity");
    expect(activeNavPath("/reports/sprint-consistency", leaves)).toBe("/reports/velocity");
    expect(activeNavPath("/reports/sprint-consistency/extra", leaves)).toBe("/reports/velocity");
    // The Estimation leaf likewise covers its whole group.
    expect(activeNavPath("/reports/task-estimation-accuracy", leaves)).toBe("/reports/task-estimation-accuracy");
    expect(activeNavPath("/reports/epic-estimation-accuracy", leaves)).toBe("/reports/task-estimation-accuracy");
    expect(activeNavPath("/reports/estimate-adjustments", leaves)).toBe("/reports/task-estimation-accuracy");
    // …and so does the Flow leaf.
    expect(activeNavPath("/reports/wip", leaves)).toBe("/reports/wip");
    expect(activeNavPath("/reports/backlog", leaves)).toBe("/reports/wip");
    expect(activeNavPath("/reports/aging-wip", leaves)).toBe("/reports/wip");
    expect(activeNavPath("/reports/blocked-time", leaves)).toBe("/reports/wip");
    expect(activeNavPath("/reports/epic-progress", leaves)).toBe("/reports/wip");
    expect(activeNavPath("/reports/cycle-time", leaves)).toBe("/reports/velocity");
    expect(activeNavPath("/reports/reported-time-ratio", leaves)).toBe("/reports/task-estimation-accuracy");
    // The longest match still wins across leaves.
    const custom = [
      { to: "/a", label: "appShell.nav.home", icon: leaves[0].icon, activeFor: ["/b/deep"] },
      { to: "/b", label: "appShell.nav.home", icon: leaves[0].icon },
    ] as const;
    expect(activeNavPath("/b/deep/x", custom)).toBe("/a");
    expect(activeNavPath("/b/other", custom)).toBe("/b");
  });

  test("the root matches only exactly", () => {
    expect(activeNavPath(homePath, leaves)).toBe("/");
    expect(activeNavPath("/nowhere", leaves)).toBeNull();
  });
});
