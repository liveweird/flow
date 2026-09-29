import type { AgingItem, AgingThresholds, AgingWipReport } from "../api/reports";

/** The badge colour of an item's band: red = above the highest threshold, orange = the next one down, gray = anything else. */
export type BandTone = "red" | "orange" | "gray";

/**
 * How loudly a band the SERVER assigned is drawn. The band itself is the server's (`"P85"`,
 * `"WITHIN"`, `null` while the thresholds are hidden) — it is never recomputed from the age here.
 * Only its RANK among the configured thresholds is read: with the default 50/85/95, above p95 is
 * red and above p85 (below p95) orange; with other percentiles the top two thresholds keep those
 * two tones and lower ones stay quiet.
 */
export function bandTone(band: string | null | undefined, thresholds: AgingThresholds): BandTone {
  if (band == null) return "gray";
  const ranked = [...thresholds.percentiles].sort((a, b) => a.percentile - b.percentile);
  const rank = ranked.findIndex((entry) => `P${entry.percentile}` === band);
  if (rank < 0) return "gray";
  if (rank === ranked.length - 1) return "red";
  return rank === ranked.length - 2 ? "orange" : "gray";
}

/** "P85" → "p85": the label a band's threshold goes by. */
export function bandPercentileLabel(band: string): string {
  return band.toLowerCase();
}

/** The thresholds an item's band is judged against: its own kind's (an epic's cycle is another scale). */
export function thresholdsFor(item: AgingItem, report: Pick<AgingWipReport, "thresholds" | "epicThresholds">): AgingThresholds {
  return item.itemKind === "EPIC" ? report.epicThresholds : report.thresholds;
}
