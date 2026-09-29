import { describe, expect, test } from "vitest";
import { formatDays, formatMd, formatMedian, formatPercent, formatRatio, formatSignedPercent, histogramLabels } from "./reportFormat";

describe("reportFormat", () => {
  test("man-days and ratios keep at most two decimals, no trailing zeros", () => {
    expect(formatMd(12.5)).toBe("12.5");
    expect(formatMd(8)).toBe("8");
    expect(formatRatio(1.2549)).toBe("1.25");
    expect(formatRatio(1)).toBe("1");
    expect(formatRatio(0.5)).toBe("0.5");
  });

  test("days keep one decimal, widening on request", () => {
    expect(formatDays(3.5)).toBe("3.5");
    expect(formatDays(3)).toBe("3");
    expect(formatDays(3.14159)).toBe("3.1");
    expect(formatDays(3.14159, 1)).toBe("3.14");
  });

  test("a share is a percentage with at most one decimal", () => {
    expect(formatPercent(0.2)).toBe("20%");
    expect(formatPercent(0.125)).toBe("12.5%");
    expect(formatPercent(0)).toBe("0%");
    expect(formatPercent(1)).toBe("100%");
  });

  test("a change is signed: a plus for growth, a true minus sign for shrinkage, none for zero", () => {
    expect(formatSignedPercent(0.25)).toBe("+25%");
    expect(formatSignedPercent(-0.1)).toBe("−10%");
    expect(formatSignedPercent(0)).toBe("0%");
    expect(formatSignedPercent(0.0004)).toBe("0%");
    expect(formatSignedPercent(-0.125)).toBe("−12.5%");
  });

  test("extra decimals widen the precision of each format", () => {
    expect(formatRatio(1.0049)).toBe("1");
    expect(formatRatio(1.0049, 1)).toBe("1.005");
    expect(formatPercent(0.1234, 1)).toBe("12.34%");
    expect(formatSignedPercent(-0.0123, 1)).toBe("−1.23%");
  });

  test("histogram labels stay at normal precision when they already differ", () => {
    expect(histogramLabels([{ from: 0.5, to: 1 }, { from: 1, to: 1.5 }], formatRatio)).toEqual(["0.5 – 1", "1 – 1.5"]);
  });

  test("histogram labels widen until every range reads differently and none collapses", () => {
    const tight = [{ from: 1, to: 1.004 }, { from: 1.004, to: 1.008 }];
    // At two decimals both would print "1 – 1" / "1 – 1.01"-style collisions.
    const labels = histogramLabels(tight, formatRatio);
    expect(new Set(labels).size).toBe(2);
    expect(labels).toEqual(["1 – 1.004", "1.004 – 1.008"]);
    // Percentages too.
    const pct = histogramLabels([{ from: 0.1, to: 0.10004 }, { from: 0.10004, to: 0.10008 }], formatSignedPercent);
    expect(new Set(pct).size).toBe(2);
  });

  test("a range whose ends are equal has no wider spelling to give — the labels settle at the widest tried", () => {
    expect(histogramLabels([{ from: 1, to: 1 }], formatRatio)).toEqual(["1 – 1"]);
  });

  test("a group's median is a dash below the minimum sample", () => {
    const shown = { n: 9, hidden: false, p50: 1.1, histogram: [] };
    expect(formatMedian(shown, formatRatio)).toBe("1.1");
    expect(formatMedian({ n: 2, hidden: true, histogram: [] }, formatRatio)).toBe("—");
    expect(formatMedian({ n: 9, hidden: false, p50: null, histogram: [] }, formatRatio)).toBe("—");
  });
});
