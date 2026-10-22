import { useTranslation } from "react-i18next";
import type { CostsSprint, ReportFilters } from "../api/reports";
import type { DrillState } from "../hooks/useReportDrill";
import { COSTS_EPIC_ANCHOR } from "../utils/costsReport";
import { costsEpicTarget } from "../utils/costsSprints";
import { COSTS_STACKS, costsEpicChart, epicTableRows } from "../utils/sprintEpics";
import { formatMd } from "../utils/reportFormat";
import SprintEpicSection, { type EpicFigure } from "./SprintEpicSection";
import type { EpicChartStack } from "./SprintEpicChart";

type Segment = CostsSprint["byEpic"][number];

/**
 * Costs' actual cost per closed sprint, stacked by epic (`sprints[].byEpic`): one column per sprint, the epic segments in the server's
 * order (the shared by-epic section), and the table of every sprint × segment whose man-day figure opens the worklogs behind that
 * segment. The cost counts toward the epic the task belongs to NOW, like the delivery reports' by-epic charts.
 */
export default function CostsSprintEpicSection({
  sprints,
  filters,
  drill,
  drillable,
  refreshing,
}: {
  sprints: readonly CostsSprint[];
  filters: ReportFilters;
  drill: DrillState;
  drillable: boolean;
  refreshing: boolean;
}) {
  const { t } = useTranslation();
  const chart = costsEpicChart(sprints, filters);
  const stacks: EpicChartStack[] = [{ ...COSTS_STACKS[0], treatment: "solid", label: (name) => name }];
  const figures: EpicFigure<Segment>[] = [
    {
      key: "acMd",
      header: t("reports.costs.sprints.column.acMd"),
      render: (segment) => formatMd(segment.acMd),
      items: (segment) => (segment.acMd > 0 ? 1 : 0),
    },
  ];
  return (
    <SprintEpicSection
      title={t("reports.costs.sprints.epicTitle")}
      caption={t("reports.costs.sprints.epicCaption")}
      chartLabel={t("reports.costs.sprints.epicChartLabel")}
      tableLabel={t("reports.costs.sprints.epicTableAria")}
      chart={chart}
      stacks={stacks}
      rows={epicTableRows(sprints, filters, (sprint) => sprint.byEpic, chart.segments)}
      figures={figures}
      filters={filters}
      drill={drill}
      drillable={drillable}
      refreshing={refreshing}
      anchor={COSTS_EPIC_ANCHOR}
      toTarget={costsEpicTarget}
      note={t("reports.costs.sprints.epicNote")}
    />
  );
}
