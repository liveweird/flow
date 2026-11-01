import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { SimpleGrid, Stack } from "@mantine/core";
import type { CodeActivityReport } from "../api/reports";
import { CODE_FLOW_ANCHOR, CODE_WEEKLY_ANCHOR, type CodeDrill, type CodeDrillTarget } from "../utils/codeActivityDrill";
import type { ChartBarHit } from "../utils/chartDrill";
import { distributionPeople } from "../utils/distributionPeople";
import { drillFigureColumn } from "../utils/drillFigure";
import { formatOneDecimal, formatDays, formatPercent } from "../utils/reportFormat";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import DistributionPanel from "./DistributionPanel";
import LoadingBlock from "./LoadingBlock";
import ReportChartCard from "./ReportChartCard";
import ReportTile, { type TileDrill } from "./ReportTile";
import ScrollRegion from "./ScrollRegion";

// The weekly chart (and with it recharts) rides its own lazy chunk.
const CodeWeeklyChart = lazy(() => import("./CodeWeeklyChart"));

type Week = CodeActivityReport["flow"]["weekly"][number];

/**
 * The flow of the team's merge requests (A50: the figures the page may give a direction — lower is better for the cycle, the time to first
 * review, the size and the merged-without-review counts; the captions say so in words): the merged count with the closed-without-merging
 * beside it, the two review-rule counts, the five distributions (each with its own numbers table) and, in a card of its own, the weekly
 * merged bar chart with its numbers table. With several members picked every histogram stacks per person, as the other distribution reports do.
 * With a `drill` every figure here lists the merge requests behind it: the three counts, each histogram bar and each week.
 */
