import { describe, expect, test } from "vitest";
import { COSTS_WITH_SPRINTS } from "../test/costsSprintsFixtures";
import { FILTERS } from "../test/reportFixtures";
import { costsCapacityRows, costsChartMd, costsEpicTarget, costsSprintConnection, costsSprintName, costsSprintTarget } from "./costsSprints";
import { COSTS_STACKS, costsEpicChart, epicHitTarget } from "./sprintEpics";

const { sprints } = COSTS_WITH_SPRINTS;

describe("the capacity chart's rows", () => {
  test("one per sprint in completion order, a missing capacity stays null (a gap in the line, never a zero)", () => {
    expect(costsCapacityRows([...sprints].reverse(), FILTERS)).toEqual([
      { id: "1:11", label: "Alpha 1", acMd: 10, capacityMd: 12, capacitySource: "CALENDAR" },
      { id: "1:12", label: "Alpha 2", acMd: 13.5, capacityMd: null, capacitySource: null },
    ]);
  });
});

describe("the targets", () => {
  test("a sprint row is the whole bar; a row id that names no sprint opens nothing", () => {
    expect(costsSprintTarget("1:12")).toEqual({ kind: "cost-sprint", teamId: 1, sprintId: 12 });
    expect(costsSprintTarget("nope")).toBeNull();
  });

  test("a segment is the sprint narrowed to it, and 'Other epics' carries the chart's top epics", () => {
    const chart = costsEpicChart(sprints, FILTERS);
    expect(epicHitTarget({ rowId: "1:12", series: "a1" }, chart.segments, COSTS_STACKS, costsEpicTarget)).toEqual({
      kind: "cost-sprint",
      teamId: 1,
      sprintId: 12,
      epicSegment: "102",
    });
    expect(costsEpicTarget("1:12", undefined, { key: "OTHER_EPICS" }, chart.segments)).toEqual({
      kind: "cost-sprint",
      teamId: 1,
      sprintId: 12,
      epicSegment: "OTHER_EPICS",
      topEpicIds: [101, 102],
    });
    expect(costsEpicTarget("nope", undefined, { key: "101" }, chart.segments)).toBeNull();
  });
});

describe("what the drawer is held against and named by", () => {
  test("the whole bar is the sprint's AC, a segment its own; a sprint or segment the report does not list has none", () => {
    expect(costsChartMd(sprints, { kind: "cost-sprint", teamId: 1, sprintId: 12 })).toBe(13.5);
    expect(costsChartMd(sprints, { kind: "cost-sprint", teamId: 1, sprintId: 12, epicSegment: "102" })).toBe(3);
    expect(costsChartMd(sprints, { kind: "cost-sprint", teamId: 1, sprintId: 11, epicSegment: "102" })).toBeUndefined();
    expect(costsChartMd(sprints, { kind: "cost-sprint", teamId: 2, sprintId: 12 })).toBeUndefined();
    expect(costsChartMd(undefined, { kind: "cost-sprint", teamId: 1, sprintId: 12 })).toBeUndefined();
  });

  test("the sprint's name and connection come from the report", () => {
    expect(costsSprintName(sprints, { teamId: 1, sprintId: 11 })).toBe("Alpha 1");
    expect(costsSprintName(sprints, { teamId: 1, sprintId: 99 })).toBeUndefined();
    expect(costsSprintConnection(sprints, { teamId: 1, sprintId: 11 })).toBe(1);
    expect(costsSprintConnection(undefined, { teamId: 1, sprintId: 11 })).toBeUndefined();
  });
});
