import { describe, expect, test } from "vitest";
import { DEFAULT_THEME, type MantineColorsTuple } from "@mantine/core";
import { deltaE2000 } from "../test/colorDifference";
import { contrast } from "../test/contrast";
import { theme } from "../theme";
import { DARK_TOKENS, LIGHT_TOKENS } from "../themeVariables";
import { AS_OF_COLOR, CATEGORY_PALETTE, CHART_COLORS, DEEP_DIVE_FILL_ALPHA, EPIC_PALETTE, FINAL_COLOR, NO_EPIC_COLOR, OTHER_EPICS_COLOR } from "./chartColors";

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

  test.each(CATEGORY_PALETTE.map((color) => [color]))("the categorical palette colour %s in both schemes", (color) => {
    for (const surface of [...LIGHT_SURFACES, ...DARK_SURFACES]) {
      expect(contrast(shade(color), surface), `${color} on ${surface}`).toBeGreaterThanOrEqual(3);
    }
  });

  test("the categorical palette wears no semantic hue and no hue twice", () => {
    const hues = CATEGORY_PALETTE.map((color) => color.split(".")[0]);
    for (const semantic of ["teal", "orange", "red", "grape"]) expect(hues).not.toContain(semantic);
    expect(new Set(hues).size).toBe(hues.length);
  });

  test("neighbouring palette entries (the wrap-around included) are ΔE2000 ≥ 15 apart", () => {
    CATEGORY_PALETTE.forEach((color, i) => {
      const next = CATEGORY_PALETTE[(i + 1) % CATEGORY_PALETTE.length];
      expect(deltaE2000(shade(color), shade(next)), `${color} → ${next}`).toBeGreaterThanOrEqual(15);
    });
  });

  test("every palette entry is ΔE2000 ≥ 15 from all four semantic colours, except the one listed pair (violet.5 vs the waiting grape)", () => {
    // violet.5 / grape.6 is 11.9: accepted because the palette and the stage keyings never share a chart (`paintBands` reaches
    // the palette only for by=STATUS / COLUMN, and `STAGE_COLORS` only for by=STAGE). Revisit if a chart ever mixes the two.
    const accepted = new Set(["violet.5|grape.6"]);
    const semantic = ["teal.8", "orange.8", "red.7", "grape.6"];
    const seen = new Set<string>();
    for (const color of CATEGORY_PALETTE) {
      for (const meaning of semantic) {
        const key = `${color}|${meaning}`;
        const distance = deltaE2000(shade(color), shade(meaning));
        if (accepted.has(key)) {
          seen.add(key);
          expect(distance, key).toBeLessThan(15);
        } else {
          expect(distance, `${color} vs ${meaning}`).toBeGreaterThanOrEqual(15);
        }
      }
    }
    // the exception list holds no stale entry
    expect([...seen]).toEqual([...accepted]);
  });

  test("the documented palette: the order and the figures of the comment table", () => {
    expect(CATEGORY_PALETTE).toEqual(["flow.6", "violet.5", "cyan.7", "indigo.5", "pink.6", "gray.6", "lime.9"]);
    expect(contrast(shade("violet.5"), "#ffffff")).toBeCloseTo(4.26, 2);
    expect(deltaE2000(shade("flow.6"), shade("violet.5"))).toBeCloseTo(22.9, 1);
    expect(deltaE2000(shade("lime.9"), shade("flow.6"))).toBeCloseTo(60.8, 1);
  });

  test("the final-scope blue is picked per scheme — flow.8 fails on dark paper", () => {
    for (const surface of LIGHT_SURFACES) expect(contrast(shade(FINAL_COLOR.light), surface)).toBeGreaterThanOrEqual(3);
    for (const surface of DARK_SURFACES) expect(contrast(shade(FINAL_COLOR.dark), surface)).toBeGreaterThanOrEqual(3);
    expect(contrast(shade(FINAL_COLOR.light), DARK_TOKENS.surfaceTint)).toBeLessThan(3);
  });

  test("each Deep dive bar's 2px edge clears 3:1 against the surface its 1px halo paints; without the halo a neighbouring fill would not", () => {
    const blend = (fill: string, surface: string) => {
      const channels = [1, 3, 5].map((i) => {
        const f = parseInt(fill.slice(i, i + 2), 16);
        const s = parseInt(surface.slice(i, i + 2), 16);
        return Math.round(f * DEEP_DIVE_FILL_ALPHA + s * (1 - DEEP_DIVE_FILL_ALPHA)).toString(16).padStart(2, "0");
      });
      return `#${channels.join("")}`;
    };
    const layers = [CHART_COLORS.deepDivePlan, CHART_COLORS.deepDiveEv, CHART_COLORS.deepDiveCost];
    // the epic window's dashed outline is drawn over the bars too, ringed in the surface the same way
    const edges = [...layers, CHART_COLORS.deepDiveWindow];
    let unhaloedWorst = Infinity;
    for (const edge of edges) {
      for (const surface of [...LIGHT_SURFACES, ...DARK_SURFACES]) {
        // the halo is the surface itself, so with it the edge is always measured against the surface
        expect(contrast(shade(edge), surface), `${edge} edge on halo ${surface}`).toBeGreaterThanOrEqual(3);
        // ...whereas bars overlap, so an edge can lie over ANY layer's fill blended over that surface
        for (const neighbour of layers) {
          unhaloedWorst = Math.min(unhaloedWorst, contrast(shade(edge), blend(shade(neighbour), surface)));
        }
      }
    }
    // an edge's own tint (flow.6 over white is 2.44:1) and a different hue's tint fall below 3:1: the halo is load-bearing
    expect(unhaloedWorst).toBeLessThan(3);
  });

  test("the Deep dive layers wear three different hues and the vocabulary's own shades", () => {
    expect(CHART_COLORS.deepDivePlan).toBe(CHART_COLORS.committed);
    expect(CHART_COLORS.deepDiveEv).toBe(CHART_COLORS.delivered);
    expect(CHART_COLORS.deepDiveCost).toBe(CHART_COLORS.removed);
    const hues = [CHART_COLORS.deepDivePlan, CHART_COLORS.deepDiveEv, CHART_COLORS.deepDiveCost].map((c) => c.split(".")[0]);
    expect(new Set(hues).size).toBe(3);
  });

  test("the non-working hatch is a neutral gray that clears 3:1 on every surface in both schemes", () => {
    expect(CHART_COLORS.deepDiveHatch.startsWith("gray.")).toBe(true);
    for (const surface of [...LIGHT_SURFACES, ...DARK_SURFACES]) {
      expect(contrast(shade(CHART_COLORS.deepDiveHatch), surface), surface).toBeGreaterThanOrEqual(3);
    }
  });

  test("the burn-up as-of label is text: it clears 4.5:1 on the surfaces of its own scheme", () => {
    for (const surface of LIGHT_SURFACES) expect(contrast(shade(AS_OF_COLOR.light), surface), surface).toBeGreaterThanOrEqual(4.5);
    for (const surface of DARK_SURFACES) expect(contrast(shade(AS_OF_COLOR.dark), surface), surface).toBeGreaterThanOrEqual(4.5);
    // Neither shade would do on the other scheme, hence the pair.
    expect(contrast(shade(AS_OF_COLOR.light), DARK_TOKENS.surfaceTint)).toBeLessThan(4.5);
    expect(contrast(shade(AS_OF_COLOR.dark), "#ffffff")).toBeLessThan(4.5);
  });

  test("the by-epic palette is the categorical one without its gray: six hues in the palette's order, each clearing 3:1 (the palette test above) and ΔE2000 ≥ 15 from its neighbours", () => {
    expect(EPIC_PALETTE).toEqual(["flow.6", "violet.5", "cyan.7", "indigo.5", "pink.6", "lime.9"]);
    expect(OTHER_EPICS_COLOR).toBe("gray.6");
    // the stack order is the six epics, then "Other epics" (gray.6), then "No epic": every neighbouring pair stays tellable apart
    EPIC_PALETTE.forEach((color, i) => {
      const next = EPIC_PALETTE[i + 1] ?? OTHER_EPICS_COLOR;
      expect(deltaE2000(shade(color), shade(next)), `${color} → ${next}`).toBeGreaterThanOrEqual(15);
    });
  });

  test("'No epic' wears a second neutral picked per scheme: ≥ 3:1 on its own scheme's surfaces (no single gray shade does on both) and ΔE2000 ≥ 15 from 'Other epics' and every epic colour", () => {
    for (const surface of LIGHT_SURFACES) expect(contrast(shade(NO_EPIC_COLOR.light), surface), surface).toBeGreaterThanOrEqual(3);
    for (const surface of DARK_SURFACES) expect(contrast(shade(NO_EPIC_COLOR.dark), surface), surface).toBeGreaterThanOrEqual(3);
    expect(contrast(shade(NO_EPIC_COLOR.light), DARK_TOKENS.surfaceTint)).toBeLessThan(3);
    expect(contrast(shade(NO_EPIC_COLOR.dark), "#ffffff")).toBeLessThan(3);
    for (const color of [NO_EPIC_COLOR.light, NO_EPIC_COLOR.dark]) {
      expect(color.startsWith("gray."), `${color} is a neutral`).toBe(true);
      for (const other of [OTHER_EPICS_COLOR, ...EPIC_PALETTE]) expect(deltaE2000(shade(color), shade(other)), `${color} vs ${other}`).toBeGreaterThanOrEqual(15);
    }
    // the documented figures
    expect(contrast(shade(NO_EPIC_COLOR.light), "#ffffff")).toBeCloseTo(11.51, 2);
    expect(contrast(shade(NO_EPIC_COLOR.dark), DARK_TOKENS.surfaceTint)).toBeCloseTo(9.09, 2);
    expect(deltaE2000(shade(NO_EPIC_COLOR.light), shade(OTHER_EPICS_COLOR))).toBeCloseTo(31.0, 1);
    expect(deltaE2000(shade(NO_EPIC_COLOR.dark), shade(OTHER_EPICS_COLOR))).toBeCloseTo(19.8, 1);
  });

  test("the documented ratios: flow.6 is 3.56 on white, red.7 is 3.53 on dark paper", () => {
    expect(contrast(shade("flow.6"), "#ffffff")).toBeCloseTo(3.56, 2);
    expect(contrast(shade("red.7"), DARK_TOKENS.surfaceTint)).toBeCloseTo(3.53, 2);
  });
});
