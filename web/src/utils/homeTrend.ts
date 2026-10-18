import type { Trend, TrendTone } from "./homeOverview";

/**
 * The trend marker's look: an arrow for the direction of the VALUE (up, down) in the colour of the verdict (teal better, orange
 * worse, gray otherwise). A tie within the tolerance ("No change") points sideways whatever the tiny difference was -- an up arrow
 * would claim a movement the server called noise. Colour is never the only carrier: the verdict is also a word (in the page's flow
 * and in the tooltip and table of every chart).
 */
const GLYPH = { up: "↑", down: "↓", flat: "→" } as const;

/** The arrow of a trend, `null` where there is nothing to point at (no previous value to compare with). */
export function trendGlyph(trend: Trend | null): string | null {
  if (trend === null || trend.direction === null) return null;
  return trend.tone === "same" ? GLYPH.flat : GLYPH[trend.direction];
}

const TONE_COLOR: Record<TrendTone, string> = {
  better: "teal.8",
  worse: "orange.8",
  same: "gray.6",
  none: "gray.6",
};

/** The Mantine colour of a verdict (the app's vocabulary: teal = success, orange = soft finding, gray = neutral). */
export function trendColor(tone: TrendTone): string {
  return TONE_COLOR[tone];
}

/** A Mantine theme colour as a CSS custom property, for SVG attributes that Mantine's `c` prop cannot style (`teal.8` → `var(--mantine-color-teal-8)`). */
export function cssColor(color: string): string {
  return `var(--mantine-color-${color.replace(".", "-")})`;
}

/** The same colour as a CSS custom property. */
export function trendCssColor(tone: TrendTone): string {
  return cssColor(TONE_COLOR[tone]);
}
