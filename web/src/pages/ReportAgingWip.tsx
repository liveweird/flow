import { useState } from "react";
import { useTranslation } from "react-i18next";
import { SegmentedControl, Stack, Text } from "@mantine/core";
import { getAgingWipReport } from "../api/reports";
import AgingChartSection from "../components/AgingChartSection";
import AgingItemsTable from "../components/AgingItemsTable";
import AgingThresholdsRow from "../components/AgingThresholdsRow";
import ReportPageHeader from "../components/ReportPageHeader";
import ReportPageShell from "../components/ReportPageShell";
import ReportChartCard from "../components/ReportChartCard";
import ReportFilterBar, { type ReportControls } from "../components/ReportFilterBar";
import ReportMetaNote from "../components/ReportMetaNote";
import { useAgingKind } from "../hooks/useAgingKind";
import { useReportPage } from "../hooks/useReportPage";
import { offersEpics } from "../utils/agingReport";
import { withPeriod } from "../utils/reportFilter";
import { FLOW_TABS } from "../utils/reportLinks";

// Aging WIP is "as of now": no period control. It slices by domain, activity type and work category, and is read per team — never the whole unit
// (`teamRequired`; a domain may still be picked beside the team).
const CONTROLS: ReportControls = { teamRequired: true, noPeriod: true, domain: true, activityType: true, workCategory: true };

/** Report 11 — aging WIP: every task and epic in progress, oldest first, banded against the team's recent cycle times. */
export default function ReportAgingWip() {
  const { t } = useTranslation();
  // The report has no period, so none is sent (a period left in the URL by another report is kept
  // there for the tab links, but a sprint-relative one could only 400 for an unknown id).
  const { filtersQuery, filters, filter, setFilter, query, storageKey } = useReportPage(
    "aging-wip",
    (requested) => getAgingWipReport(withPeriod(requested, {})),
    undefined,
    CONTROLS,
  );
  const report = query.data;
  // The item a click on a chart dot picked: its table row is highlighted (clicking the same dot again lets go).
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  // The page's one Tasks | Epics switch (URL state, never a request param): the response carries both kinds and the page shows one.
  // Where epics are not offered (USER level lists that assignee's tasks and never epics) the switch is hidden and a `kind=epics` link reads as Tasks.
  const { kind: requestedKind, setKind } = useAgingKind();
  const showEpics = report !== undefined && offersEpics(report);
  const kind = showEpics ? requestedKind : "TASK";
  const shownCount = report?.items.filter((item) => item.itemKind === kind).length ?? 0;

  return (
    <Stack gap="md">
      <ReportPageHeader
        title={t("reports.agingWip.title")}
        description={t("reports.agingWip.description")}
        filter={filter}
        filters={filters}
        controls={CONTROLS}
        extraLines={[{ label: t("reports.filters.itemKind"), value: t(`reports.filters.itemKindOption.${kind}`) }]}
      />
      <ReportPageShell
        tabs={FLOW_TABS}
        filtersQuery={filtersQuery}
        filters={filters}
        noTeamsLabel={t("reports.agingWip.noTeams")}
      >
        {(filters) => (
          <>
            <ReportFilterBar storageKey={storageKey} filters={filters} filter={filter} onChange={setFilter} controls={CONTROLS} />
            {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
            {showEpics && (
              <SegmentedControl
                data-print="hide"
                w="fit-content"
                aria-label={t("reports.agingWip.kindAria")}
                value={kind}
                onChange={(value) => {
                  setKind(value === "EPIC" ? "EPIC" : "TASK");
                  setSelectedKey(null);
                }}
                data={(["TASK", "EPIC"] as const).map((value) => ({ value, label: t(`reports.filters.itemKindOption.${value}`) }))}
              />
            )}
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
                    title={t(kind === "EPIC" ? "reports.agingWip.epicsTitle" : "reports.agingWip.tasksTitle")}
                    thresholds={kind === "EPIC" ? report.epicThresholds : report.thresholds}
                    minSampleSize={report.meta.minSampleSize}
                  />
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
            {!query.isError && report && shownCount > 0 && (
              <ReportChartCard
                title={t("reports.agingWip.chart.title")}
                caption={t("reports.agingWip.chart.caption")}
                isPending={query.isPending}
                isRefreshing={query.isPlaceholderData}
                empty={false}
              >
                <AgingChartSection
                  report={report}
                  kind={kind}
                  selectedKey={selectedKey}
                  onSelect={(key) => setSelectedKey((current) => (current === key ? null : key))}
                />
              </ReportChartCard>
            )}
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
                  ) : shownCount === 0 ? (
                    <Text size="sm" c="dimmed">
                      {t(
                        kind === "EPIC"
                          ? report.itemsTruncated ? "reports.agingWip.emptyEpicsTruncated" : "reports.agingWip.emptyEpics"
                          : report.itemsTruncated ? "reports.agingWip.emptyTasksTruncated" : "reports.agingWip.emptyTasks",
                        { count: report.items.length },
                      )}
                    </Text>
                  ) : (
                    <AgingItemsTable report={report} kind={kind} filters={filters} selectedKey={selectedKey} />
                  ))}
              </ReportChartCard>
            )}
          </>
        )}
      </ReportPageShell>
    </Stack>
  );
}
