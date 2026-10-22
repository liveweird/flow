import type { CommitmentsSprint, CostsSprint, ReportFilters, ThroughputSprint } from "../api/reports";
import type { ChartBarHit } from "./chartDrill";
import { OTHER_EPICS, orderedEpicSegments, topEpicIds, type EpicChartSegment, type EpicSegmentIdentity } from "./epicSegments";
import type { DrillTarget, SprintBucket } from "./reportDrill";
import { labelledSprints, sprintRowId, type SprintIdentity } from "./reportSprints";
import { sprintTarget } from "./sprintDrill";

/**
 * The by-epic sprint charts' model (Commitments: initial and final scope; Throughput: delivered), pure: the segments in stack order
 * (the union of the sprints' own, `orderedEpicSegments`), one chart row per sprint in completion order and the flat sprint × segment rows
 * of the table beside the chart. A segment a sprint omits (nothing of it in that sprint) counts 0 in the chart.
 */

/** A stack of the chart: Commitments draws two per sprint (initial, final), Throughput one (delivered). */
export interface EpicStack {
  /** The stack's id and the prefix of its data keys (`i0`, `f0`, … / `d0`, …). */
  id: string;
  /** The sprint bucket a segment of this stack drills (`COMMITTED`, `FINAL`, `DELIVERED`); a chart whose bars are no sprint bucket (Costs) has none. */
  bucket?: SprintBucket;
}

/** The data key of a segment in a stack: stack ids are letters, positions digits, so the pair is unambiguous. */
export function epicDataKey(stackId: string, position: number): string {
  return `${stackId}${position}`;
}

/** A plotted row: the sprint's `id` (`sprintRowId`), its `label` and each segment's MD under its data key. */
export type EpicChartRow = { id: string; label: string } & Record<string, number | string>;

/** The segments in stack order and the rows they are plotted in. */
export interface EpicChart {
  segments: EpicChartSegment[];
  rows: EpicChartRow[];
}

/** Commitments' two stacks: initial (COMMITTED) and final (FINAL), cut by the same segments. */
export const COMMITMENTS_STACKS: readonly EpicStack[] = [
  { id: "i", bucket: "COMMITTED" },
  { id: "f", bucket: "FINAL" },
];

/** Throughput's one stack: delivered (DELIVERED). */
export const THROUGHPUT_STACKS: readonly EpicStack[] = [{ id: "d", bucket: "DELIVERED" }];

/** Costs' one stack: the sprint's actual cost, which has no sprint bucket (its drill is `cost-sprint`). */
export const COSTS_STACKS: readonly EpicStack[] = [{ id: "a" }];

/** The generic chart: `figures` reads the MD of a sprint's segment per stack. */
function epicChart<S extends SprintIdentity, G extends EpicSegmentIdentity>(
  sprints: readonly S[],
  filters: ReportFilters,
  segmentsOf: (sprint: S) => readonly G[],
  stacks: ReadonlyArray<EpicStack & { md: (segment: G) => number }>,
  rankBy: (segment: G) => readonly number[],
): EpicChart {
  const labelled = labelledSprints(sprints, filters);
  const totals = new Map<string, number[]>();
  for (const { sprint } of labelled) {
    for (const segment of segmentsOf(sprint)) {
      const sum = totals.get(segment.key) ?? [];
      rankBy(segment).forEach((value, i) => (sum[i] = (sum[i] ?? 0) + value));
      totals.set(segment.key, sum);
    }
  }
  const segments = orderedEpicSegments(
    labelled.map(({ sprint }) => segmentsOf(sprint)),
    (key) => totals.get(key) ?? [],
  );
  const rows = labelled.map(({ sprint, label }) => {
    const row: EpicChartRow = { id: sprintRowId(sprint), label };
    for (const segment of segments) {
      const own = segmentsOf(sprint).find((candidate) => candidate.key === segment.key);
      for (const stack of stacks) row[epicDataKey(stack.id, segment.position)] = own ? stack.md(own) : 0;
    }
    return row;
  });
  return { segments, rows };
}

/** Commitments' chart: per sprint, the initial and the final MD of every epic segment; epics rank by final, then initial MD (the server's). */
export function commitmentsEpicChart(sprints: readonly CommitmentsSprint[], filters: ReportFilters): EpicChart {
  return epicChart(
    sprints,
    filters,
    (sprint) => sprint.byEpic,
    [
      { ...COMMITMENTS_STACKS[0], md: (segment) => segment.initialMd },
      { ...COMMITMENTS_STACKS[1], md: (segment) => segment.finalMd },
    ],
    (segment) => [segment.finalMd, segment.initialMd],
  );
}

