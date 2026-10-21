import { describe, expect, test } from "vitest";
import { COMMITMENTS_EPICS, THROUGHPUT_EPICS, TOP_EPIC_IDS } from "../test/epicFixtures";
import { FILTERS } from "../test/reportFixtures";
import {
  COMMITMENTS_STACKS,
  THROUGHPUT_STACKS,
  commitmentsEpicChart,
  epicDataKey,
  epicHitTarget,
  epicLabeller,
  epicSegmentTarget,
  epicTableRows,
  hasEpicSegments,
  throughputEpicChart,
} from "./sprintEpics";

describe("Commitments' by-epic chart", () => {
  const chart = commitmentsEpicChart(COMMITMENTS_EPICS.sprints, FILTERS);

  test("segments in stack order, rows in completion order", () => {
    expect(chart.segments.map((segment) => segment.key)).toEqual(["101", "102", "OTHER_EPICS", "NO_EPIC"]);
    expect(chart.rows.map((row) => [row.id, row.label])).toEqual([["1:11", "Alpha 1"], ["1:12", "Alpha 2"]]);
  });

  test("every row holds each segment's initial and final MD under the stack's key, 0 where the sprint has none", () => {
    expect(chart.rows[1]).toMatchObject({ i0: 8, f0: 10, i1: 6, f1: 7.5, i2: 4, f2: 4, i3: 2, f3: 3 });
    expect(chart.rows[0]).toMatchObject({ i0: 9, f0: 10, i1: 0, f1: 0, i2: 0, f2: 0, i3: 6, f3: 6 });
  });

  test("a row's segments add up to the sprint's initial and final MD", () => {
    chart.rows.forEach((row, index) => {
      const sprint = [...COMMITMENTS_EPICS.sprints].sort((a, b) => a.sprintId - b.sprintId)[index];
      const sum = (stack: string) => chart.segments.reduce((total, segment) => total + (row[epicDataKey(stack, segment.position)] as number), 0);
      expect(sum("i")).toBeCloseTo(sprint.initialMd, 6);
      expect(sum("f")).toBeCloseTo(sprint.finalMd, 6);
    });
  });
});

describe("Throughput's by-epic chart", () => {
  const chart = throughputEpicChart(THROUGHPUT_EPICS.bySprint, FILTERS);

  test("one delivered stack per sprint that adds up to the sprint's delivered MD", () => {
    expect(chart.rows.map((row) => row.label)).toEqual(["Alpha 1", "Alpha 2"]);
    expect(chart.rows[1]).toMatchObject({ d0: 6, d1: 5, d2: 4, d3: 3 });
    expect(chart.rows[0]).toMatchObject({ d0: 7, d1: 0, d2: 0, d3: 3 });
    const total = (row: (typeof chart.rows)[number]) => chart.segments.reduce((sum, segment) => sum + (row[epicDataKey("d", segment.position)] as number), 0);
    expect(chart.rows.map(total)).toEqual([10, 18]);
  });
});

describe("the table rows beside a by-epic chart", () => {
  test("one row per sprint and segment the sprint has, sprints in completion order, segments in stack order", () => {
    const chart = commitmentsEpicChart(COMMITMENTS_EPICS.sprints, FILTERS);
    const rows = epicTableRows(COMMITMENTS_EPICS.sprints, FILTERS, (sprint) => sprint.byEpic, chart.segments);
    expect(rows.map((row) => `${row.label}|${row.segment.key}`)).toEqual([
      "Alpha 1|101",
      "Alpha 1|NO_EPIC",
      "Alpha 2|101",
      "Alpha 2|102",
      "Alpha 2|OTHER_EPICS",
      "Alpha 2|NO_EPIC",
    ]);
    expect(new Set(rows.map((row) => row.key)).size).toBe(rows.length);
  });
});

describe("the by-epic drill targets", () => {
  const chart = commitmentsEpicChart(COMMITMENTS_EPICS.sprints, FILTERS);
  const [checkout, , other, none] = chart.segments;

  test("an epic's segment names the epic; 'No epic' the fixed key", () => {
    expect(epicSegmentTarget("1:12", "COMMITTED", checkout, chart.segments)).toEqual({ kind: "sprint", teamId: 1, sprintId: 12, bucket: "COMMITTED", epicSegment: "101" });
    expect(epicSegmentTarget("1:12", "FINAL", none, chart.segments)).toEqual({ kind: "sprint", teamId: 1, sprintId: 12, bucket: "FINAL", epicSegment: "NO_EPIC" });
  });

  test("'Other epics' carries the chart's own top epics so the server knows which epics are NOT the rest", () => {
    expect(epicSegmentTarget("1:12", "FINAL", other, chart.segments)).toEqual({
      kind: "sprint",
      teamId: 1,
      sprintId: 12,
      bucket: "FINAL",
      epicSegment: "OTHER_EPICS",
      topEpicIds: TOP_EPIC_IDS,
    });
  });

  test("a row id that names no sprint opens nothing", () => {
    expect(epicSegmentTarget("nope", "FINAL", checkout, chart.segments)).toBeNull();
  });

  test("a chart hit: the data key names the stack (its bucket) and the segment, the row the sprint", () => {
    expect(epicHitTarget({ rowId: "1:12", series: "f1" }, chart.segments, COMMITMENTS_STACKS)).toMatchObject({ bucket: "FINAL", epicSegment: "102" });
    expect(epicHitTarget({ rowId: "1:12", series: "i0" }, chart.segments, COMMITMENTS_STACKS)).toMatchObject({ bucket: "COMMITTED", epicSegment: "101" });
    expect(epicHitTarget({ rowId: "1:12", series: "d3" }, chart.segments, THROUGHPUT_STACKS)).toMatchObject({ bucket: "DELIVERED", epicSegment: "NO_EPIC" });
    expect(epicHitTarget({ rowId: "1:12", series: "d3" }, chart.segments, COMMITMENTS_STACKS)).toBeNull();
    expect(epicHitTarget({ rowId: "1:12", series: "x9" }, chart.segments, COMMITMENTS_STACKS)).toBeNull();
  });
});

describe("the section's gate and the drawer's naming", () => {
  test("a report with no segment anywhere has no section", () => {
    expect(hasEpicSegments([{ byEpic: [] }, { byEpic: [] }])).toBe(false);
    expect(hasEpicSegments([])).toBe(false);
    expect(hasEpicSegments(COMMITMENTS_EPICS.sprints)).toBe(true);
  });

  test("the labeller finds a segment of any sprint by key", () => {
    const label = epicLabeller(COMMITMENTS_EPICS.sprints);
    expect(label("102")?.label).toBe("FLO-11 Search");
    expect(label("999")).toBeUndefined();
    expect(epicLabeller(undefined)("102")).toBeUndefined();
  });
});
