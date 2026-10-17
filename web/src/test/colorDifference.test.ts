import { describe, expect, test } from "vitest";
import { deltaE2000, deltaE2000Lab } from "./colorDifference";

type Lab = [number, number, number];

describe("deltaE2000", () => {
  // Sharma, Wu & Dalal (2005), "The CIEDE2000 Color-Difference Formula", supplementary test data (pair numbers as published).
  test.each<[string, Lab, Lab, number]>([
    ["pair 1: the blue region, where the rotation term bites", [50, 2.6772, -79.7751], [50, 0, -82.7485], 2.0425],
    ["pair 2: the blue region", [50, 3.1571, -77.2803], [50, 0, -82.7485], 2.8615],
    ["pair 7: the grey axis (zero chroma on one side)", [50, 0, 0], [50, -1, 2], 2.3669],
    ["pair 9: hues 180 degrees apart (the mean-hue wrap)", [50, 2.49, -0.001], [50, -2.49, 0.0009], 7.1792],
    ["pair 13: hues 180 degrees apart on the b axis", [50, -0.001, 2.49], [50, 0.0009, -2.49], 4.8045],
    ["pair 16: a quarter turn apart", [50, 2.5, 0], [50, 0, -2.5], 4.3065],
    ["pair 17: lightness and chroma both differ", [50, 2.5, 0], [73, 25, -18], 27.1492],
  ])("%s", (_name, first, second, expected) => {
    expect(deltaE2000Lab(first, second)).toBeCloseTo(expected, 3);
    expect(deltaE2000Lab(second, first)).toBeCloseTo(expected, 3);
  });

  test("on hex colours: identical colours are 0 apart, white and black 100, and the distance is symmetric", () => {
    expect(deltaE2000("#228be6", "#228be6")).toBe(0);
    expect(deltaE2000("#ffffff", "#000000")).toBeCloseTo(100, 0);
    expect(deltaE2000("#228be6", "#e64980")).toBeCloseTo(deltaE2000("#e64980", "#228be6"), 6);
  });

  test("a blue and an indigo are closer than a blue and a lime", () => {
    expect(deltaE2000("#228be6", "#5c7cfa")).toBeLessThan(15);
    expect(deltaE2000("#228be6", "#5c940d")).toBeGreaterThan(50);
  });
});
