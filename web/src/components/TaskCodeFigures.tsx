import { useTranslation } from "react-i18next";
import { Box, SimpleGrid, Stack } from "@mantine/core";
import type { TaskCodeReport } from "../api/reports";
import { formatDays, formatPercent } from "../utils/reportFormat";
import {
  TASK_CODE_AFTER_ANCHOR,
  TASK_CODE_LEAD_ANCHOR,
  TASK_CODE_TASKS_ANCHOR,
  taskCodeDrillCount,
  type TaskCodeDrill,
  type TaskCodeFigureView,
} from "../utils/taskCodeDrill";
import DistributionPanel from "./DistributionPanel";
import ReportChartCard from "./ReportChartCard";
import ReportTile, { type TileDrill } from "./ReportTile";

/**
 * The three figure groups of Tasks and code (A51), each a card. (1) The done tasks with and without a merged merge request: NEUTRAL, no direction is
 * better (a task may need no code), so the share of tasks without one is context and nothing is coloured. (2) The code lead time, from a task's start to
 * the merge of its last merge request. (3) Code merged after the task was done, its share and the lag. The two time figures say "lower is better" in
 * words in their captions and hints, never by a colour. With a `drill` every count lists the done tasks behind it (the Done total opens nothing) and every histogram bar the tasks in it, each task with the merge requests the figure counted for it.
 */
export default function TaskCodeFigures({ report, isRefreshing, drill }: { report: TaskCodeReport; isRefreshing: boolean; drill?: TaskCodeDrill }) {
  const { t } = useTranslation();
  const { tasks, mergedAfterDone, meta } = report;
  const min = meta.minSampleSize;
  const nothingDone = tasks.done === 0;
  const tile = (view: TaskCodeFigureView, label: string, title: string, anchor: string): TileDrill | undefined => {
    const count = taskCodeDrillCount(report, view);
    return (
      drill && {
        label: t("reports.drill.figureAria", { figure: String(count), column: label, name: title }),
        count,
        disabled: drill.disabled,
        onOpen: (trigger) => drill.open({ kind: "taskcode", view }, { trigger, anchor }),
      }
    );
  };
  const panel = (view: string, anchor: string) => ({ onDrill: drill?.bar(view, anchor), drillDisabled: drill?.disabled });
  return (
    <>
      <ReportChartCard
        title={t("reports.taskCode.tasks.title")}
        caption={t("reports.taskCode.tasks.caption")}
        isPending={false}
        isRefreshing={isRefreshing}
        empty={nothingDone}
        drillAnchor={drill && TASK_CODE_TASKS_ANCHOR}
        hint={drill && t("reports.taskCode.drill.tilesHint")}
      >
        <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="md">
          <ReportTile label={t("reports.taskCode.tasks.done")} value={String(tasks.done)} hint={t("reports.taskCode.tasks.doneHint")} />
          <ReportTile
            label={t("reports.taskCode.tasks.withMr")}
            value={String(tasks.withMergeRequest)}
            hint={t("reports.taskCode.tasks.withMrHint")}
            drill={tile("TASKS_WITH_MR", t("reports.taskCode.tasks.withMr"), t("reports.taskCode.tasks.title"), TASK_CODE_TASKS_ANCHOR)}
          />
          <ReportTile
            label={t("reports.taskCode.tasks.withoutMr")}
            value={String(tasks.withoutMergeRequest)}
            drill={tile("TASKS_WITHOUT_MR", t("reports.taskCode.tasks.withoutMr"), t("reports.taskCode.tasks.title"), TASK_CODE_TASKS_ANCHOR)}
            hint={tasks.withoutShare === undefined ? t("reports.taskCode.tasks.noShare") : t("reports.taskCode.tasks.withoutShare", { share: formatPercent(tasks.withoutShare) })}
          />
        </SimpleGrid>
      </ReportChartCard>
      <ReportChartCard
        title={t("reports.taskCode.leadTime.title")}
        caption={t("reports.taskCode.leadTime.caption")}
        isPending={false}
        isRefreshing={isRefreshing}
        empty={nothingDone}
        drillAnchor={drill && TASK_CODE_LEAD_ANCHOR}
        hint={drill && t("reports.drill.hint")}
      >
        <DistributionPanel
          title={t("reports.taskCode.leadTime.panelTitle")}
          distribution={report.codeLeadTimeWorkingDays}
          minSampleSize={min}
          format={formatDays}
          axisLabel={t("reports.taskCode.leadTime.axis")}
          {...panel("CODE_LEAD_TIME", TASK_CODE_LEAD_ANCHOR)}
        />
      </ReportChartCard>
      <ReportChartCard
        title={t("reports.taskCode.mergedAfter.title")}
        caption={t("reports.taskCode.mergedAfter.caption")}
        isPending={false}
        isRefreshing={isRefreshing}
        empty={nothingDone}
        drillAnchor={drill && TASK_CODE_AFTER_ANCHOR}
        hint={drill && t("reports.drill.hint")}
      >
        <Stack gap="lg">
          {/* One tile: a width of its own, not a third of a grid with two empty cells. */}
          <Box maw={360}>
            <ReportTile
              label={t("reports.taskCode.mergedAfter.tile")}
              value={String(mergedAfterDone.count)}
              hint={
                mergedAfterDone.share === undefined
                  ? t("reports.taskCode.mergedAfter.noShare")
                  : t("reports.taskCode.mergedAfter.share", { share: formatPercent(mergedAfterDone.share) })
              }
              drill={tile("MERGED_AFTER_DONE", t("reports.taskCode.mergedAfter.tile"), t("reports.taskCode.mergedAfter.title"), TASK_CODE_AFTER_ANCHOR)}
            />
          </Box>
          <DistributionPanel
            title={t("reports.taskCode.mergedAfter.lagTitle")}
            caption={t("reports.taskCode.mergedAfter.lagCaption")}
            distribution={mergedAfterDone.lagWorkingDays}
            minSampleSize={min}
            format={formatDays}
            axisLabel={t("reports.taskCode.mergedAfter.axis")}
            {...panel("MERGED_AFTER_DONE_LAG", TASK_CODE_AFTER_ANCHOR)}
          />
        </Stack>
      </ReportChartCard>
    </>
  );
}
