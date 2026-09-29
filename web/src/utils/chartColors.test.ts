import { describe, expect, test } from "vitest";
import { DEFAULT_THEME, type MantineColorsTuple } from "@mantine/core";
import { theme } from "../theme";
import { DARK_TOKENS, LIGHT_TOKENS } from "../themeVariables";
import { CHART_COLORS, FINAL_COLOR } from "./chartColors";

function luminance(hex: string): number {
  const [r, g, b] = [1, 3, 5]
    .map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
    .map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

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
