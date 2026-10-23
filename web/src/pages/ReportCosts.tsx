import { useTranslation } from "react-i18next";
import { SimpleGrid, Stack, Text } from "@mantine/core";
import { getCostsReport } from "../api/reports";
import CostBreakdownSection from "../components/CostBreakdownSection";
import CostsCategorySections from "../components/CostsCategorySections";
import CostsSprintSections from "../components/CostsSprintSections";
import ReportPageHeader from "../components/ReportPageHeader";
import ReportPageShell from "../components/ReportPageShell";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar, { type ReportControls } from "../components/ReportFilterBar";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportTile from "../components/ReportTile";
import { useReportDrill } from "../hooks/useReportDrill";
import { useReportPage } from "../hooks/useReportPage";
import { costDrillAnchor, isSprintRelative, normalizeCostsFilter } from "../utils/costsReport";
import { formatMd, formatPercent, teamLabel } from "../utils/reportFormat";

// Every slice the report reads has a control; the cost of work is earned-shaped (D3), so the domain view starts on EPIC. The report is read per
// team — never the whole unit (`teamRequired`; a domain may still be picked beside the team).
const CONTROLS: ReportControls = { teamRequired: true, domainView: "EPIC", domain: true, activityType: true, workCategory: true };

/** Report 16 — Costs: the man-days logged in the period and the foreign-work share, split by activity type and by work category, and each closed sprint's actual cost by epic and against capacity. */
export default function ReportCosts() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, filterKey, setFilter, query, storageKey } = useReportPage("costs", getCostsReport, normalizeCostsFilter, CONTROLS);
  const report = query.data;
  const share = report?.foreignShare ?? null;
  // ONE drill for the page's drawer: the donuts' two cards and the sprint widgets share it, and two hook instances would both return focus on close.
  const drill = useReportDrill(filterKey, costDrillAnchor);

  return (
    <Stack gap="md">
      <ReportPageHeader title={t("reports.costs.title")} description={t("reports.costs.description")} filter={filter} filters={filters} controls={CONTROLS} />
      <ReportPageShell
        filtersQuery={filtersQuery}
        filters={filters}
        noTeamsLabel={t("reports.costs.noTeams")}
      >
        {(filters) => (
          <>
            <ReportFilterBar storageKey={storageKey} filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
            {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
            <ReportChartCard
              title={t(`reports.costs.levelTitle.${report?.meta.level ?? "TEAM"}`)}
              caption={report ? t(`reports.costs.domainView.${report.meta.domainView}`) : undefined}
              isPending={query.isPending}
              isRefreshing={query.isPlaceholderData}
              error={query.error}
              empty={report !== undefined && report.byActivityType.length === 0 && report.byWorkCategory.length === 0}
            >
              {report && (
                <Stack gap="md">
                  <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="md">
                    <ReportTile label={t("reports.costs.tile.total")} value={formatMd(report.totalMd)} />
                    <ReportTile label={t("reports.costs.tile.foreign")} value={formatMd(report.foreignMd)} />
                    <ReportTile
                      label={t("reports.costs.tile.share")}
                      value={share === null ? "—" : formatPercent(share)}
                      hint={share === null ? t("reports.costs.foreignNone") : t("reports.costs.shareHint")}
                    />
                  </SimpleGrid>
                  <Stack gap={4}>
                    <Text size="xs" c="dimmed">
                      {t("reports.costs.foreignHint")}
                    </Text>
                    {isSprintRelative(report.meta) && (
                      <Text size="xs" c="dimmed">
                        {t("reports.costs.sprintNote")}
                      </Text>
                    )}
                    <Text size="xs" c="dimmed">
                      {t("reports.costs.rounding")}
                    </Text>
                  </Stack>
                </Stack>
              )}
            </ReportChartCard>
            {report && (
              <CostBreakdownSection
                report={report}
                filter={filter}
                timeZone={filters.timeZone}
                drill={drill}
                drillable={!query.isPlaceholderData}
                isRefreshing={query.isPlaceholderData}
              />
            )}
            {report && (
              <CostsCategorySections
                report={report}
                teamId={filter.teamId}
                teamName={filter.teamId === undefined ? "" : teamLabel(filter.teamId, filters.teams)}
                refreshing={query.isPlaceholderData}
              />
            )}
            {report && (
              <CostsSprintSections report={report} filters={filters} drill={drill} drillable={!query.isPlaceholderData} refreshing={query.isPlaceholderData} />
            )}
          </>
        )}
      </ReportPageShell>
    </Stack>
  );
}
