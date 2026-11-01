import { useTranslation } from "react-i18next";
import { Alert, Paper, Stack } from "@mantine/core";
import { IconGitMerge, IconInfoCircle } from "@tabler/icons-react";
import { getTaskCodeReport } from "../api/reports";
import EmptyState from "../components/EmptyState";
import ErrorAlert from "../components/ErrorAlert";
import LoadingBlock from "../components/LoadingBlock";
import ReportFilterBar from "../components/ReportFilterBar";
import ReportMetaNote from "../components/ReportMetaNote";
import ReportPageHeader from "../components/ReportPageHeader";
import ReportPageShell from "../components/ReportPageShell";
import ReportTailControl from "../components/ReportTailControl";
import TaskCodeFigures from "../components/TaskCodeFigures";
import TaskCodeSourcesNote from "../components/TaskCodeSourcesNote";
import { useReportPage } from "../hooks/useReportPage";
import { CODE_TABS } from "../utils/reportLinks";
import { normalizeTaskCodeFilter, TASK_CODE_CONTROLS } from "../utils/taskCodeReport";

/**
 * Report 22 — Tasks and code: the done tasks of a team or a domain against the merge requests that name them by issue key (A51). Three groups of figures —
 * the tasks with and without a merged merge request (neutral context), the code lead time and the code merged after the task was done (both lower is
 * better, said in words) — plus a note on the tasks the link cannot use. Read per team or per domain, over the period's dates or sprints; there is no
 * person level. The merge requests are the GitLab sources' (`sources[]` says how fresh), so with none active the body is ONE empty state that says the code
 * layer is absent — never "every task is without a merge request" as if that were a finding.
 */
export default function ReportTaskCode() {
  const { t } = useTranslation();
  const { filtersQuery, filters, filter, setFilter, query, storageKey } = useReportPage(
    "task-code",
    getTaskCodeReport,
    normalizeTaskCodeFilter,
    TASK_CODE_CONTROLS,
  );
  const report = query.data;
  // While a changed filter loads, the previous report stays on screen dimmed (`isPlaceholderData`).
  const refreshing = query.isPlaceholderData;
  // Neither a team nor a domain exists to read: nothing to pick. A domain but no team yet leaves the bar up with a prompt, since the query waits for a pick.
  const nothingToRead = filters !== undefined && filters.teams.length === 0 && filters.domains.length === 0;
  const needsPick = filters !== undefined && !nothingToRead && filter.teamId === undefined && filter.domain === undefined;

  return (
    <Stack gap="md">
      <ReportPageHeader
        title={t("reports.taskCode.title")}
        description={t("reports.taskCode.description")}
        filter={filter}
        filters={filters}
        controls={TASK_CODE_CONTROLS}
        tail
      />
      <ReportPageShell tabs={CODE_TABS} filtersQuery={filtersQuery} filters={filters} noTeamsLabel={t("reports.taskCode.noTeams")} empty={nothingToRead}>
        {(loaded) => (
          <>
            <ReportFilterBar storageKey={storageKey} filters={loaded} filter={filter} onChange={setFilter} controls={TASK_CODE_CONTROLS} />
            {needsPick ? (
              <Alert color="gray" variant="light" icon={<IconInfoCircle size={16} />} role="note">
                {t("reports.taskCode.pickScope")}
              </Alert>
            ) : query.error ? (
              // One answer feeds every card, so a failure is said once, not in each.
              <ErrorAlert error={query.error} />
            ) : report === undefined ? (
              <LoadingBlock />
            ) : (
              <>
                <ReportMetaNote meta={report.meta} timeZone={loaded.timeZone} />
                {report.sources.length === 0 ? (
                  <Paper withBorder p="md">
                    <EmptyState icon={IconGitMerge} label={t("reports.taskCode.noSources")} hint={t("reports.taskCode.noSourcesHint")} />
                  </Paper>
                ) : (
                  <>
                    <TaskCodeSourcesNote report={report} timeZone={loaded.timeZone} />
                    <ReportTailControl filter={filter} onChange={setFilter} />
                    <TaskCodeFigures report={report} isRefreshing={refreshing} />
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
