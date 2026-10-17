import { describe, expect, test } from "vitest";
import {
  AGING_BAND_COLORS,
  agingItemKey,
  agingLogAxis,
  AGING_LOG_FLOOR,
  logPlotPoints,
  logPlotted,
  agingPlot,
  agingReferenceLines,
  agingYMax,
  colorVar,
  JITTER_SPAN,
  jitterOf,
} from "./agingChart";
import { AGING_BOARD, AGING_HIDDEN, AGING_UNIT } from "../test/reportFixtures";

describe("jitterOf", () => {
  test("is deterministic: the same key always lands on the same offset", () => {
    expect(jitterOf("TASK:FLO-1")).toBe(jitterOf("TASK:FLO-1"));
    expect(jitterOf("TASK:FLO-1")).not.toBe(jitterOf("TASK:FLO-2"));
  });

  test("is pinned to golden values, so a changed hash cannot pass unnoticed", () => {
    expect(jitterOf("TASK:FLO-1")).toBeCloseTo(0.135155391, 9);
    expect(jitterOf("EPIC:FLO-7")).toBeCloseTo(-0.286749276, 9);
  });

  test("stays inside [-0.5, 0.5) and spreads a family of keys over most of it", () => {
    const offsets = Array.from({ length: 200 }, (_, n) => jitterOf(`TASK:ONL-${n}`));
    expect(offsets.every((offset) => offset >= -0.5 && offset < 0.5)).toBe(true);
    expect(Math.min(...offsets)).toBeLessThan(-0.35);
    expect(Math.max(...offsets)).toBeGreaterThan(0.35);
  });
});

describe("agingPlot", () => {
  test("one point per item of the kind: the category index plus its jitter, the age as y", () => {
    const plot = agingPlot(AGING_UNIT, "TASK");
    expect(plot.items.map((item) => item.issueKey)).toEqual(["FLO-1", "FLO-2", "FLO-3", "FLO-4"]);
    // The server's order, minus the position only the epic holds — never re-sorted.
    expect(plot.categories.map((category) => category.label)).toEqual(["In progress", "In review", "On hold"]);
    expect(plot.points).toHaveLength(4);
    plot.points.forEach((point, i) => {
      const item = plot.items[i];
      expect(point.i).toBe(i);
      expect(point.y).toBe(item.ageWorkingDays);
      const index = plot.categories.findIndex((category) => category.key === item.position);
      expect(point.x).toBeCloseTo(index + jitterOf(agingItemKey(item)) * JITTER_SPAN, 10);
      expect(Math.abs(point.x - index)).toBeLessThanOrEqual(JITTER_SPAN / 2);
    });
  });

  test("the same input gives the same offsets, run after run", () => {
    expect(agingPlot(AGING_UNIT, "TASK").points).toEqual(agingPlot(AGING_UNIT, "TASK").points);
  });

  test("tasks and epics are separate plots, each with the positions its own items hold", () => {
    const epics = agingPlot(AGING_UNIT, "EPIC");
    expect(epics.items.map((item) => item.issueKey)).toEqual(["FLO-E1"]);
    expect(epics.categories.map((category) => category.key)).toEqual(["s-epic"]);
  });

  test("board columns keep the server's order, a status on no column last", () => {
    expect(agingPlot(AGING_BOARD, "TASK").categories.map((category) => category.label)).toEqual(["Doing", "Review", "(no column)"]);
  });

  test("a position the server did not list still gets a category, at the end, rather than a dot in the wrong place", () => {
    const stray = { ...AGING_UNIT, items: AGING_UNIT.items.map((item) => (item.issueKey === "FLO-4" ? { ...item, position: "ghost" } : item)) };
    const plot = agingPlot(stray, "TASK");
    expect(plot.categories.at(-1)).toEqual({ key: "ghost", label: "ghost", kind: "STATUS" });
    expect(Math.round(plot.points[3].x)).toBe(plot.categories.length - 1);
  });
});

describe("agingReferenceLines", () => {
  test("one line per configured threshold, lowest first, toned by rank like the table's badge", () => {
    expect(agingReferenceLines(AGING_UNIT.thresholds)).toEqual([
      { percentile: 50, workingDays: 8, tone: "gray" },
      { percentile: 85, workingDays: 15, tone: "orange" },
      { percentile: 95, workingDays: 25, tone: "red" },
    ]);
  });

  test("hidden thresholds draw no line at all", () => {
    expect(agingReferenceLines(AGING_HIDDEN.thresholds)).toEqual([]);
  });
});

describe("agingYMax", () => {
  test("an outlier far above p95 extends the axis to itself — never clipped", () => {
    const plot = agingPlot({ ...AGING_UNIT, items: AGING_UNIT.items.map((item) => (item.issueKey === "FLO-1" ? { ...item, ageWorkingDays: 400 } : item)) }, "TASK");
    expect(agingYMax(plot, agingReferenceLines(AGING_UNIT.thresholds))).toBeGreaterThanOrEqual(400);
  });

  test("a threshold above every item still fits, and an empty plot has a usable axis", () => {
    const plot = agingPlot(AGING_UNIT, "TASK");
    expect(agingYMax(plot, [{ percentile: 95, workingDays: 90, tone: "red" }])).toBeGreaterThanOrEqual(90);
    expect(agingYMax({ kind: "TASK", items: [], categories: [], points: [] }, [])).toBe(1);
  });

  test("a little headroom, so the topmost dot is not cut by the plot's edge", () => {
    const plot = agingPlot(AGING_UNIT, "TASK");
    expect(agingYMax(plot, [])).toBeGreaterThan(30);
  });
});

test("the band colours are the app's vocabulary shades; colorVar writes the CSS variable", () => {
  expect(AGING_BAND_COLORS).toEqual({ red: "red.7", orange: "orange.8", gray: "gray.6" });
  expect(colorVar("red.7")).toBe("var(--mantine-color-red-7)");
});

describe("the log axis", () => {
  test("logPlotted lifts anything under the floor to it and leaves the rest alone", () => {
    expect(AGING_LOG_FLOOR).toBe(0.5);
    expect([0, 0.2, 0.5, 0.6, 742].map(logPlotted)).toEqual([0.5, 0.5, 0.5, 0.6, 742]);
  });

  test("logPlotPoints floors only y, keeping x and the item index", () => {
    expect(logPlotPoints([{ x: 1.2, y: 0, i: 3 }, { x: 2, y: 9, i: 4 }])).toEqual([{ x: 1.2, y: 0.5, i: 3 }, { x: 2, y: 9, i: 4 }]);
  });

  test("agingLogAxis runs from the floor to the top, with the 1-2-5 ticks inside it", () => {
    expect(agingLogAxis(787)).toEqual({ domain: [0.5, 787], ticks: [0.5, 1, 2, 5, 10, 20, 50, 100, 200, 500] });
    expect(agingLogAxis(1)).toEqual({ domain: [0.5, 1], ticks: [0.5, 1] });
    expect(agingLogAxis(0)).toEqual({ domain: [0.5, 1], ticks: [0.5, 1] });
  });
});
