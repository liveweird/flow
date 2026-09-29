import { useTranslation } from "react-i18next";
import { Stack, Text, Title } from "@mantine/core";
import type { DataQualityLateWorklog, DataQualityReport } from "../api/reports";
import { teamName } from "../utils/dataQualityReport";
import { formatDate } from "../utils/formatDate";
import { formatDays, formatMd, formatOneDecimal, formatPercent } from "../utils/reportFormat";
import DataQualityCard, { CappedTable, QualityStats, type DataQualityScope, type QualityColumn } from "./DataQualityCard";
import DataQualityTaskCard from "./DataQualityTaskCard";
import DistributionPanel from "./DistributionPanel";

const MISSING = "—";

function CoverageCard({ report, scope, timeZone }: { report: DataQualityReport; scope: DataQualityScope; timeZone: string }) {
  const { t } = useTranslation();
  const { doneTasks, withWorklogs, coverage, without } = report.worklogCoverage;
  return (
    <DataQualityTaskCard
      id="coverage"
      finding={without}
      scope={scope}
      timeZone={timeZone}
      notMeasured={coverage === null ? t("reports.dataQuality.coverageNone") : undefined}
      stats={[
        [t("reports.dataQuality.stat.doneTasks"), String(doneTasks)],
        [t("reports.dataQuality.stat.withWorklogs"), String(withWorklogs)],
        [t("reports.dataQuality.stat.coverage"), coverage === null ? MISSING : formatPercent(coverage)],
      ]}
    />
  );
}

/** Hours logged per member-day: a figure to read against the configured man-day, not a finding. */
function HoursCard({ report, scope }: { report: DataQualityReport; scope: DataQualityScope }) {
  const { t } = useTranslation();
  const { memberDays, hours, hoursPerMemberDay } = report.loggedHours;
  return (
    <DataQualityCard id="hours" state="info" scope={scope}>
      {hoursPerMemberDay === null && (
        <Text size="sm" c="dimmed">
          {t("reports.dataQuality.hoursNone")}
        </Text>
      )}
      <QualityStats
        items={[
          [t("reports.dataQuality.stat.memberDays"), formatOneDecimal(memberDays)],
          [t("reports.dataQuality.stat.hours"), formatOneDecimal(hours)],
          [
            t("reports.dataQuality.stat.perMemberDay"),
            hoursPerMemberDay === null ? MISSING : t("reports.dataQuality.stat.hoursUnit", { hours: formatOneDecimal(hoursPerMemberDay) }),
          ],
          [
            t("reports.dataQuality.stat.configuredDay"),
            t("reports.dataQuality.stat.hoursUnit", { hours: formatOneDecimal(report.hoursPerDay) }),
          ],
        ]}
      />
    </DataQualityCard>
  );
}

function LateCard({ report, scope, timeZone }: { report: DataQualityReport; scope: DataQualityScope; timeZone: string }) {
  const { t } = useTranslation();
  const late = report.lateLogging;
  const unassigned = t("reports.groups.unassigned");
  const columns: QualityColumn<DataQualityLateWorklog>[] = [
    {
      key: "task",
      header: t("reports.dataQuality.column.task"),
      render: (worklog) => (
        <>
          <Text size="sm" fw={500}>
            {worklog.issueKey}
          </Text>
          {worklog.summary && (
            <Text size="xs" c="dimmed">
              {worklog.summary}
            </Text>
          )}
        </>
      ),
    },
    {
      key: "author",
      header: t("reports.dataQuality.column.author"),
      render: (worklog) => worklog.author ?? t("reports.dataQuality.unknownAuthor"),
    },
    { key: "team", header: t("reports.dataQuality.column.team"), render: (worklog) => teamName(scope.filters, worklog.teamId, unassigned) },
    {
      key: "started",
      header: t("reports.dataQuality.column.loggedAt"),
      render: (worklog) => formatDate(worklog.startedAt, MISSING, timeZone),
    },
    {
      key: "lateDays",
      header: t("reports.dataQuality.column.lateDays"),
      align: "right",
      render: (worklog) => formatDays(worklog.lateDays),
    },
  ];
  // Nothing to measure is not "none found": no worklogs at all, or none with a known logging time.
  if (late.worklogs === 0 || late.measurable === 0) {
    const reason = late.worklogs === 0 ? "noWorklogs" : "noneMeasurable";
    return <DataQualityCard id="late" state="na" scope={scope} naText={t(`reports.dataQuality.late.${reason}`)} />;
  }
  return (
    <DataQualityCard id="late" state={late.over1Day > 0 ? "found" : "none"} count={late.over1Day} scope={scope}>
      <QualityStats
        items={[
          [t("reports.dataQuality.stat.worklogs"), String(late.worklogs)],
          [t("reports.dataQuality.stat.measurable"), String(late.measurable)],
          [t("reports.dataQuality.stat.over1"), String(late.over1Day)],
          [t("reports.dataQuality.stat.over7"), String(late.over7Days)],
        ]}
      />
      <DistributionPanel
        title={t("reports.dataQuality.late.distributionTitle")}
        caption={t("reports.dataQuality.late.distributionCaption")}
        distribution={late.distribution}
        minSampleSize={report.meta.minSampleSize}
        format={formatDays}
        axisLabel={t("reports.dataQuality.late.axis")}
      />
      {late.worst.length > 0 && (
        <Stack gap="xs">
          <Stack gap={0}>
            <Title order={4} size="h5">
              {t("reports.dataQuality.late.worstTitle")}
            </Title>
            <Text size="xs" c="dimmed">
              {t("reports.dataQuality.late.worstCaption")}
            </Text>
          </Stack>
          <CappedTable
            label={t("reports.dataQuality.late.worstTitle")}
            columns={columns}
            rows={late.worst}
            rowKey={(worklog) => String(worklog.worklogId)}
          />
        </Stack>
      )}
    </DataQualityCard>
  );
}

function AuthorsCard({ report, scope }: { report: DataQualityReport; scope: DataQualityScope }) {
  const { t } = useTranslation();
  const list = report.authorsWithoutTeam;
  return (
    <DataQualityCard id="authors" state={list.total > 0 ? "found" : "none"} count={list.total} scope={scope} scopeNote="noTeam">
      <CappedTable
        label={t("reports.dataQuality.cards.authors.title")}
        columns={[
          {
            key: "author",
            header: t("reports.dataQuality.column.author"),
            render: (author) => author.name ?? author.accountId ?? t("reports.dataQuality.unknownAuthor"),
          },
          { key: "worklogs", header: t("reports.dataQuality.column.worklogs"), align: "right", render: (author) => author.worklogs },
          { key: "md", header: t("reports.dataQuality.column.md"), align: "right", render: (author) => formatMd(author.md) },
        ]}
        rows={list.items}
        total={list.total}
        rowKey={(author, index) => author.accountId ?? `none-${index}`}
      />
    </DataQualityCard>
  );
}

/** Coverage and logging: are the worklogs there, are they logged on time, and by people in a team. */
export default function DataQualityLogging({
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
      <CoverageCard report={report} scope={scope} timeZone={timeZone} />
      <HoursCard report={report} scope={scope} />
      <LateCard report={report} scope={scope} timeZone={timeZone} />
      <AuthorsCard report={report} scope={scope} />
    </>
  );
}
