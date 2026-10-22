import { skipToken, useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { getThroughputCostItems } from "../api/reportDrill";
import type { ThroughputBucketRow, ThroughputSprint } from "../api/reports";
import { serializeDrill, type ActualCostBucketTarget, type ActualCostSprintTarget, type DrillTarget } from "../utils/reportDrill";
import { reportQuery, type Bucket, type ReportFilterState } from "../utils/reportFilter";
import { bucketLabel, type ThroughputPerson } from "../utils/throughputReport";
import ReportDrillDrawer from "./ReportDrillDrawer";

/** The actual cost the chart drew for the open bar (or one person's share of it): the figure the drawer's recomputed total is held against. */
function chartCost(
  target: ActualCostBucketTarget | ActualCostSprintTarget,
  byBucket: readonly ThroughputBucketRow[],
  bySprint: readonly ThroughputSprint[] | undefined,
): number | undefined {
  const row =
    target.kind === "ac-bucket"
      ? byBucket.find((candidate) => candidate.bucketStart === target.bucketStart)
      : bySprint?.find((candidate) => candidate.teamId === target.teamId && candidate.sprintId === target.sprintId);
  if (!row) return undefined;
  return target.segmentAccountId ? row.byUser.find((share) => share.accountId === target.segmentAccountId)?.actualCostMd : row.actualCostMd;
}

/**
 * Throughput's ACTUAL COST drill drawer: the tasks the time of one bar of the period chart (a week or month) or of the sprint chart is
 * logged on, fetched from `GET /reports/throughput/items?measure=ACTUAL_COST` with the SAME filter the shown report was requested with (a
 * sprint bar with the period narrowed to that sprint, like the delivered sprint drill). One row per task; the figure column is the MD
 * logged on it inside the bar and the drawer's summary the server's recomputed total, held against the chart's `actualCostMd`
 * (`chartMd`) so a refreshed dataset is announced rather than silently different. With people picked, a double-clicked cost segment (or
 * a share-table figure) opens that person's own logged time and the drawer's select moves between them.
 */
export default function ThroughputCostDrillDrawer({
  opened,
  target,
  filter,
  bucket,
  byBucket,
  bySprint,
  sprintLabel,
  periodPeople = [],
  sprintPeople = [],
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
  bySprint: readonly ThroughputSprint[] | undefined;
  /** The sprint's name as the chart labels it; `undefined` while the report is not in hand. */
  sprintLabel: (sprint: { teamId: number; sprintId: number }) => string | undefined;
  /** The picked people of each chart, for the select (the sprint view's when the page has no period buckets). */
  periodPeople?: readonly ThroughputPerson[];
  sprintPeople?: readonly ThroughputPerson[];
  timeZone: string;
  onClose: () => void;
  onSegmentChange: (accountId: string | undefined) => void;
}) {
  const { t } = useTranslation();
  const open = target?.kind === "ac-bucket" || target?.kind === "ac-sprint" ? target : null;
  const query = useQuery({
    queryKey: ["reports", "throughput", "items", reportQuery(filter), open ? serializeDrill(open) : null],
    // Closed means no request (a filter change during the exit transition must not fetch); the last answer stays cached.
    queryFn: opened && open ? () => getThroughputCostItems(filter, open) : skipToken,
  });
  let subtitle: string | undefined;
  if (open?.kind === "ac-bucket") subtitle = bucketLabel(open.bucketStart, bucket);
  else if (open) subtitle = sprintLabel(open) ?? t("reports.drill.sprintFallback", { id: open.sprintId });
  return (
    <ReportDrillDrawer
      opened={opened && open !== null}
      onClose={onClose}
      title={open ? t("reports.drill.actualCost") : ""}
      subtitle={subtitle}
      query={query}
      unit="loggedMd"
      anchor="logged"
      timeZone={timeZone}
      chartMd={open ? chartCost(open, byBucket, bySprint) : undefined}
      people={open?.kind === "ac-sprint" ? sprintPeople : periodPeople}
      segmentAccountId={open?.segmentAccountId}
      onSegmentChange={onSegmentChange}
    />
  );
}
