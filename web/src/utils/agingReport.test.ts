import { describe, expect, test } from "vitest";
import { bandPercentileLabel, bandTone, thresholdsFor } from "./agingReport";
import { AGING_UNIT } from "../test/reportFixtures";

const thresholds = AGING_UNIT.thresholds;

describe("bandTone", () => {
  test("with the default 50/85/95: above p95 is red, above p85 orange, everything else gray", () => {
    expect(bandTone("P95", thresholds)).toBe("red");
    expect(bandTone("P85", thresholds)).toBe("orange");
    expect(bandTone("P50", thresholds)).toBe("gray");
    expect(bandTone("WITHIN", thresholds)).toBe("gray");
  });

  test("hidden thresholds (no band) and a band the configuration does not list stay gray", () => {
    expect(bandTone(null, thresholds)).toBe("gray");
    expect(bandTone(undefined, thresholds)).toBe("gray");
    expect(bandTone("P99", thresholds)).toBe("gray");
  });

  test("the rank, not the number, picks the tone — the order of the list does not matter either", () => {
    const custom = { n: 40, hidden: false, percentiles: [{ percentile: 90, workingDays: 20 }, { percentile: 60, workingDays: 9 }, { percentile: 75, workingDays: 14 }] };
    expect(bandTone("P90", custom)).toBe("red");
    expect(bandTone("P75", custom)).toBe("orange");
    expect(bandTone("P60", custom)).toBe("gray");
    // A single threshold is the top one.
    expect(bandTone("P80", { n: 40, hidden: false, percentiles: [{ percentile: 80, workingDays: 12 }] })).toBe("red");
  });
});

test("a band's threshold label is the lower-case form", () => {
  expect(bandPercentileLabel("P85")).toBe("p85");
});

describe("thresholdsFor", () => {
  test("an epic is judged against the epic thresholds, a task against the task ones", () => {
    const [epic, task] = AGING_UNIT.items;
    expect(thresholdsFor(epic, AGING_UNIT)).toBe(AGING_UNIT.epicThresholds);
    expect(thresholdsFor(task, AGING_UNIT)).toBe(AGING_UNIT.thresholds);
  });
});