export default function CodeFlowSection({ report, isRefreshing, drill }: { report: CodeActivityReport; isRefreshing: boolean; drill?: CodeDrill }) {
  const { t } = useTranslation();
  const { flow, meta } = report;
  const min = meta.minSampleSize;
  const people = distributionPeople(flow.cycleWorkingDays);
  const share = (value: { count: number; share?: number }) =>
    value.share === undefined ? t("reports.codeActivity.flow.noShare") : t("reports.codeActivity.flow.share", { share: formatPercent(value.share) });
  const openWeek = (week: Week, from?: { trigger?: HTMLElement }) => {
    if (drill && !drill.disabled && week.merged > 0) drill.open({ kind: "code", view: "WEEK", bucketStart: week.bucketStart }, { ...from, anchor: CODE_WEEKLY_ANCHOR });
  };
  const onBarDoubleClick = (hit: ChartBarHit) => {
    const week = flow.weekly.find((candidate) => candidate.bucketStart === hit.rowId);
    if (week) openWeek(week);
  };
  const weekColumns: ColumnDef<Week>[] = [
    { key: "week", header: t("reports.codeActivity.weekly.week"), render: (week) => week.bucketStart },
    drillFigureColumn<Week>(
      { key: "merged", header: t("reports.codeActivity.weekly.merged"), render: (week) => week.merged, align: "right" },
      {
        label: (week, figure, column) => t("reports.drill.figureAria", { figure, column, name: t("reports.codeActivity.drill.weekOf", { date: week.bucketStart }) }),
        count: (week) => week.merged,
        disabled: drill?.disabled,
        onOpen: drill && ((week, trigger) => openWeek(week, { trigger })),
      },
    ),
  ];
  const tile = (view: CodeDrillTarget["view"], label: string, count: number): TileDrill | undefined =>
    drill && {
      label: t("reports.drill.figureAria", { figure: String(count), column: label, name: t("reports.codeActivity.flow.title") }),
      count,
      disabled: drill.disabled,
      onOpen: (trigger) => drill.open({ kind: "code", view }, { trigger, anchor: CODE_FLOW_ANCHOR }),
    };
  const panel = (view: string) => ({ onDrill: drill?.bar(view, CODE_FLOW_ANCHOR), drillDisabled: drill?.disabled });
  const tableLabel = t("reports.codeActivity.weekly.tableLabel");
  return (
    <>
      <ReportChartCard
        title={t("reports.codeActivity.flow.title")}
        caption={t("reports.codeActivity.flow.caption")}
        isPending={false}
        isRefreshing={isRefreshing}
        empty={flow.merged === 0 && flow.closedUnmerged === 0}
        drillAnchor={drill && CODE_FLOW_ANCHOR}
        hint={drill && t("reports.drill.hint")}
      >
        <Stack gap="lg">
          <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="md">
            <ReportTile
              label={t("reports.codeActivity.flow.merged")}
              value={String(flow.merged)}
              hint={t("reports.codeActivity.flow.closedUnmerged", { n: flow.closedUnmerged })}
              drill={tile("MRS_MERGED", t("reports.codeActivity.flow.merged"), flow.merged)}
            />
            <ReportTile
              label={t("reports.codeActivity.flow.withoutReview")}
              value={String(flow.mergedWithoutReview.count)}
              hint={`${t("reports.codeActivity.flow.withoutReviewRule")} ${share(flow.mergedWithoutReview)}`}
              drill={tile("MERGED_WITHOUT_REVIEW", t("reports.codeActivity.flow.withoutReview"), flow.mergedWithoutReview.count)}
            />
            <ReportTile
              label={t("reports.codeActivity.flow.withoutApproval")}
              value={String(flow.mergedWithoutApproval.count)}
              hint={`${t("reports.codeActivity.flow.withoutApprovalRule")} ${share(flow.mergedWithoutApproval)}`}
              drill={tile("MERGED_WITHOUT_APPROVAL", t("reports.codeActivity.flow.withoutApproval"), flow.mergedWithoutApproval.count)}
            />
          </SimpleGrid>
          <SimpleGrid cols={{ base: 1, md: 2 }} spacing="lg">
            <DistributionPanel
              title={t("reports.codeActivity.flow.cycleWorking.title")}
              caption={t("reports.codeActivity.flow.cycleWorking.caption")}
              distribution={flow.cycleWorkingDays}
              minSampleSize={min}
              format={formatDays}
              axisLabel={t("reports.codeActivity.flow.cycleWorking.axis")}
              people={people}
              {...panel("MR_CYCLE_WORKING_DAYS")}
            />
            <DistributionPanel
              title={t("reports.codeActivity.flow.cycleElapsed.title")}
              caption={t("reports.codeActivity.flow.cycleElapsed.caption")}
              distribution={flow.cycleElapsedDays}
              minSampleSize={min}
              format={formatDays}
              axisLabel={t("reports.codeActivity.flow.cycleElapsed.axis")}
              people={distributionPeople(flow.cycleElapsedDays)}
              {...panel("MR_CYCLE_ELAPSED_DAYS")}
            />
            <DistributionPanel
              title={t("reports.codeActivity.flow.firstReview.title")}
              caption={t("reports.codeActivity.flow.firstReview.caption")}
              distribution={flow.timeToFirstReviewWorkingDays}
              minSampleSize={min}
              format={formatDays}
              axisLabel={t("reports.codeActivity.flow.firstReview.axis")}
              people={distributionPeople(flow.timeToFirstReviewWorkingDays)}
              {...panel("TIME_TO_FIRST_REVIEW")}
            />
            <DistributionPanel
              title={t("reports.codeActivity.flow.sizeLines.title")}
              caption={t("reports.codeActivity.flow.sizeLines.caption")}
              distribution={flow.sizeLines}
              minSampleSize={min}
              format={formatOneDecimal}
              axisLabel={t("reports.codeActivity.flow.sizeLines.axis")}
              people={distributionPeople(flow.sizeLines)}
              {...panel("MR_SIZE_LINES")}
            />
            <DistributionPanel
              title={t("reports.codeActivity.flow.sizeFiles.title")}
              caption={t("reports.codeActivity.flow.sizeFiles.caption")}
              distribution={flow.sizeFiles}
              minSampleSize={min}
              format={formatOneDecimal}
              axisLabel={t("reports.codeActivity.flow.sizeFiles.axis")}
              people={distributionPeople(flow.sizeFiles)}
              {...panel("MR_SIZE_FILES")}
            />
          </SimpleGrid>
        </Stack>
      </ReportChartCard>
      <ReportChartCard
        title={t("reports.codeActivity.weekly.title")}
        caption={t("reports.codeActivity.weekly.caption")}
        isPending={false}
        isRefreshing={isRefreshing}
        empty={flow.merged === 0}
        drillAnchor={drill && CODE_WEEKLY_ANCHOR}
        hint={drill && t("reports.drill.hint")}
      >
        <Stack gap="md">
          <Suspense fallback={<LoadingBlock />}>
            <CodeWeeklyChart weeks={flow.weekly} onBarDoubleClick={drill && !drill.disabled ? onBarDoubleClick : undefined} />
          </Suspense>
          <ScrollRegion label={tableLabel} minWidth={260} maxHeight={320}>
            <ColumnTable verticalSpacing={4} aria-label={tableLabel} columns={weekColumns} rows={flow.weekly} rowKey={(week) => week.bucketStart} />
          </ScrollRegion>
        </Stack>
      </ReportChartCard>
    </>
  );
}
