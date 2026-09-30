import type { DataQualitySnapshotDrift, ReportFilters } from "../api/reports";
import { dropReportSpecific, type ReportFilterState } from "./reportFilter";
import { dataQualityPath, reportSpecificKeep } from "./reportLinks";
import { formatMd, formatSignedMd, teamLabel } from "./reportFormat";

/**
 * Makes a filter the data quality report can answer, and says what its page can show. The report
 * reads an activity type or work category, but the page has no control for those two — so, like
 * every param the page cannot show or clear, both of them (and the other reports' own `bucket`,
 * `by`, `itemKind`, `epicId`) are dropped off the request AND the URL.
 */
export function normalizeDataQualityFilter(filter: ReportFilterState): ReportFilterState {
  return dropReportSpecific(filter, reportSpecificKeep(dataQualityPath));
}

/** The DOM id of a finding's card (`dq-<card>`): the overview tiles link and move focus to it. */
export const cardAnchor = (card: string) => `dq-${card}`;

/** How many more matched than the capped list carries ("and N more"); 0 when the list is complete. */
export function moreCount(list: { total: number; items: ReadonlyArray<unknown> }): number {
  return Math.max(0, list.total - list.items.length);
}

/** A connection's name from the reference data; its id when it is no longer listed. */
export function connectionName(filters: ReportFilters, connectionId: number): string {
  return filters.connections.find((connection) => connection.id === connectionId)?.name ?? `#${connectionId}`;
}

/** A team's name from the reference data; the given text for the unassigned bucket (`null`), its id when it is no longer listed. */
export function teamName(filters: ReportFilters, teamId: number | null, unassigned: string): string {
  if (teamId === null) return unassigned;
  return teamLabel(teamId, filters.teams);
}

type DriftField = DataQualitySnapshotDrift["field"];
type DriftUnit = "md" | "items" | "load";

/** What a drifted sprint figure is counted in: man-days, items, or the capacity load (a fraction). */
function driftUnit(field: DriftField): DriftUnit {
  if (field === "load") return "load";
  return field.endsWith("Items") ? "items" : "md";
}

const roundTo = (value: number, decimals: number) => {
  const factor = 10 ** decimals;
  return Math.round(value * factor) / factor;
};

/** A live or frozen figure in its own unit; a dash when that side is null. The load keeps three decimals (the drift threshold is 0.0005). */
export function formatDriftValue(field: DriftField, value: number | null): string {
  if (value === null) return "—";
  switch (driftUnit(field)) {
    case "md":
      return formatMd(value);
    case "items":
      return String(value);
    case "load":
      return String(roundTo(value, 3));
  }
}

/** `live − frozen` with an explicit sign (a true minus), in the figure's own unit; a dash when either side is null. */
export function formatDriftDelta(field: DriftField, delta: number | null): string {
  if (delta === null) return "—";
  switch (driftUnit(field)) {
    case "md":
      return formatSignedMd(delta);
    case "items":
      return delta === 0 ? "0" : `${delta > 0 ? "+" : "−"}${Math.abs(delta)}`;
    case "load": {
      const rounded = roundTo(delta, 3);
      if (rounded === 0) return "0";
      return `${rounded > 0 ? "+" : "−"}${Math.abs(rounded)}`;
    }
  }
}
