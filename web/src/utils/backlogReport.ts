import type { BacklogCurrent, BacklogPoint } from "../api/reports";

/** One chart/table row of the backlog trend. */
export interface BacklogRow {
  day: string;
  md: number;
  items: number;
}

export function backlogRows(trend: readonly BacklogPoint[]): BacklogRow[] {
  return trend.map((point) => ({ day: point.day, md: point.md, items: point.items }));
}

/** Why the "in sprints" figure is missing, when it is (the wording is the tile's hint). */
export type SprintsGap = "noVelocity" | "zeroVelocity";

/**
 * `null` when the figure is there. No mean at all = no closed sprint, or a scope with no velocity of
 * its own (the unowned backlog, a domain slice); a mean of exactly 0 leaves the ratio undefined.
 */
export function sprintsGap(current: BacklogCurrent): SprintsGap | null {
  if (current.backlogInSprints != null) return null;
  return current.meanDeliveredMd == null ? "noVelocity" : "zeroVelocity";
}

/** Sprints ahead with one decimal, as a number (the tile's plural key needs a number). */
export function roundSprints(value: number): number {
  return Math.round(value * 10) / 10;
}
