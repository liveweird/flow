import { useTranslation } from "react-i18next";
import { Text } from "@mantine/core";
import type { DataQualityEpicGap, DataQualityEpicRef, DataQualityReport } from "../api/reports";
import { teamName } from "../utils/dataQualityReport";
import { formatDate } from "../utils/formatDate";
import { formatMd, formatSignedMd } from "../utils/reportFormat";
import DataQualityCard, { CappedTable, type CardId, type DataQualityScope, type QualityColumn } from "./DataQualityCard";
import DataQualityTaskCard from "./DataQualityTaskCard";
import JiraKeyLink from "./JiraKeyLink";

const asEpic = (epic: DataQualityEpicRef): DataQualityEpicRef => epic;

/**
 * The epic rows every epic finding lists (start and due are calendar dates stored as UTC midnight, so they are read in UTC, `doneAt` in the configured zone). A finding whose rows
 * wrap the epic (the estimate gap) passes `epicOf`; `extraColumns` add what only that finding knows (the drift finding names how the epic disagrees, the gap its figures).
 */
function EpicCard<R>({
  id,
  list,
  epicOf,
  scope,
  timeZone,
  extraColumns = [],
  explainValues,
}: {
  id: CardId;
  list: { total: number; items: ReadonlyArray<R> };
  epicOf: (row: R) => DataQualityEpicRef;
  scope: DataQualityScope;
  timeZone: string;
  extraColumns?: QualityColumn<R>[];
  explainValues?: Record<string, string | number>;
}) {
  const { t } = useTranslation();
  // Epics carry no user: a member's read has no epic findings, and says so instead of showing "none found".
  if (scope.level === "USER") {
    return <DataQualityCard id={id} state="na" scope={scope} naText={t("reports.epicsNotPerPerson")} explainValues={explainValues} />;
  }
  const unowned = t("reports.groups.noOwner");
  const columns: QualityColumn<R>[] = [
    {
      key: "epic",
      header: t("reports.dataQuality.column.epic"),
      render: (row) => (
        <>
          <Text size="sm" fw={500}>
            <JiraKeyLink issueKey={epicOf(row).issueKey} />
          </Text>
          {epicOf(row).summary && (
            <Text size="xs" c="dimmed">
              {epicOf(row).summary}
            </Text>
          )}
        </>
      ),
    },
    {
      key: "owner",
      header: t("reports.dataQuality.column.ownerTeam"),
      render: (row) => teamName(scope.filters, epicOf(row).ownerTeamId, unowned),
    },
    { key: "domain", header: t("reports.dataQuality.column.domain"), render: (row) => epicOf(row).domainKey ?? "—" },
    { key: "start", header: t("reports.dataQuality.column.start"), render: (row) => formatDate(epicOf(row).startAt) },
    { key: "due", header: t("reports.dataQuality.column.due"), render: (row) => formatDate(epicOf(row).dueAt) },
    {
      key: "done",
      header: t("reports.dataQuality.column.done"),
      render: (row) => formatDate(epicOf(row).doneAt, t("reports.dataQuality.open"), timeZone),
    },
    ...extraColumns,
  ];
  return (
    <DataQualityCard id={id} state={list.total > 0 ? "found" : "none"} count={list.total} scope={scope} explainValues={explainValues}>
      <CappedTable
        label={t(`reports.dataQuality.cards.${id}.title`)}
        columns={columns}
        rows={list.items}
        total={list.total}
        rowKey={(row) => epicOf(row).issueKey}
      />
    </DataQualityCard>
  );
}

/** Missing data: the six task findings, then the three epic findings, the epics that disagree with their children and the epics whose estimate disagrees with their tasks'. */
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
  const driftColumns: QualityColumn<DataQualityEpicRef>[] = [
    {
      key: "flags",
      header: t("reports.dataQuality.column.flags"),
      render: (epic) => epic.flags.map((flag) => t(`reports.dataQuality.flag.${flag}`)).join(", "),
    },
  ];
  const gapColumns: QualityColumn<DataQualityEpicGap>[] = [
    { key: "ownEstimate", header: t("reports.dataQuality.column.ownEstimate"), align: "right", render: (gap) => formatMd(gap.ownEstimateMd) },
    { key: "childSum", header: t("reports.dataQuality.column.childSum"), align: "right", render: (gap) => formatMd(gap.childSumMd) },
    { key: "gap", header: t("reports.dataQuality.column.gap"), align: "right", render: (gap) => formatSignedMd(gap.gapMd) },
    { key: "direction", header: t("reports.dataQuality.column.direction"), render: (gap) => t(`reports.dataQuality.direction.${gap.direction}`) },
  ];
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
      <DataQualityTaskCard id="estimatedLate" finding={missing.estimatedLate} scope={scope} timeZone={timeZone} />
      <DataQualityTaskCard
        id="estimatedAfterDone"
        finding={missing.estimatedAfterDone}
        scope={scope}
        timeZone={timeZone}
        showOpen={false}
      />
      <EpicCard id="epicsNoEstimate" list={missing.epicsWithoutEstimate} epicOf={asEpic} scope={scope} timeZone={timeZone} />
      <EpicCard id="epicsNoDates" list={missing.epicsWithoutDates} epicOf={asEpic} scope={scope} timeZone={timeZone} />
      <EpicCard id="epicsHorizon" list={missing.epicsOutsidePvHorizon} epicOf={asEpic} scope={scope} timeZone={timeZone} />
      <EpicCard id="epicDrift" list={report.epicDrift} epicOf={asEpic} scope={scope} timeZone={timeZone} extraColumns={driftColumns} />
      <EpicCard
        id="epicEstimateGap"
        list={report.epicEstimateGap}
        epicOf={(gap) => gap.epic}
        scope={scope}
        timeZone={timeZone}
        extraColumns={gapColumns}
        explainValues={{ percent: report.epicEstimateGapTolerancePercent }}
      />
    </>
  );
}
