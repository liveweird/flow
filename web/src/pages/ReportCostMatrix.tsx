import { useTranslation } from "react-i18next";
import { SimpleGrid, Stack, Text } from "@mantine/core";
import { getCostMatrixReport } from "../api/reports";
import CostMatrixTable from "../components/CostMatrixTable";
import PageHeader from "../components/PageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar, { type ReportControls } from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportTile from "../components/ReportTile";
import { useReportPage } from "../hooks/useReportPage";
import { isSprintRelative, normalizeCostMatrixFilter } from "../utils/costMatrixReport";
import { formatMd, formatPercent } from "../utils/reportFormat";

// Every slice the report reads has a control; the cost of work is earned-shaped (D3), so the domain view starts on EPIC.
const CONTROLS: ReportControls = { domainView: "EPIC", domain: true, activityType: true, workCategory: true, connection: true };

/** Report 16 — cost matrix and foreign work: the man-days logged in the period by author team and domain, with the foreign share beside every row. */
export default function ReportCostMatrix() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, setFilter, query } = useReportPage("cost-matrix", getCostMatrixReport, normalizeCostMatrixFilter);
  const report = query.data;
  const share = report?.foreignShare ?? null;

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.costMatrix.title")} description={t("reports.costMatrix.description")} />
      <ReportFiltersStatus query={filtersQuery} />
      {filters && (
        <>
          <ReportFilterBar filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
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
        </>
      )}
    </Stack>
  );
}
