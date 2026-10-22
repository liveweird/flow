import { lazy, Suspense, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Stack, Text } from "@mantine/core";
import type { ReportFilters } from "../api/reports";
import type { DrillState } from "../hooks/useReportDrill";
import { drillFigureColumn } from "../utils/drillFigure";
import { epicSegmentName, type EpicSegmentIdentity } from "../utils/epicSegments";
import type { SprintBucket } from "../utils/reportDrill";
import type { SprintIdentity } from "../utils/reportSprints";
import { epicHitTarget, epicSegmentTarget, type EpicChart, type EpicSegmentTarget, type EpicTableRow } from "../utils/sprintEpics";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import EpicSegmentName from "./EpicSegmentName";
import LoadingBlock from "./LoadingBlock";
import ReportChartCard from "./ReportChartCard";
import ScrollRegion from "./ScrollRegion";
import type { EpicChartStack } from "./SprintEpicChart";
import { teamLabel } from "../utils/reportFormat";

// The chart (and with it recharts) rides its own lazy chunk.
const SprintEpicChart = lazy(() => import("./SprintEpicChart"));

/** A figure column of the by-epic table: the segment's number and the sprint bucket (and item count) that figure stands behind; Costs' has no bucket. */
export interface EpicFigure<G> {
  key: string;
  header: ReactNode;
  render: (segment: G) => ReactNode;
  bucket?: SprintBucket;
  /** How many items the figure stands for; none, no button. */
  items: (segment: G) => number;
}

/**
 * The by-epic section of a sprint report (Commitments, Throughput, Costs): a card with the stacked chart per sprint, the table of every
 * sprint × segment beside it and the rule that cuts the segments. Every segment drills like the sprint bars do — a double-click on
 * the chart or a click on a figure of the table opens the sprint's bucket narrowed to that epic segment (`epicSegmentTarget`); "Other
 * epics" carries the chart's own top epics. While the rows are kept data of a previous filter (`drillable` false) nothing opens.
 * `toTarget` is what a segment becomes (default: the sprint's bucket narrowed to it; Costs opens its own sprint-cost target) and `note`
 * the words under the table (default: the delivery reports' "an item counts toward the epic it belongs to now").
 */
export default function SprintEpicSection<S extends SprintIdentity, G extends EpicSegmentIdentity>({
  title,
  caption,
  chartLabel,
  tableLabel,
  chart,
  stacks,
  rows,
  figures,
  filters,
  drill,
  drillable,
  refreshing,
  anchor,
  toTarget = epicSegmentTarget,
  note,
}: {
  title: string;
  caption: string;
  chartLabel: string;
  tableLabel: string;
  chart: EpicChart;
  stacks: readonly EpicChartStack[];
  rows: EpicTableRow<S, G>[];
  figures: EpicFigure<G>[];
  filters: ReportFilters;
  drill: DrillState;
  drillable: boolean;
  refreshing: boolean;
  /** The card's focus-return anchor for a drill opened by a double-click or a deep link. */
  anchor: string;
  toTarget?: EpicSegmentTarget;
  note?: string;
}) {
  const { t } = useTranslation();
  const open = (row: EpicTableRow<S, G>, bucket: SprintBucket | undefined, trigger: HTMLElement) => {
    const target = toTarget(`${row.sprint.teamId}:${row.sprint.sprintId}`, bucket, row.segment, chart.segments);
    if (target) drill.open(target, { trigger, anchor });
  };
  const multiTeam = new Set(rows.map((row) => row.sprint.teamId)).size > 1;
  const columns: ColumnDef<EpicTableRow<S, G>>[] = [
    { key: "sprint", header: t("reports.sprints.sprint"), render: (row) => row.label },
    ...(multiTeam ? [{ key: "team", header: t("reports.sprints.team"), render: (row: EpicTableRow<S, G>) => teamLabel(row.sprint.teamId, filters.teams) }] : []),
    { key: "epic", header: t("reports.epics.column.epic"), render: (row) => <EpicSegmentName segment={row.segment} /> },
    ...figures.map((figure) =>
      drillFigureColumn<EpicTableRow<S, G>>(
        { key: figure.key, header: figure.header, align: "right", render: (row) => figure.render(row.segment) },
        {
          label: (row, shown, column) =>
            t("reports.drill.figureAria", {
              figure: shown,
              column,
              name: [row.label, epicSegmentName(t, row.segment), ...(figure.bucket === undefined ? [] : [t(`reports.drill.bucket.${figure.bucket}`)])].join(" · "),
            }),
          count: (row) => figure.items(row.segment),
          onOpen: (row, trigger) => open(row, figure.bucket, trigger),
          disabled: !drillable,
        },
      ),
    ),
  ];
  return (
    <ReportChartCard
      title={title}
      caption={caption}
      isPending={false}
      isRefreshing={refreshing}
      empty={false}
      hint={t("reports.drill.hint")}
      drillAnchor={anchor}
    >
      <Stack gap="md">
        <Suspense fallback={<LoadingBlock />}>
          <SprintEpicChart
            segments={chart.segments}
            stacks={stacks}
            rows={chart.rows}
            ariaLabel={chartLabel}
            legendLabel={t("reports.epics.legendLabel")}
            onBarDoubleClick={
              drillable
                ? (hit) => {
                    const target = epicHitTarget(hit, chart.segments, stacks, toTarget);
                    if (target) drill.open(target, { anchor });
                  }
                : undefined
            }
          />
        </Suspense>
        <ScrollRegion label={tableLabel} minWidth={multiTeam ? 760 : 640} maxHeight={480}>
          <ColumnTable aria-label={tableLabel} columns={columns} rows={rows} rowKey={(row) => row.key} stickyHeader />
        </ScrollRegion>
        <Text size="xs" c="dimmed">
          {note ?? t("reports.epics.note")}
        </Text>
      </Stack>
    </ReportChartCard>
  );
}
