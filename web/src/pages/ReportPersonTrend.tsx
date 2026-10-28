import { useTranslation } from "react-i18next";
import { Paper, Stack, Text } from "@mantine/core";
import { IconChartLine } from "@tabler/icons-react";
import { getPersonTrendReport } from "../api/reports";
import EmptyState from "../components/EmptyState";
import ErrorAlert from "../components/ErrorAlert";
import LoadingBlock from "../components/LoadingBlock";
import PersonTrendDrillDrawer from "../components/PersonTrendDrillDrawer";
import PersonTrendPicker from "../components/PersonTrendPicker";
import PersonTrendSection from "../components/PersonTrendSection";
import ReportFilterBar from "../components/ReportFilterBar";
import ReportFiltersStatus from "../components/ReportFiltersStatus";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportNote from "../components/ReportNote";
import ReportPageHeader from "../components/ReportPageHeader";
import { useReportDrill } from "../hooks/useReportDrill";
import { useReportPage } from "../hooks/useReportPage";
import { normalizePersonTrendFilter, PERSON_TREND_CONTROLS, reportHasReference, trendAnchorOfRow } from "../utils/personTrendReport";
import type { DrillTarget } from "../utils/reportDrill";
import { requiredPeople, withFilterKey } from "../utils/reportFilter";

/** The chart card focus returns to after a point's drill closes: the one that draws the point's row. */
function anchorOf(target: DrillTarget): string {
  return trendAnchorOfRow(target.kind === "point" ? target.row : "");
}

/**
 * Report 20 — Person trend: one person's Compare-people figures over rolling 90-day windows (a point every 14 days) across a year, across every
 * team they belonged to. The person is picked beside the filter bar (their `accountId` IS the URL, remembered like any filter); until then the
 * page is the picker and an empty state and nothing is requested. Then one chart per figure with the first-versus-last cue, and the work mix as
 * stacked shares. Open dots are windows that only partly lie in the derived span; gaps are windows with no figure, never a zero. A dot, and a figure
 * of a chart's numbers table, opens the items behind that point in a drawer (`drill=point:<row>:<window end>` in the URL).
 */
export default function ReportPersonTrend() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, filterKey, setFilter, query, storageKey } = useReportPage(
    "person-trend",
    getPersonTrendReport,
    normalizePersonTrendFilter,
    PERSON_TREND_CONTROLS,
  );
  const accountIds = filter.accountIds ?? [];
  const picked = accountIds.length === requiredPeople(PERSON_TREND_CONTROLS);
  // Without a person no report shows (kept data of an earlier person would answer a question nobody asks); while a new person loads, the previous
  // one's report stays on screen dimmed (`isPlaceholderData`).
  const report = picked ? query.data : undefined;
  const names = report === undefined ? {} : { [report.person.accountId]: report.person.label };
  // While another person's or range's report loads, the previous one stays on screen dimmed and its points are not drillable.
  const drillable = report !== undefined && !query.isPlaceholderData;
  const drill = useReportDrill(filterKey, anchorOf);
  const openPoint = (row: string, end: string, trigger?: HTMLElement) => drill.open({ kind: "point", row, end }, { trigger, anchor: trendAnchorOfRow(row) });

  return (
    <Stack gap="md">
      <ReportPageHeader title={t("reports.personTrend.title")} description={t("reports.personTrend.description")} filter={filter} filters={filters} controls={PERSON_TREND_CONTROLS} />
      <ReportFiltersStatus query={filtersQuery} />
      {filters && (
        <>
          <PersonTrendPicker
            accountIds={accountIds}
            names={names}
            teams={filters.teams}
            onChange={(next) => setFilter(withFilterKey(filter, "accountIds", next.length === 0 ? null : next))}
          />
          <ReportFilterBar storageKey={storageKey} filters={filters} filter={filter} onChange={setFilter} controls={PERSON_TREND_CONTROLS} />
          {!picked ? (
            <Paper withBorder p="md">
              <EmptyState icon={IconChartLine} label={t("reports.personTrend.pickOne")} />
            </Paper>
          ) : query.error ? (
            // One answer feeds every card, so a failure is said once, not in each.
            <ErrorAlert error={query.error} />
          ) : (
            <>
              {report && <ReportMetaNote meta={report.meta} timeZone={filters.timeZone} />}
              {report?.note && <ReportNote note={report.note} derivedAt={report.meta.derivedAt} />}
              {report && (
                <Text size="xs" c="dimmed">
                  {t("reports.personTrend.windowsHint", { days: report.windowDays, step: report.stepDays })}
                  {reportHasReference(report) && ` ${t("reports.personTrend.reference.hint")}`}
                </Text>
              )}
              {query.isPending && <LoadingBlock />}
              {report && (
                <PersonTrendSection report={report} filters={filters} isRefreshing={query.isPlaceholderData} drill={{ onOpen: openPoint, disabled: !drillable }} />
              )}
              {report && (
                <PersonTrendDrillDrawer
                  report={report}
                  filter={filter}
                  timeZone={filters.timeZone}
                  opened={drill.target !== null}
                  target={drill.shown}
                  onClose={drill.close}
                />
              )}
            </>
          )}
        </>
      )}
    </Stack>
  );
}
