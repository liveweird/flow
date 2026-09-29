import { useTranslation } from "react-i18next";
import { Box, Stack } from "@mantine/core";
import { getDataQualityReport } from "../api/reports";
import { useAdmin } from "../auth";
import DataQualityConfig from "../components/DataQualityConfig";
import DataQualityGroups from "../components/DataQualityGroups";
import DataQualityLogging from "../components/DataQualityLogging";
import DataQualityMissing from "../components/DataQualityMissing";
import DataQualitySprint from "../components/DataQualitySprint";
import DataQualitySummary from "../components/DataQualitySummary";
import type { DataQualityScope } from "../components/DataQualityCard";
import PageHeader from "../components/PageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar, { type ReportControls } from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportMetaNote from "../components/ReportMetaNote";
import { useReportPage } from "../hooks/useReportPage";
import { normalizeDataQualityFilter } from "../utils/dataQualityReport";

// The findings slice by domain (under the chosen domain view, delivered in by default) and by connection;
// an activity type or work category is accepted by the server but has no control here.
const CONTROLS: ReportControls = { domainView: "TASK", domain: true, connection: true };

/** Report 14 — data quality: where the data the other reports stand on is missing or inconsistent, one card per finding. */
export default function ReportDataQuality() {
  const { t } = useTranslation();
  const admin = useAdmin();
  const { filtersQuery, filters, filter, setFilter, query } = useReportPage(
    "data-quality",
    getDataQualityReport,
    normalizeDataQualityFilter,
  );
  const report = query.data;
  const scope: DataQualityScope | undefined =
    report && filters
      ? { filters, level: report.meta.level, teamScoped: filter.teamId !== undefined && filter.teamId > 0, admin }
      : undefined;

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.dataQuality.title")} description={t("reports.dataQuality.description")} />
      <ReportFiltersStatus query={filtersQuery} />
      {filters && (
        <>
          <ReportFilterBar filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          <ReportChartCard
            title={t("reports.dataQuality.summary.title")}
            isPending={query.isPending}
            isRefreshing={query.isPlaceholderData}
            error={query.error}
            empty={false}
          >
            {report && <DataQualitySummary report={report} />}
          </ReportChartCard>
          {report && scope && (
            <Box aria-busy={query.isPlaceholderData} opacity={query.isPlaceholderData ? 0.5 : 1}>
              <Stack gap="md" role="group" aria-label={t("reports.dataQuality.cardsLabel")}>
                <DataQualityLogging report={report} scope={scope} timeZone={filters.timeZone} />
                <DataQualityMissing report={report} scope={scope} timeZone={filters.timeZone} />
                <DataQualitySprint report={report} scope={scope} timeZone={filters.timeZone} />
                <DataQualityConfig report={report} scope={scope} timeZone={filters.timeZone} />
                <DataQualityGroups report={report} filters={filters} />
              </Stack>
            </Box>
          )}
        </>
      )}
    </Stack>
  );
}
