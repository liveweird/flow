import { useEffect } from "react";
import { skipToken, useQuery } from "@tanstack/react-query";
import type { ReportDrillItems } from "../api/reportDrill";
import type { CodeActivityReport, Distribution } from "../api/reports";
import type { DistributionPerson } from "../utils/distributionPeople";
import { serializeDrill, type DrillAnchor, type DrillTarget, type DrillUnit, type HistDrillTarget } from "../utils/reportDrill";
import { reportQuery, type ReportFilterState } from "../utils/reportFilter";
import { histogramLabels, type ValueFormat } from "../utils/reportFormat";
import ReportDrillDrawer from "./ReportDrillDrawer";

/** One drillable histogram of a page: the server's `view` name, the shown distribution and how its values read. */
export interface DistributionDrillView {
  /** The `view` the items endpoint knows this histogram by (`WORKING_DAYS`, `RATIO`, …). */
  view: string;
  /** What the distribution is called in the drawer's title ("Working days"). */
  title: string;
  distribution: Distribution;
  format: ValueFormat;
  unit: DrillUnit;
  /** The picked people of this histogram's per-person split (numbered), none below USER level. */
  people: readonly DistributionPerson[];
}

/**
 * The drill drawer of a distribution report: the items behind one bar of one of the page's histograms. `views` are the
 * histograms the SHOWN report drew; the `drill` param names one by its `view` and a bar by its index, and a link to a view the
 * page has not drawn (yet) opens nothing. The list is fetched with the SAME filter the report was requested with, so it is the
 * bar's own computation; the histogram's count (or the person's share of it) is passed as `chartCount`, so a refreshed dataset is
 * announced rather than silently different. With people picked, the drawer's select moves between their segments.
 */
export default function DistributionDrillDrawer({
  report,
  fetchItems,
  opened,
  target,
  views,
  filter,
  timeZone,
  onClose,
  onSegmentChange,
  segmentRequired = false,
  anchor = "done",
  codeSources,
}: {
  /** The report's path segment: the query-key family (`cycle-time`, `reported-time-ratio`). */
  report: string;
  fetchItems: (filter: ReportFilterState, target: HistDrillTarget) => Promise<ReportDrillItems>;
  /** Whether the drill is open; `target` is the hook's `shown` (the last bar, kept through the drawer's exit transition). */
  opened: boolean;
  target: DrillTarget | null;
  views: readonly DistributionDrillView[];
  filter: ReportFilterState;
  timeZone: string;
  onClose: () => void;
  onSegmentChange: (accountId: string | undefined) => void;
  /** Every bar is one person's (Compare people): the drawer's person select has no "Everyone". */
  segmentRequired?: boolean;
  /** The instant the date column shows (default: when the item was done). */
  anchor?: DrillAnchor;
  /** The lists are Code activity's: keys link to these sources' GitLab (see `ReportDrillDrawer`). */
  codeSources?: CodeActivityReport["sources"];
}) {
  const current = target?.kind === "hist" ? views.find((candidate) => candidate.view === target.view) : undefined;
  // The bar the SHOWN report drew: none for a view the page did not draw, a hidden distribution (no histogram, so no bar to list)
  // or an index past the last bucket. Such a link opens nothing — and, once the report is in hand, is dropped from the URL.
  const bucket = target?.kind === "hist" && current ? current.distribution.histogram[target.bucketIndex] : undefined;
  const open = target?.kind === "hist" && bucket !== undefined ? target : null;
  const stale = opened && target?.kind === "hist" && views.length > 0 && open === null;
  useEffect(() => {
    if (stale) onClose();
  }, [stale, onClose]);
  const query = useQuery({
    queryKey: ["reports", report, "items", reportQuery(filter), open ? serializeDrill(open) : null],
    // Closed means no request (a filter change during the exit transition must not fetch); the last answer stays cached.
    queryFn: opened && open ? () => fetchItems(filter, open) : skipToken,
  });
  const label = open && current ? histogramLabels(current.distribution.histogram, current.format, current.distribution.bucketWidth)[open.bucketIndex] : "";
  const chartCount =
    open?.segmentAccountId && current
      ? current.distribution.byUser?.find((share) => share.accountId === open.segmentAccountId)?.counts[open.bucketIndex]
      : bucket?.count;
  return (
    <ReportDrillDrawer
      opened={opened && open !== null}
      onClose={onClose}
      title={current?.title ?? ""}
      subtitle={current ? label : undefined}
      query={query}
      unit={current?.unit ?? "wd"}
      anchor={anchor}
      timeZone={timeZone}
      chartCount={chartCount}
      // A request for both kinds (blocked time's `itemKind=BOTH`) lists tasks and epics: every bar says which is which.
      showKind={filter.itemKind === "BOTH"}
      people={current?.people}
      segmentAccountId={open?.segmentAccountId}
      onSegmentChange={onSegmentChange}
      allowEveryone={!segmentRequired}
      codeSources={codeSources}
    />
  );
}
