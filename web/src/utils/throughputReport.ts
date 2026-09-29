import type { ThroughputBucketRow } from "../api/reports";
import type { Bucket } from "./reportFilter";

export interface ThroughputChartRow {
  label: string;
  deliveredMd: number;
  deliveredItems: number;
}

/** One row per bucket: a week is labelled by its Monday (`2026-09-28`), a month by `2026-09`. */
export function throughputChartRows(buckets: readonly ThroughputBucketRow[], bucket: Bucket): ThroughputChartRow[] {
  return buckets.map((row) => ({
    label: bucket === "MONTH" ? row.bucketStart.slice(0, 7) : row.bucketStart,
    deliveredMd: row.deliveredMd,
    deliveredItems: row.deliveredItems,
  }));
}

/** The period view's totals — the text alternative to the chart, and the "nothing delivered" test. */
export function throughputTotals(buckets: readonly ThroughputBucketRow[]): { md: number; items: number } {
  return buckets.reduce((sum, row) => ({ md: sum.md + row.deliveredMd, items: sum.items + row.deliveredItems }), { md: 0, items: 0 });
}
