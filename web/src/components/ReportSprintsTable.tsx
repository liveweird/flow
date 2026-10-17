import { useTranslation } from "react-i18next";
import { Badge, Group, Text } from "@mantine/core";
import type { ReportFilters } from "../api/reports";
import { formatDate } from "../utils/formatDate";
import { teamLabel } from "../utils/reportFormat";
import type { SprintBucket } from "../utils/reportDrill";
import { sortSprints, type SprintIdentity } from "../utils/reportSprints";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import { drillFigureColumn } from "../utils/drillFigure";
import ScrollRegion from "./ScrollRegion";

/**
 * A figure column of the sprint table. With `drill` its figure becomes a button that opens the items of that sprint's `bucket`
 * (`items` = how many the figure stands for; none, no button), so a column is the one place that says which bucket it stands behind.
 */
export type SprintColumn<S> = ColumnDef<S> & { drill?: { bucket: SprintBucket; items: (sprint: S) => number } };

/**
 * The per-sprint table beside a sprint chart (the chart's table view): sprint, team, completion
 * date (in the configured zone; "Open" for an active sprint), the report's own figure columns,
 * and the drift column — an orange "Drift" badge (the soft-finding hue: the live figures moved
 * since the sprint closed, D13) with the frozen figures as visible text, not a tooltip. With `drill` the figures of the columns that
 * name a bucket are buttons that open that bucket's items (the keyboard route to the chart's double-click).
 */
export default function ReportSprintsTable<S extends SprintIdentity & { drift: boolean }>({
  sprints,
  filters,
  columns,
  driftDetail,
  drill,
  minWidth = 760,
}: {
  sprints: readonly S[];
  filters: ReportFilters;
  columns: ReadonlyArray<SprintColumn<S>>;
  /** The frozen figures of a drifted sprint, already translated; null when it has no snapshot. */
  driftDetail: (sprint: S) => string | null;
  /** What opening a bucket's items does (the columns that carry a `drill` call it). */
  drill?: {
    onDrill: (sprint: S, bucket: SprintBucket, trigger: HTMLElement) => void;
    /** The rows are kept data of a previous filter: the figures are plain text, not buttons. */
    disabled?: boolean;
  };
  minWidth?: number;
}) {
  const { t } = useTranslation();
  const columnsWithIdentity: ColumnDef<S>[] = [
    { key: "sprint", header: t("reports.sprints.sprint"), render: (sprint) => sprint.name },
    { key: "team", header: t("reports.sprints.team"), render: (sprint) => teamLabel(sprint.teamId, filters.teams) },
    {
      key: "completed",
      header: t("reports.sprints.completed"),
      render: (sprint) => formatDate(sprint.completedAt, t("reports.sprints.open"), filters.timeZone),
    },
    ...columns.map(({ drill: bucketOf, ...column }): ColumnDef<S> => {
      const right = { ...column, align: "right" as const };
      return drill && bucketOf
        ? drillFigureColumn(right, {
            label: (sprint, figure, column) =>
              t("reports.drill.figureAria", { figure, column, name: `${sprint.name} · ${t(`reports.drill.bucket.${bucketOf.bucket}`)}` }),
            count: bucketOf.items,
            onOpen: (sprint, trigger) => drill.onDrill(sprint, bucketOf.bucket, trigger),
            disabled: drill.disabled,
          })
        : right;
    }),
    {
      key: "drift",
      header: t("reports.sprints.drift"),
      render: (sprint) => {
        const detail = sprint.drift ? driftDetail(sprint) : null;
        return (
          detail !== null && (
            <Group gap="xs" wrap="nowrap">
              <Badge color="orange" variant="light">
                {t("reports.sprints.driftBadge")}
              </Badge>
              <Text size="xs" c="dimmed">
                {detail}
              </Text>
            </Group>
          )
        );
      },
    },
  ];
  return (
    <ScrollRegion label={t("reports.sprints.tableAria")} minWidth={minWidth}>
      <ColumnTable
        aria-label={t("reports.sprints.tableAria")}
        columns={columnsWithIdentity}
        rows={sortSprints(sprints)}
        rowKey={(sprint) => `${sprint.teamId}:${sprint.sprintId}`}
      />
    </ScrollRegion>
  );
}
