import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { COSTS_TEAM } from "../test/reportFixtures";
import { COSTS_WITH_CATEGORIES, TARGETS_CONFIGURED, TARGETS_UNCONFIGURED, TREND_EMPTY_FIRST_WEEK, TREND_THREE_WEEKS } from "../test/costsCategoriesFixtures";
import { CATEGORY_PALETTE } from "./chartColors";
import { categoryColors, categoryLabel, deltaAxisMax, deltaBars, formatDeltaPoints, trendChartRows, trendSeries } from "./costsCategories";

const t = i18n.t.bind(i18n);

describe("formatDeltaPoints", () => {
  test("an explicit sign (a true minus) and exactly one decimal; zero is unsigned", () => {
    expect(formatDeltaPoints(2.1)).toBe("+2.1");
    expect(formatDeltaPoints(1)).toBe("+1.0");
    expect(formatDeltaPoints(-10)).toBe("−10.0");
    expect(formatDeltaPoints(0)).toBe("0.0");
    expect(formatDeltaPoints(-0.04)).toBe("0.0");
  });
});

describe("categoryLabel", () => {
  test("UNCATEGORIZED is “No category”; any other key is shown as it is", () => {
    expect(categoryLabel(t, "UNCATEGORIZED")).toBe("No category");
    expect(categoryLabel(t, "Run")).toBe("Run");
  });
});

describe("categoryColors", () => {
  test("the donut's own colours for its named slices, the next hues for what it folds or the targets alone know", () => {
    const colors = categoryColors(COSTS_WITH_CATEGORIES);
    // COSTS_TEAM's work-category donut draws Run, Build and the none-slice by position, without the palette's gray.
    expect(colors).toMatchObject({ Run: "flow.6", Build: "violet.5", UNCATEGORIZED: "cyan.7" });
    expect(colors.Retired).toBe("indigo.5");
    expect(Object.values(colors)).not.toContain("gray.6");
  });

  test("every key of the trend and of the targets has a colour, repeating the hues after the sixth category", () => {
    const keys = Array.from({ length: 8 }, (_, index) => `Cat${index}`);
    const report = {
      ...COSTS_TEAM,
      byWorkCategory: [],
      categoryTargets: null,
      categoryShareTrend: { weeks: [{ weekStart: "2026-03-02", cumulativeMd: 0, byCategory: keys.map((key) => ({ key, cumulativeMd: 0, share: null })) }] },
    };
    const colors = categoryColors(report);
    expect(keys.map((key) => colors[key])).toHaveLength(8);
    expect(colors.Cat6).toBe(colors.Cat0);
    expect(CATEGORY_PALETTE).toContain(colors.Cat0);
  });
});

describe("the diverging bars", () => {
  test("a row without a delta draws no bar; labels are the words, deltas the server's", () => {
    expect(deltaBars(TARGETS_UNCONFIGURED.rows, t)).toEqual([]);
    expect(deltaBars(TARGETS_CONFIGURED.rows, t)).toEqual([
      { key: "Run", label: "Run", delta: 2.1 },
      { key: "Build", label: "Build", delta: 1 },
      { key: "Retired", label: "Retired", delta: -10 },
      { key: "UNCATEGORIZED", label: "No category", delta: 6.9 },
    ]);
  });

  test("the axis is symmetric about zero, in steps of five points, at least ±5", () => {
    expect(deltaAxisMax([])).toBe(5);
    expect(deltaAxisMax([{ key: "a", label: "a", delta: 4.9 }])).toBe(5);
    expect(deltaAxisMax([{ key: "a", label: "a", delta: -5.1 }, { key: "b", label: "b", delta: 2 }])).toBe(10);
    expect(deltaAxisMax(deltaBars(TARGETS_CONFIGURED.rows, t))).toBe(10);
  });
});

describe("the trend lines", () => {
  const colors = categoryColors(COSTS_WITH_CATEGORIES);

  test("one positional series per category of the first week, in the server's order", () => {
    expect(trendSeries(TREND_THREE_WEEKS, colors, t)).toEqual([
      { name: "c0", key: "Run", label: "Run", color: "flow.6", dashed: false },
      { name: "c1", key: "Build", label: "Build", color: "violet.5", dashed: false },
      { name: "c2", key: "UNCATEGORIZED", label: "No category", color: "cyan.7", dashed: false },
    ]);
  });

  test("a hue a line already wore is dashed", () => {
    const series = trendSeries(TREND_THREE_WEEKS, { Run: "flow.6", Build: "flow.6", UNCATEGORIZED: "cyan.7" }, t);
    expect(series.map((line) => line.dashed)).toEqual([false, true, false]);
  });

  test("a chart row per week with each share in percent, and a null share as a gap", () => {
    const series = trendSeries(TREND_THREE_WEEKS, colors, t);
    const rows = trendChartRows(TREND_THREE_WEEKS, series);
    expect(rows.map((row) => row.week)).toEqual(["2026-03-02", "2026-03-09", "2026-03-16"]);
    expect(rows[0]).toEqual({ week: "2026-03-02", c0: 75, c1: 25, c2: 0 });
    expect(rows[2].c0).toBeCloseTo((9 / 14.5) * 100, 9);
    expect(trendChartRows(TREND_EMPTY_FIRST_WEEK, series)[0]).toEqual({ week: "2026-03-02", c0: null, c1: null, c2: null });
  });
});
