import type { CycleTimeTrendBucket } from "../api/reports";
import type { Bucket } from "./reportFilter";
import { bucketLabel } from "./throughputReport";

export interface CycleTrendRow {
  label: string;
  /** null = the bucket has too few items: a GAP in the line, never a zero. */
  p50: number | null;
  p90: number | null;
  n: number;
}

/** One row per week/month; a hidden bucket keeps its `null`s so the chart breaks the line there. */
export function cycleTrendRows(trend: readonly CycleTimeTrendBucket[], bucket: Bucket): CycleTrendRow[] {
  return trend.map((b) => ({ label: bucketLabel(b.bucketStart, bucket), p50: b.p50, p90: b.p90, n: b.n }));
}

/** Whether ANY bucket has enough items to plot — otherwise the chart would be an empty frame. */
export function trendHasPoints(rows: readonly CycleTrendRow[]): boolean {
  return rows.some((row) => row.p50 !== null || row.p90 !== null);
}
