import { useTranslation } from "react-i18next";
import { Paper, Stack } from "@mantine/core";
import { IconGitMerge } from "@tabler/icons-react";
import { getCodeActivityReport } from "../api/reports";
import CodeActivityDrillDrawer from "../components/CodeActivityDrillDrawer";
import CodeFlowSection from "../components/CodeFlowSection";
import CodeMembersTable from "../components/CodeMembersTable";
import CodeOpenTile from "../components/CodeOpenTile";
import CodeReviewTile from "../components/CodeReviewTile";
import CodeSourcesNote from "../components/CodeSourcesNote";
import EmptyState from "../components/EmptyState";
import ErrorAlert from "../components/ErrorAlert";
import LoadingBlock from "../components/LoadingBlock";
import ReportFilterBar from "../components/ReportFilterBar";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportPageHeader from "../components/ReportPageHeader";
import ReportPageShell from "../components/ReportPageShell";
import ReportTailControl from "../components/ReportTailControl";
import { useHistogramDrill } from "../hooks/useHistogramDrill";
import { useReportDrill } from "../hooks/useReportDrill";
import { useReportPage } from "../hooks/useReportPage";
import { CODE_FLOW_ANCHOR, type CodeDrill } from "../utils/codeActivityDrill";
import { CODE_ACTIVITY_CONTROLS, normalizeCodeActivityFilter } from "../utils/codeActivityReport";
import { CODE_TABS } from "../utils/reportLinks";

/**
 * Report 21 — Code activity: the flow of ONE team's merge requests over the connected GitLab (cycle time, time to first review, size, merged per
 * week, what is open now, how review work is spread) with the members' volumes beside it as context (A50). The flow figures carry a direction
 * in words; the member table is neutral — no tint, icon, rank or default order. A person is in a figure only through the identity link an
 * administrator confirmed, and what is still unlinked, unread or unknown is said in a note, never silently left out. Read per team (never the whole
 * unit), over the period's dates or sprints, for up to eight members - or for "Unassigned" (`teamId=0`), the work no team holds, which the team control offers
 * on this page only and whose rows are identities rather than a roster.
 */
export default function ReportCodeActivity() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, filterKey, setFilter, query, storageKey } = useReportPage(
    "code-activity",
    getCodeActivityReport,
    normalizeCodeActivityFilter,
    CODE_ACTIVITY_CONTROLS,
  );
  const report = query.data;
  // While a changed filter loads, the previous report stays on screen dimmed (`isPlaceholderData`).
  const refreshing = query.isPlaceholderData;
  // The drill (a tile, a member's cell, a weekly bar or a histogram bar) is URL state; a bar of kept data belongs to the PREVIOUS filter and opens nothing.
  const drill = useReportDrill(filterKey, CODE_FLOW_ANCHOR);
  const openBar = useHistogramDrill(drill, { anchor: CODE_FLOW_ANCHOR });
  const codeDrill: CodeDrill = { open: drill.open, bar: openBar, disabled: query.isPlaceholderData };

  return (
    <Stack gap="md">
      <ReportPageHeader
        title={t("reports.codeActivity.title")}
        description={t("reports.codeActivity.description")}
        filter={filter}
        filters={filters}
        controls={CODE_ACTIVITY_CONTROLS}
        tail
      />
      <ReportPageShell tabs={CODE_TABS} filtersQuery={filtersQuery} filters={filters} noTeamsLabel={t("reports.codeActivity.noTeams")}>
        {(loaded) => (
          <>
            <ReportFilterBar storageKey={storageKey} filters={loaded} filter={filter} onChange={setFilter} controls={CODE_ACTIVITY_CONTROLS} />
            {query.error ? (
              // One answer feeds every card, so a failure is said once, not in each.
              <ErrorAlert error={query.error} />
            ) : report === undefined ? (
              <LoadingBlock />
            ) : (
              <>
                <ReportMetaNote meta={report.meta} timeZone={loaded.timeZone} />
                {report.sources.length === 0 ? (
                  <Paper withBorder p="md">
                    <EmptyState icon={IconGitMerge} label={t("reports.codeActivity.noSources")} hint={t("reports.codeActivity.noSourcesHint")} />
                  </Paper>
                ) : (
                  <>
                    <CodeSourcesNote report={report} timeZone={loaded.timeZone} />
                    <ReportTailControl filter={filter} onChange={setFilter} />
                    <CodeFlowSection report={report} isRefreshing={refreshing} drill={codeDrill} />
                    <CodeOpenTile report={report} isRefreshing={refreshing} drill={codeDrill} />
                    {report.review && <CodeReviewTile review={report.review} minSampleSize={report.meta.minSampleSize} isRefreshing={refreshing} />}
                    <CodeMembersTable report={report} filters={loaded} drill={codeDrill} unassigned={filter.teamId === 0} />
                    <CodeActivityDrillDrawer
                      report={report}
                      filters={loaded}
                      filter={filter}
                      opened={drill.target !== null}
                      target={drill.shown}
                      onClose={drill.close}
                      onSegmentChange={drill.setSegment}
                    />
                  </>
                )}
              </>
            )}
          </>
        )}
      </ReportPageShell>
    </Stack>
  );
}
