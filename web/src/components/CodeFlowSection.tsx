import { lazy, Suspense } from "react";
import { useTranslation } from "react-i18next";
import { SimpleGrid, Stack } from "@mantine/core";
import type { CodeActivityReport } from "../api/reports";
import { distributionPeople } from "../utils/distributionPeople";
import { formatOneDecimal, formatDays, formatPercent } from "../utils/reportFormat";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import DistributionPanel from "./DistributionPanel";
import LoadingBlock from "./LoadingBlock";
import ReportChartCard from "./ReportChartCard";
import ReportTile from "./ReportTile";
import ScrollRegion from "./ScrollRegion";

// The weekly chart (and with it recharts) rides its own lazy chunk.
const CodeWeeklyChart = lazy(() => import("./CodeWeeklyChart"));

type Week = CodeActivityReport["flow"]["weekly"][number];

/**
 * The flow of the team's merge requests (A50: the figures the page may give a direction — lower is better for the cycle, the time to first
 * review, the size and the merged-without-review counts; the captions say so in words): the merged count with the closed-without-merging
 * beside it, the two review-rule counts, the five distributions (each with its own numbers table) and, in a card of its own, the weekly
 * merged bar chart with its numbers table. With several members picked every histogram stacks per person, as the other distribution reports do.
 */
export default function CodeFlowSection({ report, isRefreshing }: { report: CodeActivityReport; isRefreshing: boolean }) {
  const { t } = useTranslation();
  const { flow, meta } = report;
  const min = meta.minSampleSize;
  const people = distributionPeople(flow.cycleWorkingDays);
  const share = (value: { count: number; share?: number }) =>
    value.share === undefined ? t("reports.codeActivity.flow.noShare") : t("reports.codeActivity.flow.share", { share: formatPercent(value.share) });
  const weekColumns: ColumnDef<Week>[] = [
    { key: "week", header: t("reports.codeActivity.weekly.week"), render: (week) => week.bucketStart },
    { key: "merged", header: t("reports.codeActivity.weekly.merged"), render: (week) => week.merged, align: "right" },
  ];
  const tableLabel = t("reports.codeActivity.weekly.tableLabel");
  return (
    <>
      <ReportChartCard
        title={t("reports.codeActivity.flow.title")}
        caption={t("reports.codeActivity.flow.caption")}
        isPending={false}
        isRefreshing={isRefreshing}
        empty={flow.merged === 0 && flow.closedUnmerged === 0}
      >
        <Stack gap="lg">
          <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="md">
            <ReportTile
              label={t("reports.codeActivity.flow.merged")}
              value={String(flow.merged)}
              hint={t("reports.codeActivity.flow.closedUnmerged", { n: flow.closedUnmerged })}
            />
            <ReportTile
              label={t("reports.codeActivity.flow.withoutReview")}
              value={String(flow.mergedWithoutReview.count)}
              hint={`${t("reports.codeActivity.flow.withoutReviewRule")} ${share(flow.mergedWithoutReview)}`}
            />
            <ReportTile
              label={t("reports.codeActivity.flow.withoutApproval")}
              value={String(flow.mergedWithoutApproval.count)}
              hint={`${t("reports.codeActivity.flow.withoutApprovalRule")} ${share(flow.mergedWithoutApproval)}`}
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
            />
            <DistributionPanel
              title={t("reports.codeActivity.flow.cycleElapsed.title")}
              caption={t("reports.codeActivity.flow.cycleElapsed.caption")}
              distribution={flow.cycleElapsedDays}
              minSampleSize={min}
              format={formatDays}
              axisLabel={t("reports.codeActivity.flow.cycleElapsed.axis")}
              people={distributionPeople(flow.cycleElapsedDays)}
            />
            <DistributionPanel
              title={t("reports.codeActivity.flow.firstReview.title")}
              caption={t("reports.codeActivity.flow.firstReview.caption")}
              distribution={flow.timeToFirstReviewWorkingDays}
              minSampleSize={min}
              format={formatDays}
              axisLabel={t("reports.codeActivity.flow.firstReview.axis")}
              people={distributionPeople(flow.timeToFirstReviewWorkingDays)}
            />
            <DistributionPanel
              title={t("reports.codeActivity.flow.sizeLines.title")}
              caption={t("reports.codeActivity.flow.sizeLines.caption")}
              distribution={flow.sizeLines}
              minSampleSize={min}
              format={formatOneDecimal}
              axisLabel={t("reports.codeActivity.flow.sizeLines.axis")}
              people={distributionPeople(flow.sizeLines)}
            />
            <DistributionPanel
              title={t("reports.codeActivity.flow.sizeFiles.title")}
              caption={t("reports.codeActivity.flow.sizeFiles.caption")}
              distribution={flow.sizeFiles}
              minSampleSize={min}
              format={formatOneDecimal}
              axisLabel={t("reports.codeActivity.flow.sizeFiles.axis")}
              people={distributionPeople(flow.sizeFiles)}
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
      >
        <Stack gap="md">
          <Suspense fallback={<LoadingBlock />}>
            <CodeWeeklyChart weeks={flow.weekly} />
          </Suspense>
          <ScrollRegion label={tableLabel} minWidth={260} maxHeight={320}>
            <ColumnTable verticalSpacing={4} aria-label={tableLabel} columns={weekColumns} rows={flow.weekly} rowKey={(week) => week.bucketStart} />
          </ScrollRegion>
        </Stack>
      </ReportChartCard>
    </>
  );
}
