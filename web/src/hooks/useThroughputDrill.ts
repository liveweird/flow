import type { ThroughputSprint } from "../api/reports";
import type { ChartBarHit } from "../utils/chartDrill";
import type { SprintBucket } from "../utils/reportDrill";
import { sprintOfRow, sprintTarget } from "../utils/sprintDrill";
import { COST_SERIES, type ThroughputPerson, type ThroughputShareRow } from "../utils/throughputReport";
import { useReportDrill } from "./useReportDrill";

/** The period card's focus-return anchor for a drill opened by a chart double-click (`ReportChartCard`'s `drillAnchor`). */
export const PERIOD_DRILL_ANCHOR = "throughput-period";
/** The sprint card's anchor: a sprint bar (opened by a double-click or a deep link) returns focus there. */
export const SPRINT_DRILL_ANCHOR = "throughput-sprint";
/** The by-epic card's anchor: a by-epic sprint bar (a double-click, a figure or a deep link) returns focus there. */
export const EPIC_DRILL_ANCHOR = "throughput-epics";

/**
 * Throughput's drill: the page's `useReportDrill` plus every way a bar of its two charts is opened — a chart hit, a table row, a
 * person's share row — as ready-made handlers. While `drillable` is false (kept data of a previous filter: its bars must not open
 * a drill under the new one) the CHART handlers are `undefined`, so the bars stop being clickable, and `drillDisabled` is true: the
 * tables show their figures as plain text.
 */
export function useThroughputDrill(
  filterKey: string | null,
  drillable: boolean,
  periodPeople: readonly ThroughputPerson[],
  sprintPeople: readonly ThroughputPerson[],
) {
  const drill = useReportDrill(filterKey, (target) => {
    if (target.kind === "ac-sprint") return SPRINT_DRILL_ANCHOR;
    if (target.kind !== "sprint") return PERIOD_DRILL_ANCHOR;
    return target.epicSegment === undefined ? SPRINT_DRILL_ANCHOR : EPIC_DRILL_ANCHOR;
  });
  const openBucket = (bucketStart: string, segmentAccountId?: string, trigger?: HTMLElement) =>
    drill.open(segmentAccountId ? { kind: "bucket", bucketStart, segmentAccountId } : { kind: "bucket", bucketStart }, { trigger });
  const openSprint = (rowId: string, segmentAccountId?: string, trigger?: HTMLElement) => {
    const target = sprintTarget(rowId, "DELIVERED", segmentAccountId);
    if (target) drill.open(target, { trigger });
  };
  const openCostBucket = (bucketStart: string, segmentAccountId?: string, trigger?: HTMLElement) =>
    drill.open(segmentAccountId ? { kind: "ac-bucket", bucketStart, segmentAccountId } : { kind: "ac-bucket", bucketStart }, { trigger });
  const openCostSprint = (rowId: string, segmentAccountId?: string, trigger?: HTMLElement) => {
    const sprint = sprintOfRow(rowId);
    if (sprint) drill.open(segmentAccountId ? { kind: "ac-sprint", ...sprint, segmentAccountId } : { kind: "ac-sprint", ...sprint }, { trigger });
  };
  const personOf = (people: readonly ThroughputPerson[], series: string) => people.find((person) => person.key === series)?.accountId;
  /** The series of a chart hit is a person's actual-cost segment (`costKey`) or the team's `ac` bar: the hit is about cost, not delivery. */
  const costHit = (people: readonly ThroughputPerson[], series: string) => series === COST_SERIES || people.some((person) => person.costKey === series);
  const costPersonOf = (people: readonly ThroughputPerson[], series: string) => people.find((person) => person.costKey === series)?.accountId;
  const only = <T>(handler: T): T | undefined => (drillable ? handler : undefined);
  return {
    drill,
    onPeriodBar: only(({ rowId, series }: ChartBarHit) =>
      costHit(periodPeople, series) ? openCostBucket(rowId, costPersonOf(periodPeople, series)) : openBucket(rowId, personOf(periodPeople, series)),
    ),
    drillDisabled: !drillable,
    onPeriodRow: (row: { id: string }, trigger: HTMLElement) => openBucket(row.id, undefined, trigger),
    onPeriodShare: (row: ThroughputShareRow, trigger: HTMLElement) => openBucket(row.slotId, row.person.accountId, trigger),
    onSprintBar: only(({ rowId, series }: ChartBarHit) =>
      costHit(sprintPeople, series) ? openCostSprint(rowId, costPersonOf(sprintPeople, series)) : openSprint(rowId, personOf(sprintPeople, series)),
    ),
    onSprintRow: (sprint: ThroughputSprint, _bucket: SprintBucket, trigger: HTMLElement) =>
      openSprint(`${sprint.teamId}:${sprint.sprintId}`, undefined, trigger),
    onSprintShare: (row: ThroughputShareRow, trigger: HTMLElement) => openSprint(row.slotId, row.person.accountId, trigger),
    /** The actual-cost figures of the tables: a week/month row, a sprint row, and a person's share of either. */
    onPeriodCostRow: (row: { id: string }, trigger: HTMLElement) => openCostBucket(row.id, undefined, trigger),
    onPeriodCostShare: (row: ThroughputShareRow, trigger: HTMLElement) => openCostBucket(row.slotId, row.person.accountId, trigger),
    onSprintCostRow: (sprint: ThroughputSprint, trigger: HTMLElement) => openCostSprint(`${sprint.teamId}:${sprint.sprintId}`, undefined, trigger),
    onSprintCostShare: (row: ThroughputShareRow, trigger: HTMLElement) => openCostSprint(row.slotId, row.person.accountId, trigger),
  };
}
