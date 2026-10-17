import { useTranslation } from "react-i18next";
import { Badge, Group, Text } from "@mantine/core";
import type { ReportFilters } from "../api/reports";
import { formatDate } from "../utils/formatDate";
import { teamLabel } from "../utils/reportFormat";
import type { SprintBucket } from "../utils/reportDrill";
import { sortSprints, type SprintIdentity } from "../utils/reportSprints";
import ColumnTable, { type ColumnDef } from "./ColumnTable";
import { sprintDrillColumn } from "./DrillColumn";
import ScrollRegion from "./ScrollRegion";

export type SprintColumn<S> = ColumnDef<S>;

/**
 * The per-sprint table beside a sprint chart (the chart's table view): sprint, team, completion
 * date (in the configured zone; "Open" for an active sprint), the report's own figure columns,
 * and the drift column — an orange "Drift" badge (the soft-finding hue: the live figures moved
 * since the sprint closed, D13) with the frozen figures as visible text, not a tooltip. With `drill` each row also has an Items
 * button per bucket the chart draws for it (the keyboard route to the chart's double-click).
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
  /** The buckets a sprint row can list the items of, and what opening one does. */
  drill?: {
    buckets: readonly SprintBucket[];
    onDrill: (sprint: S, bucket: SprintBucket, trigger: HTMLElement) => void;
    /** The rows are kept data of a previous filter: the column stays, its buttons are disabled. */
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
    ...columns.map((column) => ({ ...column, align: "right" as const })),
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
    ...(drill ? [sprintDrillColumn<S>(t, (sprint) => sprint.name, drill.buckets, drill.onDrill, drill.disabled)] : []),
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
