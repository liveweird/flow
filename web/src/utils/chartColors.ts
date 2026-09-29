/**
 * The report charts' series colours — the app's colour vocabulary (blue = plan/committed,
 * teal = delivered, orange = carried over / added scope, red = dropped, gray = removed/neutral;
 * no new hue) as concrete Mantine theme shades.
 *
 * Every mark must clear WCAG 1.4.11 (≥ 3:1) on every surface a chart sits on: white and the
 * `#f5f7fb` canvas in the light scheme, the `#2e2e2e` paper and the `#1f1f1f` canvas in the dark
 * one. `chartColors.test.ts` recomputes these ratios from the theme, so the table below cannot
 * drift from the code (white / canvas | dark paper / dark canvas):
 *
 *   committed  flow.6    3.56 / 3.32 | 3.82 / 4.63
 *   delivered  teal.8    3.95 / 3.68 | 3.44 / 4.18
 *   carried    orange.8  3.58 / 3.34 | 3.79 / 4.60   (added scope uses the same hue: never both in one chart)
 *   dropped    red.7     3.84 / 3.58 | 3.53 / 4.29
 *   removed    gray.6    3.32 / 3.10 | 4.09 / 4.96
 *   band 3rd   flow.7    4.20 / 3.91 | 3.24 / 3.93   (the WIP band cycle's extra blue: flow.5 fails on white, flow.8 on dark paper)
 *   final      flow.8    5.02 / 4.68 (light)   ·   flow.4  5.49 / 6.66 (dark; flow.8 is 2.70:1 on dark paper)
 *
 * Adjacent series in one chart must also be tellable apart with full colour vision (the dataviz
 * skill's ΔE ≥ 15 floor), which a same-hue pair of blues is not — so no chart here plots
 * committed and final side by side except velocity's (see the note there); stacked partitions
 * order their segments so orange and red never touch.
 */
export const CHART_COLORS = {
  committed: "flow.6",
  /** Estimation distributions: blue is the plan side — the estimate the actual cost is measured against. */
  estimate: "flow.6",
  delivered: "teal.8",
  carriedOver: "orange.8",
  dropped: "red.7",
  added: "orange.8",
  removed: "gray.6",
  /** Trend lines: the median is the blue primary, the p90 tail the neutral gray — and it is dashed. */
  trendMedian: "flow.6",
  trendTail: "gray.6",
  /** The backlog is planned work nobody has started: the plan blue. */
  backlog: "flow.6",
  /** WIP bands by stage: gray = not started, blue = in progress (active work), teal = done, orange = unmapped (a soft configuration finding). */
  stageNotStarted: "gray.6",
  stageInProgress: "flow.6",
  stageDone: "teal.8",
  stageUnmapped: "orange.8",
} as const;

/**
 * The bands of a WIP chart keyed by status or board column — arbitrary names, so they must not wear
 * a colour that MEANS something (teal = success, orange = waived, red = blocked). The neutral
 * palette left is blue and gray, and only three of their shades clear 3:1 on every chart surface
 * (flow.6, flow.7, gray.6; flow.5 and gray.5 fail on white, flow.8 and gray.7 on dark paper). So the
 * cycle ALTERNATES the two hues — bands are coloured by position among those shown, so neighbours
 * never share a hue — and beyond two bands a hue (or a shade) repeats: the legend, the tooltip and
 * the tables beside the chart carry identity, never the colour alone. Stage keyings do not use this:
 * a stage has a meaning, and wears its vocabulary colour (`stage*` above).
 */
export const BAND_CYCLE: ReadonlyArray<string> = ["flow.6", "gray.6", "flow.7", "gray.6"];

/** The final-scope blue: the deeper step in the light scheme, a lighter one in the dark. */
export const FINAL_COLOR = { light: "flow.8", dark: "flow.4" } as const;
