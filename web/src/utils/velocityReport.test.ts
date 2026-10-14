import { describe, expect, test } from "vitest";
import type { ReportFilters, VelocitySprint } from "../api/reports";
import { hasCapacity, loadFraction, velocityChartRows, velocityShareRows, velocityUserChart } from "./velocityReport";

const sprint = (over: Partial<VelocitySprint>): VelocitySprint => ({
  sprintId: 1,
  name: "S1",
  teamId: 1,
  completedAt: 1000,
  initialMd: 10,
  initialItems: 4,
  finalMd: 12,
  finalItems: 5,
  snapshot: null,
  drift: false,
  capacityMd: null,
  capacitySource: null,
  byUser: [],
  ...over,
});

const share = (accountId: string, label: string, initialMd: number, finalMd: number) => ({
  accountId,
  label,
  initialMd,
  initialItems: 1,
  finalMd,
  finalItems: 2,
});

const filters = {
  teams: [
    { id: 1, name: "Alpha", sprints: [], members: [] },
    { id: 2, name: "Beta", sprints: [], members: [] },
  ],
} as unknown as ReportFilters;

describe("velocityChartRows", () => {
  test("orders by completion, an open sprint last", () => {
    const rows = velocityChartRows(
      [
        sprint({ sprintId: 3, name: "Open", completedAt: null }),
        sprint({ sprintId: 2, name: "Late", completedAt: 3000 }),
        sprint({ sprintId: 1, name: "Early", completedAt: 1000 }),
      ],
      filters,
    );
    expect(rows.map((r) => r.label)).toEqual(["Early", "Late", "Open"]);
  });

  test("labels carry the team only when several teams share the chart", () => {
    const rows = velocityChartRows(
      [sprint({ sprintId: 1, teamId: 1 }), sprint({ sprintId: 2, teamId: 2, name: "S2" })],
      filters,
    );
    expect(rows.map((r) => r.label)).toEqual(["Alpha · S1", "Beta · S2"]);
    expect(rows[0]).toMatchObject({ initialMd: 10, finalMd: 12, initialItems: 4, finalItems: 5 });
  });

  test("each row carries its sprint's capacity and where it came from; a sprint without one stays null", () => {
    const rows = velocityChartRows(
      [
        sprint({ sprintId: 1, completedAt: 1000, capacityMd: 25, capacitySource: "DEFAULT" }),
        sprint({ sprintId: 2, completedAt: 2000, capacityMd: 18.5, capacitySource: "CONFIGURED" }),
        sprint({ sprintId: 3, completedAt: 3000 }),
      ],
      filters,
    );
    expect(rows.map((r) => [r.capacityMd, r.capacitySource])).toEqual([
      [25, "DEFAULT"],
      [18.5, "CONFIGURED"],
      [null, null],
    ]);
    expect(hasCapacity(rows)).toBe(true);
    expect(hasCapacity(rows.slice(2))).toBe(false);
    expect(hasCapacity([])).toBe(false);
  });
});

describe("loadFraction", () => {
  test("initial scope over capacity; null without a (non-zero) capacity", () => {
    expect(loadFraction(20, 25)).toBeCloseTo(0.8);
    expect(loadFraction(30, 25)).toBeCloseTo(1.2);
    expect(loadFraction(0, 25)).toBe(0);
    expect(loadFraction(20, null)).toBeNull();
    expect(loadFraction(20, 0)).toBeNull();
  });
});

describe("velocityUserChart", () => {
  const ann = (initialMd: number, finalMd: number) => share("acc:ann", "Ann", initialMd, finalMd);
  const bob = (initialMd: number, finalMd: number) => share("acc:bob", "Bob", initialMd, finalMd);

  test("one row per sprint in completion order, two keyed values per person, people in request order", () => {
    const { users, rows } = velocityUserChart(
      [
        sprint({ sprintId: 2, name: "Late", completedAt: 3000, byUser: [ann(4, 5), bob(0, 1.5)] }),
        sprint({ sprintId: 1, name: "Early", completedAt: 1000, byUser: [ann(2, 3), bob(6, 7)] }),
      ],
      filters,
    );
    expect(users).toEqual([
      { accountId: "acc:ann", label: "Ann", committedKey: "c0", finalKey: "f0" },
      { accountId: "acc:bob", label: "Bob", committedKey: "c1", finalKey: "f1" },
    ]);
    expect(rows).toEqual([
      { label: "Early", c0: 2, f0: 3, c1: 6, f1: 7 },
      { label: "Late", c0: 4, f0: 5, c1: 0, f1: 1.5 },
    ]);
  });

  test("a person a sprint does not list counts 0 there; one person is one segment pair; no byUser, no users", () => {
    const { users, rows } = velocityUserChart(
      [
        sprint({ sprintId: 1, completedAt: 1000, byUser: [ann(2, 3)] }),
        sprint({ sprintId: 2, name: "S2", completedAt: 2000, byUser: [ann(1, 1), bob(5, 6)] }),
      ],
      filters,
    );
    expect(users.map((u) => u.accountId)).toEqual(["acc:ann", "acc:bob"]);
    expect(rows[0]).toEqual({ label: "S1", c0: 2, f0: 3, c1: 0, f1: 0 });
    expect(velocityUserChart([sprint({ byUser: [ann(2, 3)] })], filters).users).toHaveLength(1);
    expect(velocityUserChart([sprint({})], filters)).toEqual({ users: [], rows: [{ label: "S1" }] });
  });
});

describe("velocityShareRows", () => {
  test("a flat row per sprint and person, sprints in completion order, people in request order", () => {
    const rows = velocityShareRows(
      [
        sprint({ sprintId: 2, completedAt: 2000, byUser: [share("a", "Ann", 1, 2), share("b", "Bob", 3, 4)] }),
        sprint({ sprintId: 1, completedAt: 1000, byUser: [share("a", "Ann", 5, 6), share("b", "Bob", 0, 0)] }),
      ],
      filters,
    );
    expect(rows.map((r) => [r.sprint.sprintId, r.share.accountId, r.share.initialMd])).toEqual([
      [1, "a", 5],
      [1, "b", 0],
      [2, "a", 1],
      [2, "b", 3],
    ]);
    expect(new Set(rows.map((r) => r.key)).size).toBe(4);
  });
});
