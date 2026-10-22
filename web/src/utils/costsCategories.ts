import type { TFunction } from "i18next";
import type { CostsCategoryTargetRow, CostsCategoryTrend, CostsReport } from "../api/reports";
import { foldBreakdown, keyLabel, SLICE_PALETTE, sliceColors } from "./costBreakdown";

/** A work category in words: the same as the donut legend, the drill drawer and the filter (`UNCATEGORIZED` is "(uncategorized)"), any other key as it is. */
export function categoryLabel(t: TFunction, key: string): string {
  return keyLabel(t, key);
}

/**
 * The colour of every work category the two widgets draw, the donut's own where the donut draws one: the named slices of
 * `byWorkCategory` wear `sliceColors` by position (the very call the donut makes), and a category the donut folds into "Other" or
 * drops (and one only the targets know) continues down the palette after them — it needs a hue of its own in a line chart, and the
 * gray of "Other" would name many lines at once. From the seventh category on the hues repeat; identity never rests on colour (the
 * legend, the tooltip and both tables name every category).
 */
export function categoryColors(report: CostsReport): Record<string, string> {
  const slices = foldBreakdown(report.byWorkCategory);
  const named = slices.filter((slice) => !slice.other);
  const hues = sliceColors(named.length, slices.some((slice) => slice.other));
  const colors: Record<string, string> = {};
  named.forEach((slice, index) => {
    colors[slice.key] = hues[index];
  });
  let next = named.length;
  const rest = [
    ...(report.categoryShareTrend?.weeks[0]?.byCategory.map((category) => category.key) ?? []),
    ...(report.categoryTargets?.rows.map((row) => row.key) ?? []),
  ];
  for (const key of rest) {
    if (key in colors) continue;
    colors[key] = SLICE_PALETTE[next % SLICE_PALETTE.length];
    next += 1;
  }
  return colors;
}

/** A change in percentage points with an explicit sign and ONE decimal (the server rounds to one): `+3.5`, `−2.0`, `0.0`. */
export function formatDeltaPoints(points: number): string {
  const text = Math.abs(points).toFixed(1);
  if (text === "0.0") return text;
  return `${points > 0 ? "+" : "−"}${text}`;
}

/** The targets table's rows that have a delta to draw, as the diverging chart plots them. */
export interface DeltaBar {
  key: string;
  label: string;
  delta: number;
}

export function deltaBars(rows: readonly CostsCategoryTargetRow[], t: TFunction): DeltaBar[] {
  return rows.flatMap((row) => (row.deltaPoints === null ? [] : [{ key: row.key, label: categoryLabel(t, row.key), delta: row.deltaPoints }]));
}

/** The value axis of the diverging chart: symmetric about zero (so the zero line is centred), in steps of 5 points, at least ±5. */
export function deltaAxisMax(bars: readonly DeltaBar[]): number {
  const largest = bars.reduce((max, bar) => Math.max(max, Math.abs(bar.delta)), 0);
  return Math.max(5, Math.ceil(largest / 5) * 5);
}

/** One line of the trend chart: Mantine reads a series by a data key, and a category label may hold anything (a dot is a path), so the key is positional. */
export interface TrendSeries {
  name: string;
  key: string;
  label: string;
  color: string;
  /** The hue was already worn by an earlier line, so form tells this one apart. */
  dashed: boolean;
}

/** The lines of the trend chart: every category of the first week (the server sends the same keys, in the same order, in every week). */
export function trendSeries(trend: CostsCategoryTrend, colors: Record<string, string>, t: TFunction): TrendSeries[] {
  const worn = new Set<string>();
  return (trend.weeks[0]?.byCategory ?? []).map((category, index) => {
    const color = colors[category.key];
    const dashed = worn.has(color);
    worn.add(color);
    return { name: `c${index}`, key: category.key, label: categoryLabel(t, category.key), color, dashed };
  });
}

/** A chart row per week: the week's Monday and each category's cumulative share in percent (null while nothing has been logged, a gap). */
export function trendChartRows(trend: CostsCategoryTrend, series: readonly TrendSeries[]): Array<Record<string, string | number | null>> {
  return trend.weeks.map((week) => {
    const row: Record<string, string | number | null> = { week: week.weekStart };
    for (const line of series) {
      const share = week.byCategory.find((category) => category.key === line.key)?.share ?? null;
      row[line.name] = share === null ? null : share * 100;
    }
    return row;
  });
}
