import { useTranslation } from "react-i18next";
import { Text } from "@mantine/core";
import type { DataQualityTaskFinding, DataQualityTaskRef } from "../api/reports";
import { teamName } from "../utils/dataQualityReport";
import { formatDate } from "../utils/formatDate";
import { formatMd } from "../utils/reportFormat";
import DataQualityCard, {
  CappedTable,
  QualityStats,
  type CardId,
  type DataQualityScope,
  type QualityColumn,
  type ScopeNote,
} from "./DataQualityCard";

/** The one row shape every task finding lists: the task, its team and assignee, when it was done and started, its estimate. */
function TaskRefTable({
  label,
  finding,
  scope,
  timeZone,
}: {
  label: string;
  finding: DataQualityTaskFinding;
  scope: DataQualityScope;
  timeZone: string;
}) {
  const { t } = useTranslation();
  const unassigned = t("reports.groups.unassigned");
  const columns: QualityColumn<DataQualityTaskRef>[] = [
    {
      key: "task",
      header: t("reports.dataQuality.column.task"),
      render: (task) => (
        <>
          <Text size="sm" fw={500}>
            {task.issueKey}
          </Text>
          {task.summary && (
            <Text size="xs" c="dimmed">
              {task.summary}
            </Text>
          )}
        </>
      ),
    },
    { key: "team", header: t("reports.dataQuality.column.team"), render: (task) => teamName(scope.filters, task.teamId, unassigned) },
    {
      key: "assignee",
      header: t("reports.dataQuality.column.assignee"),
      render: (task) => task.assignee ?? t("reports.groups.unassigned"),
    },
    {
      key: "done",
      header: t("reports.dataQuality.column.done"),
      render: (task) => (task.doneAt === null ? t("reports.dataQuality.open") : formatDate(task.doneAt, "—", timeZone)),
    },
    {
      key: "started",
      header: t("reports.dataQuality.column.started"),
      render: (task) => formatDate(task.startedAt, "—", timeZone),
    },
    {
      key: "estimate",
      header: t("reports.dataQuality.column.estimate"),
      align: "right",
      render: (task) => (task.estimateMd === null ? "—" : formatMd(task.estimateMd)),
    },
  ];
  return (
    <CappedTable
      label={label}
      columns={columns}
      rows={finding.items}
      total={finding.total}
      rowKey={(task) => `${task.issueKey}:${task.doneAt ?? "open"}`}
    />
  );
}

/**
 * A task finding: the done/open split and the estimate the tasks carry, then the capped list. `showOpen` is
 * off for a finding that only makes sense once a task is done (its `open` is always 0); `showMd` is off where
 * the estimate is the finding itself (no estimate). `notMeasured` swaps the card for its reason (`na`).
 */
export default function DataQualityTaskCard({
  id,
  finding,
  scope,
  timeZone,
  showOpen = true,
  showMd = true,
  scopeNote,
  notMeasured,
  stats,
}: {
  id: CardId;
  finding: DataQualityTaskFinding;
  scope: DataQualityScope;
  timeZone: string;
  showOpen?: boolean;
  showMd?: boolean;
  scopeNote?: ScopeNote;
  notMeasured?: string;
  /** Replaces the default done/open/estimate figures (coverage states its own). */
  stats?: ReadonlyArray<readonly [string, string]>;
}) {
  const { t } = useTranslation();
  const figures: [string, string][] = [[t("reports.dataQuality.stat.done"), String(finding.done)]];
  if (showOpen) figures.push([t("reports.dataQuality.stat.open"), String(finding.open)]);
  if (showMd) figures.push([t("reports.dataQuality.stat.estimate"), formatMd(finding.md)]);
  return (
    <DataQualityCard
      id={id}
      state={notMeasured !== undefined ? "na" : finding.total > 0 ? "found" : "none"}
      count={finding.total}
      scope={scope}
      scopeNote={scopeNote}
      naText={notMeasured}
    >
      {(finding.total > 0 || stats !== undefined) && <QualityStats items={stats ?? figures} />}
      <TaskRefTable label={t(`reports.dataQuality.cards.${id}.title`)} finding={finding} scope={scope} timeZone={timeZone} />
    </DataQualityCard>
  );
}
