import { skipToken, useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { getThroughputItems } from "../api/reportDrill";
import type { ThroughputBucketRow } from "../api/reports";
import { serializeDrill, type DrillTarget } from "../utils/reportDrill";
import { reportQuery, type Bucket, type ReportFilterState } from "../utils/reportFilter";
import { bucketLabel, type ThroughputPerson } from "../utils/throughputReport";
import ReportDrillDrawer from "./ReportDrillDrawer";

/**
 * Throughput's drill drawer: the tasks behind one bar of the period chart (a week or month), fetched from
 * `GET /reports/throughput/items` with the SAME filter the shown report was requested with (so the list and the bar are one
 * computation) and compared with the row the chart drew, so a refreshed dataset is announced rather than silently different.
 * With people picked, a double-clicked segment (or a share-table row) opens that person's share and the drawer's select moves
 * between them.
 */
export default function ThroughputDrillDrawer({
  opened,
  target,
  filter,
  bucket,
  byBucket,
  people,
  timeZone,
  onClose,
  onSegmentChange,
}: {
  /** Whether the drill is open; `target` is the hook's `shown` (the last bar, kept through the drawer's exit transition). */
  opened: boolean;
  target: DrillTarget | null;
  filter: ReportFilterState;
  /** The resolution of the SHOWN report (it travels with the data, not the filter). */
  bucket: Bucket;
  byBucket: readonly ThroughputBucketRow[];
  people: readonly ThroughputPerson[];
  timeZone: string;
  onClose: () => void;
  onSegmentChange: (accountId: string | undefined) => void;
}) {
  const { t } = useTranslation();
  const query = useQuery({
    queryKey: ["reports", "throughput", "items", reportQuery(filter), target ? serializeDrill(target) : null],
    // Closed means no request (a filter change during the exit transition must not fetch); the last answer stays cached.
    queryFn: opened && target ? () => getThroughputItems(filter, target) : skipToken,
  });
  const row = target ? byBucket.find((candidate) => candidate.bucketStart === target.bucketStart) : undefined;
  const chartCount = target?.segmentAccountId
    ? row?.byUser.find((share) => share.accountId === target.segmentAccountId)?.deliveredItems
    : row?.deliveredItems;
  return (
    <ReportDrillDrawer
      opened={opened}
      onClose={onClose}
      title={target ? t("reports.drill.title.bucket", { label: bucketLabel(target.bucketStart, bucket) }) : ""}
      query={query}
      unit="md"
      anchor="done"
      timeZone={timeZone}
      chartCount={chartCount}
      people={people}
      segmentAccountId={target?.segmentAccountId}
      onSegmentChange={onSegmentChange}
    />
  );
}
