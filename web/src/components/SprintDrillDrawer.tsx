import { skipToken, useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import type { ReportDrillItems } from "../api/reportDrill";
import { epicSegmentKeyName, type EpicSegmentIdentity } from "../utils/epicSegments";
import { serializeDrill, type DrillTarget } from "../utils/reportDrill";
import { reportQuery, type ReportFilterState } from "../utils/reportFilter";
import ReportDrillDrawer, { type ReportDrillDrawerProps } from "./ReportDrillDrawer";

type SprintTarget = Extract<DrillTarget, { kind: "sprint" }>;

/**
 * The drill drawer of a SPRINT bar — one bucket of one sprint (Commitments' committed/final, Throughput's delivered, Home's
 * three bars). The list is fetched with the SAME filter the shown report was requested with, its period narrowed to that
 * sprint (`fetchItems` decides which endpoint answers; `scope` keys the cache), and its server-recomputed bar is compared with
 * the figure the chart drew (`chartCount`), so a refreshed dataset is announced rather than silently different. With people
 * picked, a double-clicked segment (or a share-table row) opens that person's share and the drawer's select moves between them.
 */
export default function SprintDrillDrawer({
  opened,
  target,
  filter,
  scope,
  fetchItems,
  sprintLabel,
  epicLabel,
  caption,
  chartCount,
  people,
  timeZone,
  onClose,
  onSegmentChange,
}: {
  /** Whether the drill is open; `target` is the hook's `shown` (the last bar, kept through the drawer's exit transition). */
  opened: boolean;
  target: DrillTarget | null;
  filter: ReportFilterState;
  /** The endpoint family, part of the query key (`sprint-consistency`, `throughput`). */
  scope: string;
  fetchItems: (filter: ReportFilterState, target: SprintTarget) => Promise<ReportDrillItems>;
  /** The sprint's name as the chart labels it; `undefined` while the report is not (or no longer) in hand. */
  sprintLabel: (sprint: { teamId: number; sprintId: number }) => string | undefined;
  /** The by-epic segment a drilled `epicSegment` names, as the chart drew it (for the title); `undefined` = not in hand, a fixed fallback names it. */
  epicLabel?: (key: string) => EpicSegmentIdentity | undefined;
  /** A dimmed line under the title (the page's own note about the figures, repeated where the list is read). */
  caption?: string;
  /** The item count the chart (or its table) shows for the open bar and segment; `undefined` = unknown. */
  chartCount: (target: SprintTarget) => number | undefined;
  people?: ReportDrillDrawerProps["people"];
  timeZone: string;
  onClose: () => void;
  onSegmentChange: (accountId: string | undefined) => void;
}) {
  const { t } = useTranslation();
  const open = target?.kind === "sprint" ? target : null;
  const bucket = open ? t(`reports.drill.bucket.${open.bucket}`) : "";
  const title = open?.epicSegment === undefined ? bucket : t("reports.drill.epicTitle", { bucket, epic: epicSegmentKeyName(t, open.epicSegment, epicLabel?.(open.epicSegment)) });
  const query = useQuery({
    queryKey: ["reports", scope, "items", reportQuery(filter), open ? serializeDrill(open) : null],
    // Closed means no request (a filter change during the exit transition must not fetch); the last answer stays cached.
    queryFn: opened && open ? () => fetchItems(filter, open) : skipToken,
  });
  return (
    <ReportDrillDrawer
      opened={opened && open !== null}
      onClose={onClose}
      title={title}
      subtitle={open ? (sprintLabel(open) ?? t("reports.drill.sprintFallback", { id: open.sprintId })) : undefined}
      caption={caption}
      query={query}
      unit="estimateMd"
      anchor="completed"
      timeZone={timeZone}
      chartCount={open ? chartCount(open) : undefined}
      // A by-epic segment is the sum of the picked people: there is no person to narrow it to (the chart's figure is not split both ways).
      people={open?.epicSegment === undefined ? people : undefined}
      segmentAccountId={open?.segmentAccountId}
      onSegmentChange={onSegmentChange}
    />
  );
}
