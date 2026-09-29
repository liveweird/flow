import { useTranslation } from "react-i18next";
import { Stack, Text } from "@mantine/core";
import { getAgingWipReport } from "../api/reports";
import AgingItemsTable from "../components/AgingItemsTable";
import AgingThresholdsRow from "../components/AgingThresholdsRow";
import PageHeader from "../components/PageHeader";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar, { type ReportControls } from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportTabs from "../components/ReportTabs";
import { useReportPage } from "../hooks/useReportPage";
import { withPeriod } from "../utils/reportFilter";
import { FLOW_TABS } from "../utils/reportLinks";

// Aging WIP is "as of now": no period control. It slices by domain, activity type and work category.
const CONTROLS: ReportControls = { noPeriod: true, domain: true, activityType: true, workCategory: true };

/** Report 11 — aging WIP: every task and epic in progress, oldest first, banded against the team's recent cycle times. */
export default function ReportAgingWip() {
  const { t } = useTranslation();
  // The report has no period, so none is sent (a period left in the URL by another report is kept
  // there for the tab links, but a sprint-relative one could only 400 for an unknown id).
  const { filtersQuery, filters, filter, setFilter, query } = useReportPage("aging-wip", (requested) =>
    getAgingWipReport(withPeriod(requested, {})),
  );
  const report = query.data;
  // USER level lists that assignee's tasks and never epics, so no epic row there.
  const showEpics = report !== undefined && report.meta.level !== "USER" && (report.epicThresholds.n > 0 || report.items.some((item) => item.itemKind === "EPIC"));

  return (
    <Stack gap="md">
      <PageHeader title={t("reports.agingWip.title")} description={t("reports.agingWip.description")} />
      <ReportTabs tabs={FLOW_TABS} />
      <ReportFiltersStatus query={filtersQuery} />
      {filters && (
        <>
          <ReportFilterBar filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
          {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
          <ReportChartCard
            title={t("reports.agingWip.thresholdsTitle")}
            caption={t("reports.agingWip.thresholdsCaption")}
            isPending={query.isPending}
            isRefreshing={query.isPlaceholderData}
            error={query.error}
            empty={false}
          >
            {report && (
              <Stack gap="md">
                <AgingThresholdsRow
                  title={t("reports.agingWip.tasksTitle")}
                  thresholds={report.thresholds}
                  minSampleSize={report.meta.minSampleSize}
                />
                {showEpics && (
                  <AgingThresholdsRow
                    title={t("reports.agingWip.epicsTitle")}
                    thresholds={report.epicThresholds}
                    minSampleSize={report.meta.minSampleSize}
                  />
                )}
                {report.meta.level === "UNIT" && (
                  <Text size="xs" c="dimmed">
                    {t("reports.agingWip.unitNote")}
                  </Text>
                )}
                {report.meta.level === "USER" && (
                  <Text size="xs" c="dimmed">
                    {t("reports.agingWip.userNote")}
                  </Text>
                )}
              </Stack>
            )}
          </ReportChartCard>
          {/* A failure is reported ONCE, by the first card. */}
          {!query.isError && (
            <ReportChartCard
              title={t("reports.agingWip.openTitle")}
              caption={t("reports.agingWip.caption")}
              isPending={query.isPending}
              isRefreshing={query.isPlaceholderData}
              empty={false}
            >
              {report &&
                (report.items.length === 0 ? (
                  <Text size="sm" c="dimmed">
                    {t("reports.agingWip.empty")}
                  </Text>
                ) : (
                  <AgingItemsTable report={report} filters={filters} />
                ))}
            </ReportChartCard>
          )}
        </>
      )}
    </Stack>
  );
}
