import { describe, expect, test } from "vitest";
import { DEFAULT_THEME, type MantineColorsTuple } from "@mantine/core";
import { contrast } from "../test/contrast";
import { theme } from "../theme";
import { DARK_TOKENS, LIGHT_TOKENS } from "../themeVariables";
import { BAND_CYCLE, CHART_COLORS, FINAL_COLOR } from "./chartColors";

/** "teal.8" → the hex, resolved through the app theme first (the `flow` tuple), then Mantine's defaults. */
function shade(color: string): string {
  const [name, index] = color.split(".");
  const tuple = (theme.colors?.[name] ?? DEFAULT_THEME.colors[name]) as MantineColorsTuple;
  return tuple[Number(index)];
}

const LIGHT_SURFACES = ["#ffffff", LIGHT_TOKENS.canvas];
const DARK_SURFACES = [DARK_TOKENS.surfaceTint, DARK_TOKENS.canvas];

describe("chart series colours clear WCAG 1.4.11 (3:1) on every chart surface", () => {
  test.each(Object.entries(CHART_COLORS))("%s (%s) in both schemes", (_name, color) => {
    for (const surface of [...LIGHT_SURFACES, ...DARK_SURFACES]) {
      expect(contrast(shade(color), surface), `${color} on ${surface}`).toBeGreaterThanOrEqual(3);
    }
  });

  test.each(BAND_CYCLE.map((color) => [color]))("the WIP band cycle colour %s in both schemes", (color) => {
    for (const surface of [...LIGHT_SURFACES, ...DARK_SURFACES]) {
      expect(contrast(shade(color), surface), `${color} on ${surface}`).toBeGreaterThanOrEqual(3);
    }
  });

  test("the WIP band cycle alternates hues (neighbours never share one, wrap-around included) and uses no semantic hue", () => {
    const hue = (color: string) => color.split(".")[0];
    BAND_CYCLE.forEach((color, i) => {
      expect(hue(color)).not.toBe(hue(BAND_CYCLE[(i + 1) % BAND_CYCLE.length]));
    });
    for (const semantic of ["teal", "orange", "red"]) expect(BAND_CYCLE.map(hue)).not.toContain(semantic);
  });

  test("the final-scope blue is picked per scheme — flow.8 fails on dark paper", () => {
    for (const surface of LIGHT_SURFACES) expect(contrast(shade(FINAL_COLOR.light), surface)).toBeGreaterThanOrEqual(3);
    for (const surface of DARK_SURFACES) expect(contrast(shade(FINAL_COLOR.dark), surface)).toBeGreaterThanOrEqual(3);
    expect(contrast(shade(FINAL_COLOR.light), DARK_TOKENS.surfaceTint)).toBeLessThan(3);
  });

  test("the documented ratios: flow.6 is 3.56 on white, red.7 is 3.53 on dark paper", () => {
    expect(contrast(shade("flow.6"), "#ffffff")).toBeCloseTo(3.56, 2);
    expect(contrast(shade("red.7"), DARK_TOKENS.surfaceTint)).toBeCloseTo(3.53, 2);
  });
});
