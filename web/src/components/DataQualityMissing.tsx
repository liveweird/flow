import { useTranslation } from "react-i18next";
import { Text } from "@mantine/core";
import type { DataQualityEpicList, DataQualityEpicRef, DataQualityReport } from "../api/reports";
import { teamName } from "../utils/dataQualityReport";
import { formatDate } from "../utils/formatDate";
import DataQualityCard, { CappedTable, type CardId, type DataQualityScope, type QualityColumn } from "./DataQualityCard";
import DataQualityTaskCard from "./DataQualityTaskCard";

/** The epic rows every epic finding lists (start and due are calendar dates stored as UTC midnight, so they are read in UTC, `doneAt` in the configured zone); the drift finding adds the column naming how the epic disagrees. */
function EpicCard({
  id,
  list,
  scope,
  timeZone,
  withFlags = false,
}: {
  id: CardId;
  list: DataQualityEpicList;
  scope: DataQualityScope;
  timeZone: string;
  withFlags?: boolean;
}) {
  const { t } = useTranslation();
  // Epics carry no user: a member's read has no epic findings, and says so instead of showing "none found".
  if (scope.level === "USER") {
    return <DataQualityCard id={id} state="na" scope={scope} naText={t("reports.epicsNotPerPerson")} />;
  }
  const unowned = t("reports.groups.noOwner");
  const columns: QualityColumn<DataQualityEpicRef>[] = [
    {
      key: "epic",
      header: t("reports.dataQuality.column.epic"),
      render: (epic) => (
        <>
          <Text size="sm" fw={500}>
            {epic.issueKey}
          </Text>
          {epic.summary && (
            <Text size="xs" c="dimmed">
              {epic.summary}
            </Text>
          )}
        </>
      ),
    },
    {
      key: "owner",
      header: t("reports.dataQuality.column.ownerTeam"),
      render: (epic) => teamName(scope.filters, epic.ownerTeamId, unowned),
    },
    { key: "domain", header: t("reports.dataQuality.column.domain"), render: (epic) => epic.domainKey ?? "—" },
    { key: "start", header: t("reports.dataQuality.column.start"), render: (epic) => formatDate(epic.startAt) },
    { key: "due", header: t("reports.dataQuality.column.due"), render: (epic) => formatDate(epic.dueAt) },
    {
      key: "done",
      header: t("reports.dataQuality.column.done"),
      render: (epic) => formatDate(epic.doneAt, t("reports.dataQuality.open"), timeZone),
    },
  ];
  if (withFlags) {
    columns.push({
      key: "flags",
      header: t("reports.dataQuality.column.flags"),
      render: (epic) => epic.flags.map((flag) => t(`reports.dataQuality.flag.${flag}`)).join(", "),
    });
  }
  return (
    <DataQualityCard id={id} state={list.total > 0 ? "found" : "none"} count={list.total} scope={scope}>
      <CappedTable
        label={t(`reports.dataQuality.cards.${id}.title`)}
        columns={columns}
        rows={list.items}
        total={list.total}
        rowKey={(epic) => epic.issueKey}
      />
    </DataQualityCard>
  );
}

/** Missing data: the four task findings, then the three epic findings and the epics that disagree with their children. */
export default function DataQualityMissing({
  report,
  scope,
  timeZone,
}: {
  report: DataQualityReport;
  scope: DataQualityScope;
  timeZone: string;
}) {
  const { t } = useTranslation();
  const { missing } = report;
  return (
    <>
      <DataQualityTaskCard id="noEstimate" finding={missing.noEstimate} scope={scope} timeZone={timeZone} showMd={false} />
      <DataQualityTaskCard id="noEpic" finding={missing.noEpic} scope={scope} timeZone={timeZone} />
      <DataQualityTaskCard
        id="noWorkCategory"
        finding={missing.noWorkCategory}
        scope={scope}
        timeZone={timeZone}
        notMeasured={missing.workCategoryConfigured ? undefined : t("reports.dataQuality.notConfigured")}
      />
      <DataQualityTaskCard id="unassigned" finding={missing.unassigned} scope={scope} timeZone={timeZone} showOpen={false} />
      <EpicCard id="epicsNoEstimate" list={missing.epicsWithoutEstimate} scope={scope} timeZone={timeZone} />
      <EpicCard id="epicsNoDates" list={missing.epicsWithoutDates} scope={scope} timeZone={timeZone} />
      <EpicCard id="epicsHorizon" list={missing.epicsOutsidePvHorizon} scope={scope} timeZone={timeZone} />
      <EpicCard id="epicDrift" list={report.epicDrift} scope={scope} timeZone={timeZone} withFlags />
    </>
  );
}
