import { useTranslation } from "react-i18next";
import type { CommitmentsSprint, ReportFilters } from "../api/reports";
import type { DrillState } from "../hooks/useReportDrill";
import { commitmentsFigureColumns, commitmentsFigureDrill } from "../utils/commitmentsReport";
import { COMMITMENTS_STACKS, commitmentsEpicChart, epicTableRows } from "../utils/sprintEpics";
import SprintEpicSection, { type EpicFigure } from "./SprintEpicSection";
import type { EpicChartStack } from "./SprintEpicChart";

type Segment = CommitmentsSprint["byEpic"][number];

/** The card's focus-return anchor for a drill opened by a double-click on this chart. */
export const COMMITMENTS_EPIC_ANCHOR = "commitments-epics";

/**
 * Commitments' initial and final scope per sprint, stacked by epic (`sprints[].byEpic`): two columns per sprint — initial (outlined) and
 * final (solid), the same segments in both — and the table of every sprint × segment, whose four figures drill like the sprint table's.
 * With people picked the server has already narrowed the figures to theirs; this only renders what it sends.
 */
export default function CommitmentsEpicSection({
  sprints,
  filters,
  drill,
  drillable,
  refreshing,
}: {
  sprints: readonly CommitmentsSprint[];
  filters: ReportFilters;
  drill: DrillState;
  drillable: boolean;
  refreshing: boolean;
}) {
  const { t } = useTranslation();
  const chart = commitmentsEpicChart(sprints, filters);
  const stacks: EpicChartStack[] = [
    {
      ...COMMITMENTS_STACKS[0],
      treatment: "outlined",
      label: (name) => t("reports.commitments.userSeries.committed", { name }),
      legend: t("reports.commitments.userLegend.committed"),
    },
    {
      ...COMMITMENTS_STACKS[1],
      treatment: "solid",
      label: (name) => t("reports.commitments.userSeries.final", { name }),
      legend: t("reports.commitments.userLegend.final"),
    },
  ];
  const figures = commitmentsFigureColumns<Segment>(t, (segment) => segment).flatMap((column): EpicFigure<Segment>[] => {
    const target = commitmentsFigureDrill<Segment>(column.key, (segment) => segment);
    return target ? [{ key: column.key, header: column.header, render: column.render, bucket: target.bucket, items: target.items }] : [];
  });
  return (
    <SprintEpicSection
      title={t("reports.commitments.epicChartTitle")}
      caption={t("reports.commitments.epicCaption")}
      chartLabel={t("reports.commitments.epicChartLabel")}
      tableLabel={t("reports.commitments.epicTableAria")}
      chart={chart}
      stacks={stacks}
      rows={epicTableRows(sprints, filters, (sprint) => sprint.byEpic, chart.segments)}
      figures={figures}
      filters={filters}
      drill={drill}
      drillable={drillable}
      refreshing={refreshing}
      anchor={COMMITMENTS_EPIC_ANCHOR}
    />
  );
}
