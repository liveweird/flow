import { DEFAULT_THEME, type MantineColorsTuple } from "@mantine/core";
import { theme } from "../theme";
import { LIGHT_TOKENS } from "../themeVariables";

/**
 * The heat table's ONE sequential scale (the cost matrix): the brand blue, mixed into the table's own
 * surface in five steps — a light-to-mid blue in the light scheme, a dark-to-brand blue in the dark
 * one, so a bigger figure is always the more intense cell. Each step carries its OWN text colour, and
 * `heatScale.test.ts` recomputes every ratio from the theme: the number in a cell must clear WCAG 1.4.3
 * (4.5:1) on every step in both schemes, since the figure — never the fill — is what a cell says.
 *
 *   light  (base white, top flow.4, dark text)   14.3 · 12.2 · 10.2 · 8.1 · 6.4 : 1
 *   dark   (base dark-7, top flow.8, white text) 12.9 · 10.3 · 8.1 · 6.3 · 5.0 : 1
 *
 * Step 0 (no work) has no fill at all, so an empty cell stays quiet.
 */
export const HEAT_STEPS = 5;

/** One step's fill and the text colour that reads on it. */
export interface HeatStyle {
  background: string;
  color: string;
}

type Scheme = "light" | "dark";

const flow = theme.colors?.flow as MantineColorsTuple;
const WHITE = "#ffffff";
const LIGHT_WEIGHTS = [0.12, 0.3, 0.5, 0.75, 1] as const;
const DARK_WEIGHTS = [0.2, 0.4, 0.6, 0.8, 1] as const;

/** `base` blended `weight` of the way towards `top`, channel by channel in sRGB (CSS `color-mix(in srgb)`). */
function mix(base: string, top: string, weight: number): string {
  const channels = [1, 3, 5].map((i) => {
    const from = parseInt(base.slice(i, i + 2), 16);
    const to = parseInt(top.slice(i, i + 2), 16);
    return Math.round(from * (1 - weight) + to * weight)
      .toString(16)
      .padStart(2, "0");
  });
  return `#${channels.join("")}`;
}

/** The fills, indexed by step − 1 (step 1 is the faintest). */
export const HEAT_SCALE: Readonly<Record<Scheme, ReadonlyArray<HeatStyle>>> = {
  light: LIGHT_WEIGHTS.map((weight) => ({ background: mix(WHITE, flow[4], weight), color: LIGHT_TOKENS.text })),
  dark: DARK_WEIGHTS.map((weight) => ({ background: mix(DEFAULT_THEME.colors.dark[7], flow[8], weight), color: WHITE })),
};

/**
 * The step of a value against the largest one shown: 0 for nothing (or nothing to compare with), else
 * `ceil(value / max × steps)` clamped to 1..steps — linear, so intensity is proportional to the figure and
 * any positive amount shows at least the faintest tint. Monotone in `value`.
 */
export function heatStep(value: number, max: number): number {
  if (value <= 0 || max <= 0) return 0;
  return Math.min(HEAT_STEPS, Math.max(1, Math.ceil((value * HEAT_STEPS) / max)));
}

/** The fill of a step in a scheme; `undefined` for step 0 (no fill). */
export function heatStyle(step: number, scheme: Scheme): HeatStyle | undefined {
  return step <= 0 ? undefined : HEAT_SCALE[scheme][Math.min(step, HEAT_STEPS) - 1];
}
