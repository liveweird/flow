import { useTranslation } from "react-i18next";
import { Paper, SimpleGrid, Stack, Text } from "@mantine/core";
import { IconUsers } from "@tabler/icons-react";
import { getCostMatrixReport } from "../api/reports";
import CostBreakdownSection from "../components/CostBreakdownSection";
import CostEpicSection from "../components/CostEpicSection";
import CostMatrixTable from "../components/CostMatrixTable";
import EmptyState from "../components/EmptyState";
import ReportPageHeader from "../components/ReportPageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar, { type ReportControls } from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportTile from "../components/ReportTile";
import { useReportDrill } from "../hooks/useReportDrill";
import { useReportPage } from "../hooks/useReportPage";
import { costDrillAnchor, isSprintRelative, normalizeCostMatrixFilter } from "../utils/costMatrixReport";
import { reportQuery } from "../utils/reportFilter";
import { formatMd, formatPercent } from "../utils/reportFormat";

// Every slice the report reads has a control; the cost of work is earned-shaped (D3), so the domain view starts on EPIC. The report is read per
// team — never the whole unit (`teamRequired`; a domain may still be picked beside the team).
const CONTROLS: ReportControls = { teamRequired: true, domainView: "EPIC", domain: true, activityType: true, workCategory: true, split: true };

/** Report 16 — cost matrix and foreign work: the man-days logged in the period by author team and domain, with the foreign share beside every row. */
export default function ReportCostMatrix() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, setFilter, query, storageKey } = useReportPage(
    "cost-matrix",
    getCostMatrixReport,
    normalizeCostMatrixFilter,
    CONTROLS,
  );
  const report = query.data;
  const share = report?.foreignShare ?? null;
  const noTeams = filters !== undefined && filters.teams.length === 0;
  // ONE drill for the page's drawers (the donuts' and the epic table's): two hook instances would both return focus on close.
  const drill = useReportDrill(reportQuery({ ...filter, split: undefined }), costDrillAnchor);

  return (
    <Stack gap="md">
      <ReportPageHeader title={t("reports.costMatrix.title")} description={t("reports.costMatrix.description")} filter={filter} filters={filters} controls={CONTROLS} />
      <ReportFiltersStatus query={filtersQuery} />
      {noTeams && (
        <Paper withBorder p="md">
          <EmptyState icon={IconUsers} label={t("reports.costMatrix.noTeams")} />
        </Paper>
      )}
      {filters && !noTeams && (
        <>
          <ReportFilterBar storageKey={storageKey} filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          <ReportChartCard
            title={t(`reports.costMatrix.levelTitle.${report?.meta.level ?? "UNIT"}`)}
            caption={report ? t(`reports.costMatrix.domainView.${report.meta.domainView}`) : undefined}
            isPending={query.isPending}
            isRefreshing={query.isPlaceholderData}
            error={query.error}
            empty={report !== undefined && report.rows.length === 0}
          >
            {report && (
              <Stack gap="md">
                <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="md">
                  <ReportTile label={t("reports.costMatrix.tile.total")} value={formatMd(report.totalMd)} />
                  <ReportTile label={t("reports.costMatrix.tile.foreign")} value={formatMd(report.foreignMd)} />
                  <ReportTile
                    label={t("reports.costMatrix.tile.share")}
                    value={share === null ? "—" : formatPercent(share)}
                    hint={share === null ? t("reports.costMatrix.foreignNone") : t("reports.costMatrix.shareHint")}
                  />
                </SimpleGrid>
                <CostMatrixTable report={report} filters={filters} />
                <Stack gap={4}>
                  <Text size="xs" c="dimmed">
                    {t("reports.costMatrix.foreignHint")}
                  </Text>
                  {report.capacity && (
                    <Text size="xs" c="dimmed">
                      {t("reports.costMatrix.capacityHint")}{" "}
                      {t("reports.costMatrix.capacityDays", { count: report.capacity.days, calendarDays: report.capacity.calendarDays })}
                    </Text>
                  )}
                  {report.split && (
                    <Text size="xs" c="dimmed">
                      {t(`reports.costMatrix.splitNote.${report.split.kind}`)}
                    </Text>
                  )}
                  {isSprintRelative(report.meta) && (
                    <Text size="xs" c="dimmed">
                      {t(`reports.costMatrix.sprintNote.${report.meta.level === "UNIT" ? "UNIT" : "TEAM"}`)}
                    </Text>
                  )}
                  <Text size="xs" c="dimmed">
                    {t("reports.costMatrix.rounding")}
                  </Text>
                </Stack>
              </Stack>
            )}
          </ReportChartCard>
          {report && report.rows.length > 0 && (
            <>
              <CostEpicSection
                report={report}
                filter={filter}
                timeZone={filters.timeZone}
                drill={drill}
                drillable={!query.isPlaceholderData}
                isRefreshing={query.isPlaceholderData}
              />
              <CostBreakdownSection
                report={report}
                filter={filter}
                timeZone={filters.timeZone}
                drill={drill}
                drillable={!query.isPlaceholderData}
                isRefreshing={query.isPlaceholderData}
              />
            </>
          )}
        </>
      )}
    </Stack>
  );
}
