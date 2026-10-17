import type { ThroughputSprint } from "../api/reports";
import type { ChartBarHit } from "../utils/chartDrill";
import type { SprintBucket } from "../utils/reportDrill";
import { sprintTarget } from "../utils/sprintDrill";
import type { ThroughputPerson, ThroughputShareRow } from "../utils/throughputReport";
import { useReportDrill } from "./useReportDrill";

/** The period card's focus-return anchor for a drill opened by a chart double-click (`ReportChartCard`'s `drillAnchor`). */
export const PERIOD_DRILL_ANCHOR = "throughput-period";
/** The sprint card's anchor: a sprint bar (opened by a double-click or a deep link) returns focus there. */
export const SPRINT_DRILL_ANCHOR = "throughput-sprint";

/**
 * Throughput's drill: the page's `useReportDrill` plus every way a bar of its two charts is opened — a chart hit, a table row, a
 * person's share row — as ready-made handlers. While `drillable` is false (kept data of a previous filter: its bars must not open
 * a drill under the new one) the CHART handlers are `undefined`, so the bars stop being clickable, and `drillDisabled` is true: the
 * tables keep their Items column (the layout must not jump) with the buttons disabled.
 */
export function useThroughputDrill(
  filterKey: string,
  drillable: boolean,
  periodPeople: readonly ThroughputPerson[],
  sprintPeople: readonly ThroughputPerson[],
) {
  const drill = useReportDrill(filterKey, (target) => (target.kind === "sprint" ? SPRINT_DRILL_ANCHOR : PERIOD_DRILL_ANCHOR));
  const openBucket = (bucketStart: string, segmentAccountId?: string, trigger?: HTMLElement) =>
    drill.open(segmentAccountId ? { kind: "bucket", bucketStart, segmentAccountId } : { kind: "bucket", bucketStart }, { trigger });
  const openSprint = (rowId: string, segmentAccountId?: string, trigger?: HTMLElement) => {
    const target = sprintTarget(rowId, "DELIVERED", segmentAccountId);
    if (target) drill.open(target, { trigger });
  };
  const personOf = (people: readonly ThroughputPerson[], series: string) => people.find((person) => person.key === series)?.accountId;
  const only = <T>(handler: T): T | undefined => (drillable ? handler : undefined);
  return {
    drill,
    onPeriodBar: only(({ rowId, series }: ChartBarHit) => openBucket(rowId, personOf(periodPeople, series))),
    drillDisabled: !drillable,
    onPeriodRow: (row: { id: string }, trigger: HTMLElement) => openBucket(row.id, undefined, trigger),
    onPeriodShare: (row: ThroughputShareRow, trigger: HTMLElement) => openBucket(row.slotId, row.person.accountId, trigger),
    onSprintBar: only(({ rowId, series }: ChartBarHit) => openSprint(rowId, personOf(sprintPeople, series))),
    onSprintRow: (sprint: ThroughputSprint, _bucket: SprintBucket, trigger: HTMLElement) =>
      openSprint(`${sprint.teamId}:${sprint.sprintId}`, undefined, trigger),
    onSprintShare: (row: ThroughputShareRow, trigger: HTMLElement) => openSprint(row.slotId, row.person.accountId, trigger),
  };
}
