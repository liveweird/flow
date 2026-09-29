import { describe, expect, test } from "vitest";
import { DEFAULT_THEME } from "@mantine/core";
import { contrast, luminance } from "../test/contrast";
import { DARK_TOKENS, LIGHT_TOKENS } from "../themeVariables";
import { HEAT_SCALE, HEAT_STEPS, heatStep, heatStyle } from "./heatScale";

const SURFACES = { light: "#ffffff", dark: DEFAULT_THEME.colors.dark[7] } as const;
const DIMMED = { light: LIGHT_TOKENS.dimmed, dark: DARK_TOKENS.dimmed } as const;

describe("heatStep", () => {
  test("no work, or nothing to compare with, is step 0", () => {
    expect(heatStep(0, 10)).toBe(0);
    expect(heatStep(-1, 10)).toBe(0);
    expect(heatStep(5, 0)).toBe(0);
  });

  test("any positive figure shows at least the faintest step, the largest the strongest", () => {
    expect(heatStep(0.01, 100)).toBe(1);
    expect(heatStep(100, 100)).toBe(HEAT_STEPS);
    expect(heatStep(120, 100)).toBe(HEAT_STEPS);
  });

  test("is monotone in the value and proportional to it", () => {
    const steps = Array.from({ length: 101 }, (_, i) => heatStep(i, 100));
    for (let i = 1; i < steps.length; i++) expect(steps[i]).toBeGreaterThanOrEqual(steps[i - 1]);
    expect(new Set(steps)).toEqual(new Set([0, 1, 2, 3, 4, 5]));
    expect([heatStep(20, 100), heatStep(21, 100), heatStep(40, 100), heatStep(41, 100)]).toEqual([1, 2, 2, 3]);
  });
});

describe("the heat scale", () => {
  test.each(["light", "dark"] as const)("%s: every step's text clears 4.5:1 on its own fill", (scheme) => {
    expect(HEAT_SCALE[scheme]).toHaveLength(HEAT_STEPS);
    HEAT_SCALE[scheme].forEach((style, i) => {
      expect(contrast(style.color, style.background), `step ${i + 1}`).toBeGreaterThanOrEqual(4.5);
    });
  });

  test.each(["light", "dark"] as const)("%s: a zero cell's dimmed text clears 4.5:1 on the unfilled table surface", (scheme) => {
    expect(contrast(DIMMED[scheme], SURFACES[scheme])).toBeGreaterThanOrEqual(4.5);
  });

  test("the light scheme deepens with the figure, the dark one brightens — and every step differs from the last", () => {
    const light = HEAT_SCALE.light.map((style) => luminance(style.background));
    const dark = HEAT_SCALE.dark.map((style) => luminance(style.background));
    for (let i = 1; i < HEAT_STEPS; i++) {
      expect(light[i]).toBeLessThan(light[i - 1]);
      expect(dark[i]).toBeGreaterThan(dark[i - 1]);
    }
    // The faintest tint is still a visible step off the unfilled surface.
    expect(light[0]).toBeLessThan(luminance(SURFACES.light));
    expect(dark[0]).toBeGreaterThan(luminance(SURFACES.dark));
  });

  test("the strongest step is the brand blue itself (flow.4 light, flow.8 dark)", () => {
    expect(HEAT_SCALE.light[HEAT_STEPS - 1].background).toBe("#4dabf7");
    expect(HEAT_SCALE.dark[HEAT_STEPS - 1].background).toBe("#1971c2");
  });

  test("step 0 has no fill; a step beyond the scale is the strongest", () => {
    expect(heatStyle(0, "light")).toBeUndefined();
    expect(heatStyle(3, "dark")).toBe(HEAT_SCALE.dark[2]);
    expect(heatStyle(99, "light")).toBe(HEAT_SCALE.light[HEAT_STEPS - 1]);
  });
});
