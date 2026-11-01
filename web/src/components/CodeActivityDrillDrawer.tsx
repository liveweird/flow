import { useEffect, useRef } from "react";
import { skipToken, useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { getCodeActivityItems } from "../api/reportDrill";
import type { CodeActivityReport, ReportFilters } from "../api/reports";
import { CODE_VIEWS, codeDrillCount, memberName, type CodeDrillTarget } from "../utils/codeActivityDrill";
import { distributionPeople } from "../utils/distributionPeople";
import { serializeDrill, type DrillTarget } from "../utils/reportDrill";
import { reportQuery, type ReportFilterState } from "../utils/reportFilter";
import { formatDays, formatOneDecimal } from "../utils/reportFormat";
import DistributionDrillDrawer, { type DistributionDrillView } from "./DistributionDrillDrawer";
import ReportDrillDrawer from "./ReportDrillDrawer";

interface Props {
  report: CodeActivityReport;
  filters: ReportFilters;
  filter: ReportFilterState;
  /** Whether the drill is open; `target` is the hook's `shown` (the last target, kept through the drawer's exit transition). */
  opened: boolean;
  target: DrillTarget | null;
  onClose: () => void;
  onSegmentChange: (accountId: string | undefined) => void;
}

/**
 * The items behind a tile, a member's cell or a weekly bar (the `code` target): the commits or merge requests the figure counts, fetched with the
 * page's own filter. A target the SHOWN report has no figure for — a person it does not list, a week it did not draw, a view with no tile — opens
 * nothing and, once the report is in hand, is dropped from the URL (never a drawer that answers a 400).
 */
function FigureDrawer({ report, filters, filter, opened, target, onClose }: Omit<Props, "target" | "onSegmentChange"> & { target: CodeDrillTarget | null }) {
  const { t } = useTranslation();
  const count = target === null ? undefined : codeDrillCount(report, target);
  const open = target !== null && count !== undefined ? target : null;
  const stale = opened && target !== null && open === null;
  // Close a stale target ONCE (HomeDrillDrawer's idiom): `onClose` is `navigate(-1)` when this page opened the drill, so a second call before
  // the location has changed would step back twice. The ref remembers which target it already closed.
  const closed = useRef<string | null>(null);
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  });
  const staleKey = stale && target !== null ? serializeDrill(target) : null;
  useEffect(() => {
    if (staleKey === null) {
      closed.current = null;
    } else if (closed.current !== staleKey) {
      closed.current = staleKey;
      onCloseRef.current();
    }
  }, [staleKey]);
  const query = useQuery({
    queryKey: ["reports", "code-activity", "items", reportQuery(filter), open ? serializeDrill(open) : null],
    // Closed means no request (a filter change during the exit transition must not fetch); the last answer stays cached.
    queryFn: opened && open ? () => getCodeActivityItems(filter, open) : skipToken,
  });
  const spec = open === null ? undefined : CODE_VIEWS[open.view];
  const person = open?.segmentAccountId === undefined ? undefined : memberName(report, filters.teams.flatMap((team) => team.members), open.segmentAccountId);
  const week = open?.bucketStart === undefined ? undefined : t("reports.codeActivity.drill.weekOf", { date: open.bucketStart });
  return (
    <ReportDrillDrawer
      opened={opened && open !== null}
      onClose={onClose}
      title={open === null ? "" : t(`reports.codeActivity.drill.view.${open.view}`)}
      subtitle={person ?? week}
      query={query}
      unit={spec?.unit ?? "none"}
      anchor={spec?.anchor ?? "none"}
      timeZone={filters.timeZone}
      chartCount={count}
      codeSources={report.sources}
    />
  );
}

/**
 * The drill drawer of Code activity. Two kinds of target share the page's `drill` param: a bar of one of the five histograms (`hist`, the shared
 * `DistributionDrillDrawer` with the person select of a per-person split) and a tile, a member's cell or a weekly bar (`code`). The issue keys of
 * both link to the GitLab of the report's sources.
 */
export default function CodeActivityDrillDrawer({ report, filters, filter, opened, target, onClose, onSegmentChange }: Props) {
  const { t } = useTranslation();
  const { flow, sources } = report;
  const views: DistributionDrillView[] = [
    { view: "MR_CYCLE_WORKING_DAYS", title: t("reports.codeActivity.flow.cycleWorking.title"), distribution: flow.cycleWorkingDays, format: formatDays, unit: "wd" },
    { view: "MR_CYCLE_ELAPSED_DAYS", title: t("reports.codeActivity.flow.cycleElapsed.title"), distribution: flow.cycleElapsedDays, format: formatDays, unit: "days" },
    { view: "TIME_TO_FIRST_REVIEW", title: t("reports.codeActivity.flow.firstReview.title"), distribution: flow.timeToFirstReviewWorkingDays, format: formatDays, unit: "wd" },
    { view: "MR_SIZE_LINES", title: t("reports.codeActivity.flow.sizeLines.title"), distribution: flow.sizeLines, format: formatOneDecimal, unit: "lines" },
    { view: "MR_SIZE_FILES", title: t("reports.codeActivity.flow.sizeFiles.title"), distribution: flow.sizeFiles, format: formatOneDecimal, unit: "files" },
  ].map((entry) => ({ ...entry, people: distributionPeople(entry.distribution) }));
  return (
    <>
      <DistributionDrillDrawer
        report="code-activity"
        fetchItems={getCodeActivityItems}
        opened={opened && target?.kind === "hist"}
        target={target}
        views={views}
        filter={filter}
        timeZone={filters.timeZone}
        onClose={onClose}
        onSegmentChange={onSegmentChange}
        anchor="merged"
        codeSources={sources}
      />
      <FigureDrawer
        report={report}
        filters={filters}
        filter={filter}
        opened={opened && target?.kind === "code"}
        target={target?.kind === "code" ? target : null}
        onClose={onClose}
      />
    </>
  );
}
