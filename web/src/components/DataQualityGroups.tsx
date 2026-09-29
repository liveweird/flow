import { useTranslation } from "react-i18next";
import { Stack, Text } from "@mantine/core";
import type { DataQualityGroup, DataQualityReport, ReportFilters } from "../api/reports";
import { formatOneDecimal } from "../utils/reportFormat";
import ReportGroupsTable, { type GroupColumn } from "./ReportGroupsTable";

/**
 * The next org level's counts: one row per team (UNIT) or member (TEAM), the row name drilling the same
 * report to it. Estimate and epic counts add the done and the open tasks; the epic columns exist only at UNIT
 * level (`epics` is null per member — epics carry no user). Σ over the rows equals the matching headline count.
 */
export default function DataQualityGroups({ report, filters }: { report: DataQualityReport; filters: ReportFilters }) {
  const { t } = useTranslation();
  const both = (counts: { done: number; open: number }) => counts.done + counts.open;
  const columns: GroupColumn<DataQualityGroup>[] = [
    { key: "done", header: t("reports.dataQuality.groups.column.done"), render: (g) => g.tasks.done },
    { key: "open", header: t("reports.dataQuality.groups.column.open"), render: (g) => g.tasks.openStarted },
    { key: "withoutWorklogs", header: t("reports.dataQuality.groups.column.withoutWorklogs"), render: (g) => g.tasks.withoutWorklogs },
    { key: "noEstimate", header: t("reports.dataQuality.groups.column.noEstimate"), render: (g) => both(g.tasks.noEstimate) },
    { key: "noEpic", header: t("reports.dataQuality.groups.column.noEpic"), render: (g) => both(g.tasks.noEpic) },
    { key: "unassigned", header: t("reports.dataQuality.groups.column.unassigned"), render: (g) => g.tasks.unassigned },
    { key: "outsideSprint", header: t("reports.dataQuality.groups.column.outsideSprint"), render: (g) => g.tasks.outsideSprint },
    { key: "over1", header: t("reports.dataQuality.groups.column.over1"), render: (g) => g.worklogs.over1Day },
    { key: "over7", header: t("reports.dataQuality.groups.column.over7"), render: (g) => g.worklogs.over7Days },
    {
      key: "perMemberDay",
      header: t("reports.dataQuality.groups.column.perMemberDay"),
      render: (g) => (g.worklogs.hoursPerMemberDay === null ? "—" : formatOneDecimal(g.worklogs.hoursPerMemberDay)),
    },
  ];
  if (report.groups.some((g) => g.epics !== null)) {
    columns.push(
      { key: "epics", header: t("reports.dataQuality.groups.column.epics"), render: (g) => g.epics?.epics ?? "—" },
      {
        key: "epicFindings",
        header: t("reports.dataQuality.groups.column.epicFindings"),
        render: (g) =>
          g.epics === null ? "—" : g.epics.withoutEstimate + g.epics.withoutDates + g.epics.outsidePvHorizon + g.epics.drifting,
      },
    );
  }
  return (
    <Stack gap="xs">
      <ReportGroupsTable level={report.meta.level} filters={filters} groups={report.groups} columns={columns} />
      {report.groups.length > 0 && (
        <Text size="xs" c="dimmed">
          {t("reports.dataQuality.groups.caption")}
        </Text>
      )}
    </Stack>
  );
}
