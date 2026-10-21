import type { TFunction } from "i18next";
import { EPIC_PALETTE, NO_EPIC_COLOR, OTHER_EPICS_COLOR } from "./chartColors";

/** The two segments of a by-epic chart that are no epic of their own: the folded rest and the work without an epic (the drill's `epicSegment` values). */
export const OTHER_EPICS = "OTHER_EPICS";
export const NO_EPIC = "NO_EPIC";

/**
 * One segment of a by-epic chart as the server names it (`byEpic[]` of Commitments and Throughput, without its figures): `key` is the
 * epic's issue id, `OTHER_EPICS` or `NO_EPIC`; `epicId`/`epicKey` only on an epic's own segment; `label` is "KEY Summary" for an epic and an
 * English fallback for the two special keys, which the page localises by `key` ({@link epicSegmentName}).
 */
export interface EpicSegmentIdentity {
  key: string;
  epicId: number | null;
  epicKey: string | null;
  label: string;
}

/** A segment of one chart, in stack order (`position` 0 = the bottom of the stack, the biggest epic). */
export interface EpicChartSegment extends EpicSegmentIdentity {
  position: number;
}

/** The words for a segment: the epic's "KEY Summary", or the localised "Other epics" / "No epic". */
export function epicSegmentName(t: TFunction, segment: Pick<EpicSegmentIdentity, "key" | "label">): string {
  if (segment.key === OTHER_EPICS) return t("reports.epics.otherEpics");
  if (segment.key === NO_EPIC) return t("reports.epics.noEpic");
  return segment.label;
}

/** The name a drill drawer gives an `epicSegment` value: the segment's own name when the report is in hand, else a fixed fallback. */
export function epicSegmentKeyName(t: TFunction, key: string, known: Pick<EpicSegmentIdentity, "key" | "label"> | undefined): string {
  if (known !== undefined) return epicSegmentName(t, known);
  return epicSegmentName(t, { key, label: t("reports.epics.epicFallback", { id: key }) });
}

/** The summary text of an epic's "KEY Summary" label once the key is taken off (an epic without a summary is the key alone). */
export function epicSummary(segment: Pick<EpicSegmentIdentity, "epicKey" | "label">): string {
  const { epicKey, label } = segment;
  if (epicKey === null || !label.startsWith(epicKey)) return label;
  return label.slice(epicKey.length).trim();
}

const rank = (key: string) => (key === NO_EPIC ? 2 : key === OTHER_EPICS ? 1 : 0);

/** Where a segment new to the union goes: right after the one before it in its row, else right before the first one after it that is known, else last. */
function insertionPoint(order: ReadonlyArray<EpicSegmentIdentity>, row: ReadonlyArray<EpicSegmentIdentity>, index: number, previous: number): number {
  if (previous >= 0) return previous + 1;
  const later = new Set(row.slice(index + 1).map((segment) => segment.key));
  const successor = order.findIndex((candidate) => later.has(candidate.key));
  return successor >= 0 ? successor : order.length;
}

/**
 * The segments of a chart in stack order. Every sprint row lists its segments in the same order (the top epics by rank, then "Other
 * epics", then "No epic"; the ones with nothing in that sprint omitted), so the order is the union of the rows' lists, and the two special
 * segments always come last.
 */
export function orderedEpicSegments(rows: ReadonlyArray<ReadonlyArray<EpicSegmentIdentity>>): EpicChartSegment[] {
  const order: EpicSegmentIdentity[] = [];
  for (const row of rows) {
    let previous = -1;
    row.forEach((segment, index) => {
      const known = order.findIndex((candidate) => candidate.key === segment.key);
      if (known >= 0) {
        previous = known;
        return;
      }
      previous = insertionPoint(order, row, index, previous);
      order.splice(previous, 0, segment);
    });
  }
  // Stable: the specials sink to the end whatever the union said, "Other epics" before "No epic".
  return order
    .map((segment, index) => ({ segment, index }))
    .sort((a, b) => rank(a.segment.key) - rank(b.segment.key) || a.index - b.index)
    .map(({ segment }, position) => ({ ...segment, position }));
}

/** The epic ids of a chart's own segments (at most six): what a drill of "Other epics" sends, so the server knows which epics are NOT the rest. */
export function topEpicIds(segments: ReadonlyArray<EpicSegmentIdentity>): number[] {
  return [...new Set(segments.flatMap((segment) => (segment.epicId === null ? [] : [segment.epicId])))];
}

/**
 * A segment's colour: the Nth top epic wears the Nth colour of {@link EPIC_PALETTE} (so it keeps it in every sprint of the chart),
 * "Other epics" the palette's neutral gray, "No epic" its own neutral — per scheme, as no single shade clears 3:1 on both.
 */
export function epicSegmentColour(segment: Pick<EpicChartSegment, "key" | "position">, scheme: "light" | "dark"): string {
  if (segment.key === OTHER_EPICS) return OTHER_EPICS_COLOR;
  if (segment.key === NO_EPIC) return NO_EPIC_COLOR[scheme];
  return EPIC_PALETTE[segment.position % EPIC_PALETTE.length];
}
