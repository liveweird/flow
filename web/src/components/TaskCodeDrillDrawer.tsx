import { skipToken, useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { getTaskCodeItems } from "../api/reportDrill";
import type { ReportFilters, TaskCodeReport } from "../api/reports";
import { serializeDrill, type DrillTarget } from "../utils/reportDrill";
import { reportQuery, type ReportFilterState } from "../utils/reportFilter";
import { formatDays } from "../utils/reportFormat";
import { taskCodeDrillCount, TASK_CODE_VIEWS, type TaskCodeDrillTarget } from "../utils/taskCodeDrill";
import DistributionDrillDrawer, { type DistributionDrillView } from "./DistributionDrillDrawer";
import ReportDrillDrawer from "./ReportDrillDrawer";

interface Props {
  report: TaskCodeReport;
  filters: ReportFilters;
  filter: ReportFilterState;
  /** Whether the drill is open; `target` is the hook's `shown` (the last target, kept through the drawer's exit transition). */
  opened: boolean;
  target: DrillTarget | null;
  onClose: () => void;
}

/** The report has no person level, so a segment never exists: the histogram drawer's select has nothing to switch. */
const NO_SEGMENT = () => undefined;

/** The tasks behind a count (the `taskcode` target): fetched with the page's own filter, each with the merge requests the count held for it. */
function FigureDrawer({ report, filters, filter, opened, target, onClose }: Omit<Props, "target"> & { target: TaskCodeDrillTarget | null }) {
  const { t } = useTranslation();
  const query = useQuery({
    queryKey: ["reports", "task-code", "items", reportQuery(filter), target ? serializeDrill(target) : null],
    // Closed means no request (a filter change during the exit transition must not fetch); the last answer stays cached.
    queryFn: opened && target ? () => getTaskCodeItems(filter, target) : skipToken,
  });
  const spec = target === null ? undefined : TASK_CODE_VIEWS[target.view];
  return (
    <ReportDrillDrawer
      opened={opened && target !== null}
      onClose={onClose}
      title={target === null ? "" : t(`reports.taskCode.drill.view.${target.view}`)}
      query={query}
      unit={spec?.unit ?? "none"}
      anchor={spec?.anchor ?? "done"}
      timeZone={filters.timeZone}
      chartCount={target === null ? undefined : taskCodeDrillCount(report, target.view)}
      taskSources={report.sources}
    />
  );
}

/**
 * The drill drawer of Tasks and code. Two kinds of target share the page's `drill` param: a bar of one of the two histograms (`hist`, the shared
 * `DistributionDrillDrawer`) and a count (`taskcode`). Both list the done tasks behind the figure, each Jira key linked to Jira and the merge requests the figure
 * counted for it linked to the GitLab of the report's sources.
 */
export default function TaskCodeDrillDrawer({ report, filters, filter, opened, target, onClose }: Props) {
  const { t } = useTranslation();
  const views: DistributionDrillView[] = [
    { view: "CODE_LEAD_TIME", title: t("reports.taskCode.leadTime.title"), distribution: report.codeLeadTimeWorkingDays, format: formatDays, unit: "wd", people: [] },
    { view: "MERGED_AFTER_DONE_LAG", title: t("reports.taskCode.mergedAfter.lagTitle"), distribution: report.mergedAfterDone.lagWorkingDays, format: formatDays, unit: "wd", people: [] },
  ];
  return (
    <>
      <DistributionDrillDrawer
        report="task-code"
        fetchItems={getTaskCodeItems}
        opened={opened && target?.kind === "hist"}
        target={target}
        views={views}
        filter={filter}
        timeZone={filters.timeZone}
        onClose={onClose}
        onSegmentChange={NO_SEGMENT}
        taskSources={report.sources}
      />
      <FigureDrawer
        report={report}
        filters={filters}
        filter={filter}
        opened={opened && target?.kind === "taskcode"}
        target={target?.kind === "taskcode" ? target : null}
        onClose={onClose}
      />
    </>
  );
}
