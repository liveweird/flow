import { describe, expect, test } from "vitest";
import {
  formatDays,
  formatFigure,
  formatIndex,
  formatMd,
  formatMedian,
  formatPercent,
  formatRatio,
  formatSignedMd,
  formatSignedPercent,
  histogramLabels,
  roundScaled,
  teamLabel,
} from "./reportFormat";

describe("reportFormat", () => {
  test("a team prints its name, or its id once it is no longer listed", () => {
    const teams = [{ id: 1, name: "Alpha" }];
    expect(teamLabel(1, teams)).toBe("Alpha");
    expect(teamLabel(77, teams)).toBe("#77");
  });

  test("man-days and ratios keep at most two decimals, no trailing zeros", () => {
    expect(formatMd(12.5)).toBe("12.5");
    expect(formatMd(8)).toBe("8");
    expect(formatRatio(1.2549)).toBe("1.25");
    expect(formatRatio(1)).toBe("1");
    expect(formatRatio(0.5)).toBe("0.5");
  });

  test("a variance in man-days is signed with a true minus, and zero is bare", () => {
    expect(formatSignedMd(3.5)).toBe("+3.5");
    expect(formatSignedMd(-2)).toBe("−2");
    expect(formatSignedMd(0)).toBe("0");
    expect(formatSignedMd(-0.001)).toBe("0");
    expect(formatSignedMd(1.256)).toBe("+1.26");
  });

  test("a performance index always prints two decimals", () => {
    expect(formatIndex(1)).toBe("1.00");
    expect(formatIndex(0.75)).toBe("0.75");
    expect(formatIndex(0.8333333)).toBe("0.83");
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

describe("formatFigure", () => {
  test("rounds only for display, trims trailing zeros and never prints negative zero or exponents", () => {
    expect(formatFigure(0.1 + 0.2)).toBe("0.3");
    expect(formatFigure(2)).toBe("2");
    expect(formatFigure(1.005 * 100)).toBe("100.5");
    expect(formatFigure(0.3333, 4)).toBe("0.3333");
    expect(formatFigure(0.004)).toBe("0");
    expect(formatFigure(-0.001)).toBe("0");
    expect(formatFigure(12.3456)).toBe("12.35");
    // Binary sums just below a decimal half still round as the decimal does (no double rounding).
    expect(formatFigure(0.3509 + 0.1441)).toBe("0.5");
    expect(formatFigure(0.9279 + 0.5871)).toBe("1.52");
    expect(formatFigure(-1.515)).toBe("-1.52");
  });
});

describe("the one man-day rounding rule", () => {
  test("formatMd is formatFigure at two decimals, so float noise is cleaned the same everywhere", () => {
    expect(formatMd(0.3509 + 0.1441)).toBe("0.5");
    expect(formatMd(0.9279 + 0.5871)).toBe("1.52");
    expect(formatMd(0.1 + 0.2)).toBe("0.3");
    expect(formatMd(-0.001)).toBe("0");
    expect(formatMd(-1.515)).toBe("-1.52");
  });

  test("formatSignedMd rounds as formatMd does and signs the result", () => {
    expect(formatSignedMd(0.3509 + 0.1441)).toBe("+0.5");
    expect(formatSignedMd(-(0.3509 + 0.1441))).toBe("−0.5");
    expect(formatSignedMd(-1.515)).toBe("−1.52");
    expect(formatSignedMd(0.004)).toBe("0");
  });

  test("roundScaled is the whole-number core: noise-cleaned, half away from zero, never negative zero", () => {
    expect(roundScaled(0.3509 + 0.1441)).toBe(50);
    expect(roundScaled(12.3456, 1)).toBe(123);
    expect(roundScaled(-1.515)).toBe(-152);
    expect(Object.is(roundScaled(-0.001), 0)).toBe(true);
    expect(roundScaled(0.3333, 4)).toBe(3333);
  });
});
