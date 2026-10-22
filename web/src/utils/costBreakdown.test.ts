import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { COSTS_TEAM } from "../test/reportFixtures";
import { CATEGORY_PALETTE } from "./chartColors";
import { breakdownHeading, foldBreakdown, keyLabel, OTHER_COLOR, sliceColors, visibleBreakdowns } from "./costBreakdown";

const t = i18n.t.bind(i18n);

/** A server slice whose exact sum is its figure (no rounding in play). */
const slice = (key: string, md: number) => ({ key, md, exactMd: md });

describe("foldBreakdown", () => {
  test("slices under 3% fold into ONE Other slice, last, that lists them; the rest keep the server's order", () => {
    const slices = foldBreakdown(COSTS_TEAM.byActivityType);
    expect(slices.map((entry) => entry.keys)).toEqual([["Development"], ["Testing"], ["Support"], ["Admin"], ["Planning", "Ops"]]);
    const other = slices[slices.length - 1];
    expect(other.other).toBe(true);
    expect(other.md).toBe(0.5);
    expect(other.share).toBeCloseTo(0.5 / 14.5, 10);
    expect(slices.slice(0, -1).every((entry) => !entry.other && entry.keys.length === 1 && entry.keys[0] === entry.key)).toBe(true);
    // Admin is 3.4% — over the fold line, so it stays its own slice.
    expect(slices.find((entry) => entry.key === "Admin")?.share).toBeGreaterThan(0.03);
  });

  test("the shares add up to 100% and the total is the slices' own figures", () => {
    for (const list of [COSTS_TEAM.byActivityType, COSTS_TEAM.byWorkCategory]) {
      expect(foldBreakdown(list).reduce((sum, entry) => sum + entry.share, 0)).toBeCloseTo(1, 10);
      expect(foldBreakdown(list).reduce((sum, entry) => sum + entry.md, 0)).toBeCloseTo(14.5, 10);
    }
  });

  test("a lone small slice is just itself — Other folds at least two", () => {
    const slices = foldBreakdown([slice("A", 90), slice("B", 8), slice("C", 2)]);
    expect(slices.map((entry) => [entry.key, entry.other])).toEqual([["A", false], ["B", false], ["C", false]]);
  });

  test("zero-MD slices are dropped (nothing to show in a whole) and never counted among the folded", () => {
    expect(foldBreakdown([slice("A", 100), slice("Administrative", 0), slice("Idle", 0)]).map((entry) => entry.key)).toEqual(["A"]);
    expect(foldBreakdown([slice("A", 0)])).toEqual([]);
    expect(foldBreakdown([])).toEqual([]);
  });

  test("Other is the folded EXACT sums rounded once — two slices of 0.125 show 0.13 each and fold to 0.25, the drill's bar, not 0.26", () => {
    const slices = foldBreakdown([
      { key: "Big", md: 1000, exactMd: 1000 },
      { key: "x", md: 0.13, exactMd: 0.125 },
      { key: "y", md: 0.13, exactMd: 0.125 },
    ]);
    const other = slices[slices.length - 1];
    expect(other.keys).toEqual(["x", "y"]);
    expect(other.md).toBe(0.25);
    // Rounded one by one they would read 0.26.
    expect(0.13 + 0.13).toBeCloseTo(0.26, 10);
  });

  test("Other's figure is cleaned of binary noise to two decimals", () => {
    const slices = foldBreakdown([slice("Big", 1000), slice("x", 0.1), slice("y", 0.2), slice("z", 0.3)]);
    expect(slices[slices.length - 1].md).toBeCloseTo(0.6, 10);
  });

  test("when every slice is small they all fold, leaving only Other", () => {
    const slices = foldBreakdown(Array.from({ length: 40 }, (_, i) => slice(`k${i}`, 1)));
    expect(slices).toHaveLength(1);
    expect(slices[0].other).toBe(true);
    expect(slices[0].keys).toHaveLength(40);
    expect(slices[0].share).toBeCloseTo(1, 10);
  });
});

describe("sliceColors", () => {
  test("named slices wear the categorical palette by position without its gray, which is Other's alone", () => {
    const named = sliceColors(14, true).slice(0, 14);
    expect(named).not.toContain(OTHER_COLOR);
    expect(new Set(named.slice(0, 6)).size).toBe(6);
    expect(named.every((colour) => CATEGORY_PALETTE.includes(colour))).toBe(true);
    expect(named[6]).toBe(named[0]);
    expect(sliceColors(3, true)[3]).toBe(OTHER_COLOR);
    expect(sliceColors(3, false)).toHaveLength(3);
  });

  test("the ring's last named slice never repeats the first one's hue (it touches it when there is no Other)", () => {
    for (const count of [2, 6, 7, 13, 19]) {
      const colours = sliceColors(count, false);
      expect(colours[count - 1], `${count} slices`).not.toBe(colours[0]);
      colours.slice(1).forEach((colour, i) => expect(colour, `${count} slices, neighbours ${i}`).not.toBe(colours[i]));
    }
    // With an Other after them the last named slice touches the gray, not the first slice: the plain cycle stays.
    expect(sliceColors(7, true)[6]).toBe(sliceColors(7, true)[0]);
  });
});

describe("visibleBreakdowns", () => {
  test("both donuts with neither slice filter set", () => {
    expect(visibleBreakdowns(COSTS_TEAM, {}).map((entry) => entry.by)).toEqual(["ACTIVITY_TYPE", "WORK_CATEGORY"]);
  });

  test("a breakdown whose own filter is set is not drawn (one slice), the other stays", () => {
    expect(visibleBreakdowns(COSTS_TEAM, { activityType: "Development" }).map((entry) => entry.by)).toEqual(["WORK_CATEGORY"]);
    expect(visibleBreakdowns(COSTS_TEAM, { workCategory: "UNCATEGORIZED" }).map((entry) => entry.by)).toEqual(["ACTIVITY_TYPE"]);
    expect(visibleBreakdowns(COSTS_TEAM, { activityType: "Development", workCategory: "Run" })).toEqual([]);
  });

  test("a breakdown with nothing logged is not drawn", () => {
    expect(visibleBreakdowns({ ...COSTS_TEAM, byActivityType: [], byWorkCategory: [] }, {})).toEqual([]);
  });
});

describe("the words of a slice", () => {
  test("UNCATEGORIZED reads as the filter's '(uncategorized)'; any other key as it is", () => {
    expect(keyLabel(t, "UNCATEGORIZED")).toBe("(uncategorized)");
    expect(keyLabel(t, "Development")).toBe("Development");
  });

  test("the drawer heading is the slice over the breakdown; a folded slice says how many it holds", () => {
    expect(breakdownHeading(t, { by: "ACTIVITY_TYPE", keys: ["Development"] })).toEqual({ title: "Development", subtitle: "Activity type" });
    expect(breakdownHeading(t, { by: "WORK_CATEGORY", keys: ["UNCATEGORIZED"] })).toEqual({
      title: "(uncategorized)",
      subtitle: "Work category",
    });
    expect(breakdownHeading(t, { by: "ACTIVITY_TYPE", keys: ["A", "B", "C"] })).toEqual({ title: "Other (3)", subtitle: "Activity type" });
  });
});
