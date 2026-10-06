import type { Distribution } from "../api/reports";

/** A team's name from the reference data; `#<id>` when it is no longer listed (a deleted team still owns history). */
export function teamLabel(teamId: number, teams: ReadonlyArray<{ id: number; name: string }>): string {
  return teams.find((team) => team.id === teamId)?.name ?? `#${teamId}`;
}

/**
 * `value` scaled by `10 ** digits` and rounded half away from zero to a whole number — THE rounding
 * rule for every man-day / task-day figure (the formatters below, the Deep dive burn-up's plan in
 * whole hundredths). The rounding is done on the decimal-scaled value cleaned of binary noise at 6
 * places, not with `toFixed`, so a sum like 0.3509 + 0.1441 (0.49499999…) rounds as the decimal 0.495 does.
 * Never returns `-0`.
 */
export function roundScaled(value: number, digits = 2): number {
  const rounded = Math.round(Number((Math.abs(value) * 10 ** digits).toFixed(6)));
  return value < 0 && rounded > 0 ? -rounded : rounded;
}

/**
 * A man-day / task-day figure for display: rounded by [roundScaled] to `digits` decimals (the only
 * place the exact sums are rounded), trailing zeros dropped, `.` decimal and no grouping so it is
 * locale-stable ("12.5", "8", "0.33"). `formatMd` is this at the default two decimals.
 */
export function formatFigure(value: number, digits = 2): string {
  return String(roundScaled(value, digits) / 10 ** digits);
}

/** Man-days with at most two decimals and no trailing zeros ("12.5", "8", "0.33"). */
export function formatMd(value: number): string {
  return formatFigure(value);
}

/** Man-days with an explicit sign (a true minus): `+3.5`, `−2`, `0` — a variance reads as a direction. */
export function formatSignedMd(value: number): string {
  const text = formatFigure(Math.abs(value));
  if (text === "0") return "0";
  return `${value > 0 ? "+" : "−"}${text}`;
}

/** A performance index (SPI, CPI) at exactly two decimals: `1.00`, `0.85`. */
export function formatIndex(value: number): string {
  return value.toFixed(2);
}

/**
 * How a measure's value is written. `extraDecimals` widens the precision — the histogram labels
 * ask for it when two neighbouring range ends would otherwise print the same ("1 – 1").
 */
export type ValueFormat = (value: number, extraDecimals?: number) => string;

const round = (value: number, decimals: number) => {
  const factor = 10 ** decimals;
  return Math.round(value * factor) / factor;
};

/** A ratio (actual ÷ estimate) with two decimals and no trailing zeros: `1`, `1.25`, `0.5`. */
export const formatRatio: ValueFormat = (value, extraDecimals = 0) => String(round(value, 2 + extraDecimals));

/** One decimal, no trailing zeros: `3`, `3.5`, `0.4` — a mean count, a figure in sprints. */
export const formatOneDecimal: ValueFormat = (value, extraDecimals = 0) => String(round(value, 1 + extraDecimals));

/** Days (working or elapsed) with one decimal and no trailing zeros: `3`, `3.5`, `0.4`. */
export const formatDays: ValueFormat = (value, extraDecimals = 0) => formatOneDecimal(value, extraDecimals);

/** A 0..1 fraction as a percentage with at most one decimal: `0.2` → `20%`, `0.125` → `12.5%`. */
export const formatPercent: ValueFormat = (fraction, extraDecimals = 0) => `${round(fraction * 100, 1 + extraDecimals)}%`;

/** A signed fractional change as a percentage: `0.25` → `+25%`, `-0.1` → `−10%`, `0` → `0%`. */
export const formatSignedPercent: ValueFormat = (fraction, extraDecimals = 0) => {
  const pct = round(fraction * 100, 1 + extraDecimals);
  if (pct === 0) return "0%";
  return `${pct > 0 ? "+" : "−"}${Math.abs(pct)}%`;
};

/**
 * The histogram's range labels ("0.5 – 1"). Written at the measure's normal precision, widened one
 * decimal at a time (up to four) until every range reads differently and no range collapses to
 * "1 – 1" — a tight distribution must not print two identical rows.
 */
export function histogramLabels(buckets: ReadonlyArray<{ from: number; to: number }>, format: ValueFormat): string[] {
  let labels: string[] = [];
  for (let extra = 0; extra <= 4; extra++) {
    labels = buckets.map((b) => `${format(b.from, extra)} – ${format(b.to, extra)}`);
    const distinct = new Set(labels).size === labels.length;
    const collapsed = buckets.some((b) => format(b.from, extra) === format(b.to, extra));
    if (distinct && !collapsed) return labels;
  }
  return labels;
}

/** A group's median in the measure's unit, or a dash when the group is below the minimum sample (counts only). */
export function formatMedian(distribution: Distribution, format: (value: number) => string): string {
  return distribution.hidden || distribution.p50 == null ? "—" : format(distribution.p50);
}
