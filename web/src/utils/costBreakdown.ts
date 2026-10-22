import type { TFunction } from "i18next";
import type { CostsReport } from "../api/reports";
import { CATEGORY_PALETTE } from "./chartColors";
import { roundScaled } from "./reportFormat";

/** The two breakdowns the Costs donuts draw, spelled as the API's `by` enum. */
export type CostBreakdownBy = "ACTIVITY_TYPE" | "WORK_CATEGORY";

/** The wire key a worklog with no activity type / work category is listed under. */
const UNCATEGORIZED_KEY = "UNCATEGORIZED";

/** A slice smaller than this share of the total is folded into "Other" (when at least two are). */
const OTHER_SHARE = 0.03;

/**
 * One slice as the page draws it: [keys] is what the drill asks for (one key, or every folded key of "Other"), [md] its man-days
 * (for "Other" the folded slices' EXACT sums added and rounded once, so it equals the drill's bar) and [share] its fraction of the
 * donut's total. [other] marks the folded slice, which the legend lists key by key.
 */
export interface DisplaySlice {
  key: string;
  keys: string[];
  md: number;
  share: number;
  other: boolean;
}

/** A server slice: the figure rounded once, and the exact sum behind it. */
interface RawSlice {
  key: string;
  md: number;
  exactMd: number;
}

/**
 * The server's slices as the donut draws them: slices that show 0 MD are dropped (nothing to show in a whole), every slice under
 * [OTHER_SHARE] of the total is folded into ONE "Other" slice — only when that folds at least two, since a lone small slice is just
 * itself — and "Other" comes last. The server's order (MD descending, then key) is kept for the rest. Shares and the fold line are
 * read from the exact sums, so the shares add up to 100%, and Other's figure is the folded EXACT sums rounded once — the very number
 * the server's drill reports as its bar (two slices of 0.125 show 0.13 each, and Other shows 0.25, not 0.26). An empty or all-zero
 * list gives no slices at all.
 */
export function foldBreakdown(slices: ReadonlyArray<RawSlice>): DisplaySlice[] {
  const positive = slices.filter((slice) => slice.md > 0);
  const total = positive.reduce((sum, slice) => sum + slice.exactMd, 0);
  if (total <= 0) return [];
  const small = positive.filter((slice) => slice.exactMd / total < OTHER_SHARE);
  const folded = small.length >= 2 ? new Set(small.map((slice) => slice.key)) : new Set<string>();
  const kept: DisplaySlice[] = positive
    .filter((slice) => !folded.has(slice.key))
    .map((slice) => ({ key: slice.key, keys: [slice.key], md: slice.md, share: slice.exactMd / total, other: false }));
  if (folded.size === 0) return kept;
  const members = positive.filter((slice) => folded.has(slice.key));
  const exact = members.reduce((sum, slice) => sum + slice.exactMd, 0);
  return [...kept, { key: "", keys: members.map((slice) => slice.key), md: roundScaled(exact) / 100, share: exact / total, other: true }];
}

/**
 * The colours of a donut's slices: the categorical palette by position, WITHOUT its gray entry — gray is reserved for "Other" (the
 * neutral "everything else"), so no named slice can be mistaken for it. From the seventh slice the hues repeat; identity never rests on
 * colour (the legend table beside the donut names every slice). The donut is a ring, so when nothing follows the last named slice
 * (no Other) it touches the first: a wrap that would give them the same hue takes the next one instead.
 */
const SLICE_PALETTE = CATEGORY_PALETTE.filter((colour) => colour !== "gray.6");
export const OTHER_COLOR = "gray.6";

export function sliceColors(named: number, hasOther: boolean): string[] {
  const hues = Array.from({ length: named }, (_, index) => SLICE_PALETTE[index % SLICE_PALETTE.length]);
  const last = named - 1;
  if (!hasOther && named > 1 && hues[last] === hues[0]) hues[last] = SLICE_PALETTE[(last + 1) % SLICE_PALETTE.length];
  return hasOther ? [...hues, OTHER_COLOR] : hues;
}

/** A slice key in words: `UNCATEGORIZED` is the translated "(uncategorized)", any other key is shown as it is. */
export function keyLabel(t: TFunction, key: string): string {
  return key === UNCATEGORIZED_KEY ? t("reports.filters.uncategorized") : key;
}

/**
 * The drawer heading of a cost slice: the slice as the title ("Development") and the breakdown as the dimmed subtitle ("Activity
 * type"); a slice of several keys is the folded "Other" and says how many it holds.
 */
export function breakdownHeading(t: TFunction, target: { by: CostBreakdownBy; keys: readonly string[] }): { title: string; subtitle: string } {
  const by = t(target.by === "ACTIVITY_TYPE" ? "reports.filters.activityType" : "reports.filters.workCategory");
  const name =
    target.keys.length === 1 ? keyLabel(t, target.keys[0]) : t("reports.costs.breakdown.otherCount", { count: target.keys.length });
  return { title: name, subtitle: by };
}

/** The two donuts a report shows: a breakdown whose own filter is set has one slice and no donut. */
export function visibleBreakdowns(
  report: CostsReport,
  filter: { activityType?: string; workCategory?: string },
): Array<{ by: CostBreakdownBy; slices: DisplaySlice[] }> {
  const all: Array<{ by: CostBreakdownBy; set: boolean; raw: ReadonlyArray<RawSlice> }> = [
    { by: "ACTIVITY_TYPE", set: filter.activityType !== undefined, raw: report.byActivityType },
    { by: "WORK_CATEGORY", set: filter.workCategory !== undefined, raw: report.byWorkCategory },
  ];
  return all.filter((entry) => !entry.set).map((entry) => ({ by: entry.by, slices: foldBreakdown(entry.raw) })).filter((entry) => entry.slices.length > 0);
}
