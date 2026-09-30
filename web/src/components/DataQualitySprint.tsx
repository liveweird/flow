import { useTranslation } from "react-i18next";
import { Badge, Text } from "@mantine/core";
import type { DataQualityReport, DataQualitySnapshotDrift } from "../api/reports";
import { formatDriftDelta, formatDriftValue } from "../utils/dataQualityReport";
import { formatDate } from "../utils/formatDate";
import { teamLabel } from "../utils/reportFormat";
import DataQualityCard, { CappedTable, type DataQualityScope, type QualityColumn } from "./DataQualityCard";
import DataQualityTaskCard from "./DataQualityTaskCard";

/** D13: every figure of a closed sprint whose live value has left the frozen one — the reconstructed baselines marked. */
function SnapshotDriftCard({ report, scope, timeZone }: { report: DataQualityReport; scope: DataQualityScope; timeZone: string }) {
  const { t } = useTranslation();
  const list = report.snapshotDrift;
  // Sprint findings belong to a team's sprints: a member's read has none.
  if (scope.level === "USER") {
    return <DataQualityCard id="snapshotDrift" state="na" scope={scope} naText={t("reports.dataQuality.noSprintPerPerson")} />;
  }
  const columns: QualityColumn<DataQualitySnapshotDrift>[] = [
    {
      key: "sprint",
      header: t("reports.dataQuality.column.sprint"),
      render: (row) => (
        <>
          <Text size="sm" fw={500}>
            {row.name}
          </Text>
          <Text size="xs" c="dimmed">
            {teamLabel(row.teamId, scope.filters.teams)}
          </Text>
        </>
      ),
    },
    { key: "closed", header: t("reports.dataQuality.column.closed"), render: (row) => formatDate(row.completeAt, "—", timeZone) },
    { key: "figure", header: t("reports.dataQuality.column.figure"), render: (row) => t(`reports.dataQuality.figure.${row.field}`) },
    { key: "live", header: t("reports.dataQuality.column.live"), align: "right", render: (row) => formatDriftValue(row.field, row.live) },
    { key: "frozen", header: t("reports.dataQuality.column.frozen"), align: "right", render: (row) => formatDriftValue(row.field, row.frozen) },
    { key: "delta", header: t("reports.dataQuality.column.delta"), align: "right", render: (row) => formatDriftDelta(row.field, row.delta) },
    {
      key: "baseline",
      header: t("reports.dataQuality.column.baseline"),
      render: (row) =>
        row.reconstructed ? (
          <Badge color="orange" variant="light">
            {t("reports.dataQuality.baseline.reconstructed")}
          </Badge>
        ) : (
          t("reports.dataQuality.baseline.frozen")
        ),
    },
  ];
  return (
    <DataQualityCard id="snapshotDrift" state={list.total > 0 ? "found" : "none"} count={list.total} scope={scope}>
      <CappedTable
        label={t("reports.dataQuality.cards.snapshotDrift.title")}
        columns={columns}
        rows={list.items}
        total={list.total}
        rowKey={(row) => `${row.connectionId}:${row.sprintId}:${row.field}`}
        note={
          list.items.some((row) => row.reconstructed) ? (
            <Text size="xs" c="dimmed">
              {t("reports.dataQuality.baseline.reconstructedNote")}
            </Text>
          ) : undefined
        }
      />
    </DataQualityCard>
  );
}

/** Flow and sprint: work done outside a sprint, tasks in another domain than their epic, and the sprint-snapshot drift. */
export default function DataQualitySprint({
  report,
  scope,
  timeZone,
}: {
  report: DataQualityReport;
  scope: DataQualityScope;
  timeZone: string;
}) {
  return (
    <>
      <DataQualityTaskCard id="outsideSprint" finding={report.outsideSprint} scope={scope} timeZone={timeZone} showOpen={false} />
      <DataQualityTaskCard id="crossDomain" finding={report.crossDomain} scope={scope} timeZone={timeZone} showOpen={false} />
      <SnapshotDriftCard report={report} scope={scope} timeZone={timeZone} />
    </>
  );
}
