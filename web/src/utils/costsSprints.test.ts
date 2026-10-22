import { describe, expect, test } from "vitest";
import { COSTS_WITH_SPRINTS } from "../test/costsSprintsFixtures";
import { FILTERS } from "../test/reportFixtures";
import { costsCapacityRows, costsChartMd, costsEpicTarget, costsSprintName, costsSprintTarget } from "./costsSprints";
import { COSTS_STACKS, costsEpicChart, epicHitTarget } from "./sprintEpics";

const { sprints } = COSTS_WITH_SPRINTS;

describe("the capacity chart's rows", () => {
  test("one per sprint in completion order, identified by team, connection and sprint; a missing capacity stays null (a gap in the line, never a zero)", () => {
    expect(costsCapacityRows([...sprints].reverse(), FILTERS)).toEqual([
      { id: "1:1:11", label: "Alpha 1", acMd: 10, capacityMd: 12, capacitySource: "CALENDAR" },
      { id: "1:1:12", label: "Alpha 2", acMd: 13.5, capacityMd: null, capacitySource: null },
    ]);
  });

  test("the same sprint on two connections is two rows with two identities", () => {
    const twin = { ...sprints[1], connectionId: 2 };
    expect(costsCapacityRows([sprints[1], twin], FILTERS).map((row) => row.id)).toEqual(["1:1:12", "1:2:12"]);
  });
});

describe("the targets", () => {
  test("a sprint row is the whole bar; a row id that names no sprint (or no connection) opens nothing", () => {
    expect(costsSprintTarget("1:2:12")).toEqual({ kind: "cost-sprint", teamId: 1, connectionId: 2, sprintId: 12 });
    expect(costsSprintTarget("nope")).toBeNull();
    expect(costsSprintTarget("1:12")).toBeNull();
    expect(costsSprintTarget("1:0:12")).toBeNull();
  });

  test("a segment is the sprint narrowed to it, and 'Other epics' carries the chart's top epics", () => {
    const chart = costsEpicChart(sprints, FILTERS);
    expect(epicHitTarget({ rowId: "1:1:12", series: "a1" }, chart.segments, COSTS_STACKS, costsEpicTarget)).toEqual({
      kind: "cost-sprint",
      teamId: 1,
      connectionId: 1,
      sprintId: 12,
      epicSegment: "102",
    });
    expect(costsEpicTarget("1:1:12", undefined, { key: "OTHER_EPICS" }, chart.segments)).toEqual({
      kind: "cost-sprint",
      teamId: 1,
      connectionId: 1,
      sprintId: 12,
      epicSegment: "OTHER_EPICS",
      topEpicIds: [101, 102],
    });
    expect(costsEpicTarget("nope", undefined, { key: "101" }, chart.segments)).toBeNull();
  });
});

describe("what the drawer is held against and named by", () => {
  const bar = { kind: "cost-sprint", teamId: 1, connectionId: 1, sprintId: 12 } as const;

  test("the whole bar is the sprint's AC, a segment its own; a sprint, connection or segment the report does not list has none", () => {
    expect(costsChartMd(sprints, bar)).toBe(13.5);
    expect(costsChartMd(sprints, { ...bar, epicSegment: "102" })).toBe(3);
    expect(costsChartMd(sprints, { ...bar, sprintId: 11, epicSegment: "102" })).toBeUndefined();
    expect(costsChartMd(sprints, { ...bar, teamId: 2 })).toBeUndefined();
    expect(costsChartMd(sprints, { ...bar, connectionId: 2 })).toBeUndefined();
    expect(costsChartMd(undefined, bar)).toBeUndefined();
  });

  test("the sprint's name comes from the report", () => {
    expect(costsSprintName(sprints, { ...bar, sprintId: 11 })).toBe("Alpha 1");
    expect(costsSprintName(sprints, { ...bar, sprintId: 99 })).toBeUndefined();
  });
});
