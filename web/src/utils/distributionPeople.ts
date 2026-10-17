import type { Distribution } from "../api/reports";

/** One picked person of a per-person histogram: `position` (1-based, request order) is the number that tells them apart. */
export interface DistributionPerson {
  accountId: string;
  label: string;
  position: number;
}

/** One histogram bucket as the panel's chart and table read it; `shares` is the per-person split, index-aligned with the people. */
export interface HistogramRowWithShares {
  /** The bucket's index in the response (`String(index)`), what a chart double-click reports. */
  id: string;
  label: string;
  count: number;
  /** The final open bucket (the long tail). */
  open: boolean;
  shares?: number[];
}

/**
 * The people of a distribution's per-person split, numbered 1.. in `byUser` order (the request order). None for a hidden
 * distribution or a report that does not split — the histogram is then drawn plain.
 */
export function distributionPeople(distribution: Distribution): DistributionPerson[] {
  return (distribution.byUser ?? []).map((share, index) => ({ accountId: share.accountId, label: share.label, position: index + 1 }));
}

/**
 * The histogram rows with each bucket's per-person counts (`shares[i]` is person `i`'s count, the tail bucket included). Without
 * a split the rows carry no `shares`, so a plain distribution renders exactly as before.
 */
export function histogramRowsWithShares(distribution: Distribution, labels: readonly string[]): HistogramRowWithShares[] {
  const byUser = distribution.byUser ?? [];
  return distribution.histogram.map((bucket, index) => ({
    id: String(index),
    label: labels[index],
    count: bucket.count,
    open: bucket.to == null,
    ...(byUser.length > 0 ? { shares: byUser.map((share) => share.counts[index] ?? 0) } : {}),
  }));
}
