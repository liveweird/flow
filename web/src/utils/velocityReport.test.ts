import { describe, expect, test } from "vitest";
import type { ReportFilters, VelocitySprint } from "../api/reports";
import { sortSprints, teamNameOf, velocityChartRows } from "./velocityReport";

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
  ...over,
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

  test("same completion time falls back to the sprint id", () => {
    expect(sortSprints([{ sprintId: 9, completedAt: 5 }, { sprintId: 2, completedAt: 5 }]).map((s) => s.sprintId)).toEqual([2, 9]);
  });

  test("an unknown team renders as its id", () => {
    expect(teamNameOf(filters, 77)).toBe("#77");
  });
});