/** Throughput's chart: per sprint, the delivered MD of every epic segment. */
export function throughputEpicChart(sprints: readonly ThroughputSprint[], filters: ReportFilters): EpicChart {
  return epicChart(
    sprints,
    filters,
    (sprint) => sprint.byEpic,
    [{ ...THROUGHPUT_STACKS[0], md: (segment) => segment.deliveredMd }],
    (segment) => [segment.deliveredMd],
  );
}

/** Costs' chart: per sprint, the actual cost (AC, MD) of every epic segment. */
export function costsEpicChart(sprints: readonly CostsSprint[], filters: ReportFilters): EpicChart {
  return epicChart(
    sprints,
    filters,
    (sprint) => sprint.byEpic,
    [{ ...COSTS_STACKS[0], md: (segment) => segment.acMd }],
    (segment) => [segment.acMd],
  );
}

/** Whether any sprint has a by-epic segment — otherwise the section has nothing to draw and stays out of the page. */
export function hasEpicSegments(sprints: ReadonlyArray<{ byEpic: readonly unknown[] }>): boolean {
  return sprints.some((sprint) => sprint.byEpic.length > 0);
}

/** One flat row of the table beside a by-epic chart: one sprint's share in one segment. */
export interface EpicTableRow<S, G> {
  key: string;
  sprint: S;
  /** The sprint as the chart labels it (team-prefixed where several teams share the chart). */
  label: string;
  segment: G;
}

/** The sprint × segment rows, sprints in completion order, segments in stack order. */
export function epicTableRows<S extends SprintIdentity, G extends EpicSegmentIdentity>(
  sprints: readonly S[],
  filters: ReportFilters,
  segmentsOf: (sprint: S) => readonly G[],
  order: ReadonlyArray<EpicChartSegment>,
): EpicTableRow<S, G>[] {
  const position = new Map(order.map((segment) => [segment.key, segment.position]));
  return labelledSprints(sprints, filters).flatMap(({ sprint, label }) =>
    [...segmentsOf(sprint)]
      .sort((a, b) => (position.get(a.key) ?? 0) - (position.get(b.key) ?? 0))
      .map((segment) => ({ key: `${sprintRowId(sprint)}:${segment.key}`, sprint, label, segment })),
  );
}

/** What a by-epic section turns a clicked segment into: the target of its drill (Commitments' and Throughput's sprint buckets by default, Costs' own). */
export type EpicSegmentTarget = (
  rowId: string,
  bucket: SprintBucket | undefined,
  segment: Pick<EpicSegmentIdentity, "key">,
  segments: ReadonlyArray<EpicSegmentIdentity>,
) => DrillTarget | null;

/**
 * The target of a by-epic segment: the sprint's bucket narrowed to the segment (`epicSegment`), plus — for "Other epics" — the chart's own
 * top epics, which tell the server which epics are NOT the folded rest. A stack with no bucket drills nothing here.
 */
export const epicSegmentTarget: EpicSegmentTarget = (rowId, bucket, segment, segments) => {
  if (bucket === undefined) return null;
  const base = sprintTarget(rowId, bucket);
  if (base === null || base.kind !== "sprint") return null;
  const tops = segment.key === OTHER_EPICS ? topEpicIds(segments) : undefined;
  return { ...base, epicSegment: segment.key, ...(tops === undefined ? {} : { topEpicIds: tops }) };
};

/** What a double-click on a by-epic chart opens: the data key names a stack (its bucket) and a segment, the row names the sprint. */
export function epicHitTarget(
  hit: ChartBarHit,
  segments: ReadonlyArray<EpicChartSegment>,
  stacks: ReadonlyArray<EpicStack>,
  toTarget: EpicSegmentTarget = epicSegmentTarget,
): DrillTarget | null {
  for (const stack of stacks) {
    for (const segment of segments) {
      if (epicDataKey(stack.id, segment.position) === hit.series) return toTarget(hit.rowId, stack.bucket, segment, segments);
    }
  }
  return null;
}

/** The segment an `epicSegment` value names, as the report drew it (the drawer's title), by key; `undefined` while the report is not in hand or does not list it. */
export function epicLabeller(
  sprints: ReadonlyArray<{ byEpic: readonly EpicSegmentIdentity[] }> | undefined,
): (key: string) => EpicSegmentIdentity | undefined {
  return (key) => sprints?.flatMap((sprint) => sprint.byEpic).find((segment) => segment.key === key);
}
