import type { AgingItem, AgingPosition, AgingThresholds, AgingWipReport } from "../api/reports";
import { bandTone, type BandTone } from "./agingReport";
import { CHART_COLORS } from "./chartColors";

/** The two kinds the aging chart can show — never both on one plot: an epic's cycle is a different scale from a task's. */
export type AgingKind = "TASK" | "EPIC";

/**
 * The dot colour of each band tone, from the app's chart vocabulary (no new hue): red = the top threshold (dropped/blocked
 * work), orange = the next, gray = the rest. Each clears 3:1 on every chart surface (`chartColors.test.ts`).
 */
export const AGING_BAND_COLORS: Record<BandTone, string> = {
  red: CHART_COLORS.dropped,
  orange: CHART_COLORS.carriedOver,
  gray: CHART_COLORS.removed,
};

/** A blocked item wears a red outer ring (the vocabulary's "blocking" red) whatever its band. */
export const AGING_BLOCKED_COLOR = CHART_COLORS.dropped;

/** Radius of a dot's invisible hit target in px (about 26px across: a 12px dot is no pinpoint to land on). */
export const AGING_HIT_RADIUS = 13;

/** How much of a category's width the horizontal jitter spreads dots over (0.6 = ±0.3 around the category's centre). */
export const JITTER_SPAN = 0.6;

/** A Mantine colour name (`"red.7"`) as the CSS variable inline SVG needs. */
export function colorVar(color: string): string {
  return `var(--mantine-color-${color.replace(".", "-")})`;
}

/** The identity of an item across the chart and the table (an issue key is unique per kind of item). */
export function agingItemKey(item: Pick<AgingItem, "itemKind" | "issueKey">): string {
  return `${item.itemKind}:${item.issueKey}`;
}

/**
 * A stable pseudo-random number in [-0.5, 0.5) derived from a string (FNV-1a): the same key always lands on the same
 * offset, in every render and in tests — dots of one column and similar age fan out without `Math.random()`.
 */
export function jitterOf(key: string): number {
  let hash = 0x811c9dc5;
  for (const char of key) hash = Math.imul(hash ^ (char.codePointAt(0) ?? 0), 0x01000193);
  return ((hash >>> 0) % 10007) / 10007 - 0.5;
}

/** One dot: `x` the category index plus its jitter, `y` the age in working days, `i` the index into {@link AgingPlot.items}. */
export interface AgingPlotPoint {
  x: number;
  y: number;
  i: number;
}

export interface AgingPlot {
  kind: AgingKind;
  /** The shown kind's items, in the server's order. */
  items: AgingItem[];
  /** The x-axis categories holding at least one shown item, in the server's order (never re-sorted). */
  categories: AgingPosition[];
  points: AgingPlotPoint[];
}

/** Which kinds the report has items of — the Tasks | Epics switch appears only when both do. */
export function agingKinds(report: Pick<AgingWipReport, "items">): AgingKind[] {
  const kinds = new Set(report.items.map((item) => item.itemKind));
  return (["TASK", "EPIC"] as const).filter((kind) => kinds.has(kind));
}

/**
 * The plot of one kind: its items as points over the server's `positions` (only those holding one of THEM, since the
 * other kind's statuses would be empty columns). A position the server did not list — it cannot happen — gets its own
 * category at the end rather than a dot at a wrong place.
 */
export function agingPlot(report: AgingWipReport, kind: AgingKind): AgingPlot {
  const items = report.items.filter((item) => item.itemKind === kind);
  const used = new Set(items.map((item) => item.position));
  const categories = report.positions.filter((position) => used.has(position.key));
  const known = new Set(categories.map((category) => category.key));
  for (const item of items) {
    if (!known.has(item.position) && !report.positions.some((position) => position.key === item.position)) {
      known.add(item.position);
      categories.push({ key: item.position, label: item.position, kind: "STATUS" });
    }
  }
  const indexOf = new Map(categories.map((category, index) => [category.key, index]));
  const points = items.map((item, i) => ({
    x: (indexOf.get(item.position) ?? 0) + jitterOf(agingItemKey(item)) * JITTER_SPAN,
    y: item.ageWorkingDays,
    i,
  }));
  return { kind, items, categories, points };
}

/** One dashed reference line: a configured threshold, toned by its rank like the table's band badge. */
export interface AgingReferenceLine {
  percentile: number;
  workingDays: number;
  tone: BandTone;
}

/** The thresholds as lines, lowest first — none while they are hidden (below the minimum sample). */
export function agingReferenceLines(thresholds: AgingThresholds): AgingReferenceLine[] {
  if (thresholds.hidden) return [];
  return [...thresholds.percentiles]
    .sort((a, b) => a.percentile - b.percentile)
    .flatMap((entry) =>
      entry.workingDays == null
        ? []
        : [{ percentile: entry.percentile, workingDays: entry.workingDays, tone: bandTone(`P${entry.percentile}`, thresholds) }],
    );
}

/**
 * The top of the y axis: the oldest item or the highest threshold, whichever is higher, plus 6% headroom (so the topmost dot
 * is not cut by the plot's edge), rounded up to a whole day — an outlier is never clipped.
 */
export function agingYMax(plot: AgingPlot, lines: AgingReferenceLine[]): number {
  const top = Math.max(0, ...plot.items.map((item) => item.ageWorkingDays), ...lines.map((line) => line.workingDays));
  return Math.max(1, Math.ceil(top * 1.06));
}

/**
 * The lowest age a log axis can show, in working days: a log axis has no 0, so an age under it is PLOTTED at this floor (the
 * tooltip and the table keep the true age) and the axis starts here.
 */
export const AGING_LOG_FLOOR = 0.5;

/** The readable ticks of a log age axis (1-2-5 steps), kept to those inside the domain. */
const LOG_TICKS = [0.5, 1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000];

/** An age as plotted on the log axis: never below {@link AGING_LOG_FLOOR}. */
export function logPlotted(days: number): number {
  return Math.max(days, AGING_LOG_FLOOR);
}

/** The points with the log floor applied to their `y` (the `i` index still leads back to the true age). */
export function logPlotPoints(points: AgingPlotPoint[]): AgingPlotPoint[] {
  return points.map((point) => ({ ...point, y: logPlotted(point.y) }));
}

/** The log axis: from the floor to the same top as the linear one (the oldest item plus headroom), with 1-2-5 ticks inside. */
export function agingLogAxis(yMax: number): { domain: [number, number]; ticks: number[] } {
  const top = Math.max(yMax, 1);
  return { domain: [AGING_LOG_FLOOR, top], ticks: LOG_TICKS.filter((tick) => tick <= top) };
}
