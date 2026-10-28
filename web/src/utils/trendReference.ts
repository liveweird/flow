import { CHART_COLORS } from "./chartColors";

/** The data key (and series name) of Person trend's team reference on a line chart. */
export const REFERENCE_SERIES = "reference";

/**
 * The team-reference series of a trend line chart: the quiet neutral gray, DOTTED ("2 4", against the p90's dashes "6 4"), thinner than the person's
 * line and with no dots — it is a backdrop to read the person's line against, not a second subject. The legend names it.
 */
export function referenceSeries(label: string) {
  return { name: REFERENCE_SERIES, label, color: CHART_COLORS.teamReference, strokeDasharray: "2 4" };
}

/** The line props of the reference series: no dots (so nothing to click), a thinner stroke. */
export const REFERENCE_LINE_PROPS = { dot: false, activeDot: false, strokeWidth: 1.5 } as const;
