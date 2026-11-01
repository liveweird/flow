import { useTranslation } from "react-i18next";
import { SimpleGrid, Stack } from "@mantine/core";
import type { TaskCodeReport } from "../api/reports";
import { formatDays, formatPercent } from "../utils/reportFormat";
import DistributionPanel from "./DistributionPanel";
import ReportChartCard from "./ReportChartCard";
import ReportTile from "./ReportTile";

/**
 * The three figure groups of Tasks and code (A51), each a card. (1) The done tasks with and without a merged merge request: NEUTRAL, no direction is
 * better (a task may need no code), so the share of tasks without one is context and nothing is coloured. (2) The code lead time, from a task's start to
 * the merge of its last merge request. (3) Code merged after the task was done, its share and the lag. The two time figures say "lower is better" in
 * words in their captions and hints, never by a colour. No drill yet: the figures are plain text.
 */
export default function TaskCodeFigures({ report, isRefreshing }: { report: TaskCodeReport; isRefreshing: boolean }) {
  const { t } = useTranslation();
  const { tasks, mergedAfterDone, meta } = report;
  const min = meta.minSampleSize;
  const nothingDone = tasks.done === 0;
  return (
    <>
      <ReportChartCard title={t("reports.taskCode.tasks.title")} caption={t("reports.taskCode.tasks.caption")} isPending={false} isRefreshing={isRefreshing} empty={nothingDone}>
        <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="md">
          <ReportTile label={t("reports.taskCode.tasks.done")} value={String(tasks.done)} hint={t("reports.taskCode.tasks.doneHint")} />
          <ReportTile label={t("reports.taskCode.tasks.withMr")} value={String(tasks.withMergeRequest)} hint={t("reports.taskCode.tasks.withMrHint")} />
          <ReportTile
            label={t("reports.taskCode.tasks.withoutMr")}
            value={String(tasks.withoutMergeRequest)}
            hint={tasks.withoutShare === undefined ? t("reports.taskCode.tasks.noShare") : t("reports.taskCode.tasks.withoutShare", { share: formatPercent(tasks.withoutShare) })}
          />
        </SimpleGrid>
      </ReportChartCard>
      <ReportChartCard title={t("reports.taskCode.leadTime.title")} caption={t("reports.taskCode.leadTime.caption")} isPending={false} isRefreshing={isRefreshing} empty={nothingDone}>
        <DistributionPanel
          title={t("reports.taskCode.leadTime.panelTitle")}
          distribution={report.codeLeadTimeWorkingDays}
          minSampleSize={min}
          format={formatDays}
          axisLabel={t("reports.taskCode.leadTime.axis")}
        />
      </ReportChartCard>
      <ReportChartCard title={t("reports.taskCode.mergedAfter.title")} caption={t("reports.taskCode.mergedAfter.caption")} isPending={false} isRefreshing={isRefreshing} empty={nothingDone}>
        <Stack gap="lg">
          <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="md">
            <ReportTile
              label={t("reports.taskCode.mergedAfter.tile")}
              value={String(mergedAfterDone.count)}
              hint={
                mergedAfterDone.share === undefined
                  ? t("reports.taskCode.mergedAfter.noShare")
                  : t("reports.taskCode.mergedAfter.share", { share: formatPercent(mergedAfterDone.share) })
              }
            />
          </SimpleGrid>
          <DistributionPanel
            title={t("reports.taskCode.mergedAfter.lagTitle")}
            caption={t("reports.taskCode.mergedAfter.lagCaption")}
            distribution={mergedAfterDone.lagWorkingDays}
            minSampleSize={min}
            format={formatDays}
            axisLabel={t("reports.taskCode.mergedAfter.axis")}
          />
        </Stack>
      </ReportChartCard>
    </>
  );
}
