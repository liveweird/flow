import { useTranslation } from "react-i18next";
import type { ReportFilters, ThroughputSprint } from "../api/reports";
import { EPIC_DRILL_ANCHOR } from "../hooks/useThroughputDrill";
import type { DrillState } from "../hooks/useReportDrill";
import { THROUGHPUT_STACKS, epicTableRows, throughputEpicChart } from "../utils/sprintEpics";
import { throughputFigureColumns } from "../utils/throughputReport";
import SprintEpicSection, { type EpicFigure } from "./SprintEpicSection";
import type { EpicChartStack } from "./SprintEpicChart";

type Segment = ThroughputSprint["byEpic"][number];

/**
 * Throughput's delivered work per sprint, stacked by epic (`bySprint[].byEpic`): one column per sprint and the table of every sprint
 * × segment, whose two figures (MD, items) drill the sprint's delivered items narrowed to the segment. Only the sprint view is split —
 * the period view (`byBucket`) is not — so this section sits with the sprint card and the period card has nothing of it.
 */
export default function ThroughputEpicSection({
  sprints,
  filters,
  drill,
  drillable,
  refreshing,
}: {
  sprints: readonly ThroughputSprint[];
  filters: ReportFilters;
  drill: DrillState;
  drillable: boolean;
  refreshing: boolean;
}) {
  const { t } = useTranslation();
  const chart = throughputEpicChart(sprints, filters);
  const stacks: EpicChartStack[] = [
    { ...THROUGHPUT_STACKS[0], treatment: "solid", label: (name) => t("reports.throughput.userSeries.delivered", { name }) },
  ];
  const figures = throughputFigureColumns<Segment>(t, (segment) => segment).map(
    (column): EpicFigure<Segment> => ({
      key: column.key,
      header: column.header,
      render: column.render,
      bucket: "DELIVERED",
      items: (segment) => segment.deliveredItems,
    }),
  );
  return (
    <SprintEpicSection
      title={t("reports.throughput.epicChartTitle")}
      caption={t("reports.throughput.epicCaption")}
      chartLabel={t("reports.throughput.chartLabels.byEpic")}
      tableLabel={t("reports.throughput.epicTableAria")}
      chart={chart}
      stacks={stacks}
      rows={epicTableRows(sprints, filters, (sprint) => sprint.byEpic, chart.segments)}
      figures={figures}
      filters={filters}
      drill={drill}
      drillable={drillable}
      refreshing={refreshing}
      anchor={EPIC_DRILL_ANCHOR}
    />
  );
}
