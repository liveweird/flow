import { useEffect, useRef, useState, type ReactNode } from "react";
import type { ParseKeys } from "i18next";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useParams } from "react-router-dom";
import { Badge, Box, Button, Group, Stack, Table, Text, Title } from "@mantine/core";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  IconAdjustments,
  IconBan,
  IconFileSearch,
  IconPencil,
  IconRecycle,
  IconRefresh,
  IconReportAnalytics,
  IconRotateClockwise,
} from "@tabler/icons-react";
import { ApiError } from "../api/http";
import { useAdmin } from "../auth";
import {
  cancelSyncJob,
  getDataSourceStatus,
  listSyncJobs,
  requestSyncJob,
  type DataSourceResponse,
  type SyncCounts,
  type SyncJobKind,
  type SyncJobResponse,
  type SyncJobStatus,
} from "../api/dataSources";
import ColumnTable, { type ColumnDef } from "../components/ColumnTable";
import ConfirmActionModal from "../components/ConfirmActionModal";
import CursorTable from "../components/CursorTable";
import DataSourceEditorModal from "../components/DataSourceEditorModal";
import { DataSourcePausedAlert, DataSourcePausedBadge } from "../components/DataSourcePause";
import EditPageLoadState from "../components/EditPageLoadState";
import JobScheduledNote from "../components/JobScheduledNote";
import JobStateBadge from "../components/JobStateBadge";
import PageHeader from "../components/PageHeader";
import SyncJobsTable from "../components/SyncJobsTable";
import { dataSourceInspectPath, dataSourceMetricsConfigPath, dataSourceProfilePath, dataSourcesPath } from "../utils/dataSourceLinks";
import { dataSourceStateColor, formatEpochMillis } from "../utils/dataSourceState";
import { CONTENT_MAX_WIDTH } from "../utils/layout";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";
import FormErrorAlert from "../components/FormErrorAlert";

const JOB_TOASTS: Record<"SYNC" | "RECONCILE" | "REPROCESS", { requested: ParseKeys; coalesced: ParseKeys }> = {
  SYNC: { requested: "dataSources.toast.syncRequested", coalesced: "dataSources.toast.syncCoalesced" },
  RECONCILE: { requested: "dataSources.toast.reconcileRequested", coalesced: "dataSources.toast.reconcileCoalesced" },
  REPROCESS: { requested: "dataSources.toast.reprocessRequested", coalesced: "dataSources.toast.reprocessCoalesced" },
};

const PAGE_SIZE = 20;
const AUTO_REFRESH_MS = 5000;

/** True while a job is still open — the auto-refresh condition (plan §10/§11: refetch every 5s only then). */
function isOpen(status: SyncJobStatus | undefined): boolean {
  return status === "PENDING" || status === "RUNNING";
}

/** The current job's kind/status/stream plus its progress counters — split out to keep the page under the line cap. */
function CurrentJobSection({ job }: { job: SyncJobResponse }) {
  const { t } = useTranslation();
  const progressColumns: ColumnDef<[string, unknown]>[] = [
    {
      key: "counter",
      header: t("dataSources.details.progressCounter"),
      render: ([key]) => <Text size="sm">{key}</Text>,
    },
    {
      key: "value",
      header: t("dataSources.details.progressValue"),
      render: ([, value]) => <Text size="sm">{typeof value === "object" ? JSON.stringify(value) : String(value)}</Text>,
    },
  ];
  return (
    <Stack gap="xs">
      <Title order={3} size="h4">{t("dataSources.details.currentJob")}</Title>
      <Table aria-label={t("dataSources.details.currentJob")}>
        <Table.Tbody>
          <Table.Tr>
            <Table.Th>{t("dataSources.details.jobColumn.kind")}</Table.Th>
            <Table.Td>{t(`dataSources.job.kind.${job.kind}`)}</Table.Td>
          </Table.Tr>
          <Table.Tr>
            <Table.Th>{t("dataSources.details.jobColumn.status")}</Table.Th>
            <Table.Td>
              <JobStateBadge status={job.status} />
              <JobScheduledNote job={job} />
            </Table.Td>
          </Table.Tr>
          <Table.Tr>
            <Table.Th>{t("dataSources.details.currentStream")}</Table.Th>
            <Table.Td>{job.currentStream ?? "—"}</Table.Td>
          </Table.Tr>
        </Table.Tbody>
      </Table>
      {job.progress && Object.keys(job.progress).length > 0 && (
        <ColumnTable
          aria-label={t("dataSources.details.progressTableAria")}
          columns={progressColumns}
          rows={Object.entries(job.progress)}
          rowKey={([key]) => key}
        />
      )}
    </Stack>
  );
}

/** One label/value row of a details table. */
function DetailRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Table.Tr>
      <Table.Th>{label}</Table.Th>
      <Table.Td>{children}</Table.Td>
    </Table.Tr>
  );
}

/** A GitLab connection's raw-store counts (`counts.gitlab`; null when this process has no GitLab store, which shows dashes). */
function GitLabCountRows({ counts }: { counts: SyncCounts }) {
  const { t } = useTranslation();
  const gitlab = counts.gitlab;
  return (
    <>
      <DetailRow label={t("dataSources.details.count.projects")}>{gitlab?.projects ?? "—"}</DetailRow>
      <DetailRow label={t("dataSources.details.count.projectsMovedOut")}>{gitlab?.projectsMovedOut ?? "—"}</DetailRow>
      <DetailRow label={t("dataSources.details.count.projectsDeleted")}>{gitlab?.projectsDeleted ?? "—"}</DetailRow>
      <DetailRow label={t("dataSources.details.count.users")}>{gitlab?.users ?? "—"}</DetailRow>
      <DetailRow label={t("dataSources.details.count.usersDeleted")}>{gitlab?.usersDeleted ?? "—"}</DetailRow>
      <DetailRow label={t("dataSources.details.count.commits")}>{gitlab?.commits ?? "—"}</DetailRow>
      <DetailRow label={t("dataSources.details.count.commitsMerge")}>{gitlab?.commitsMerge ?? "—"}</DetailRow>
      <DetailRow label={t("dataSources.details.count.commitsDeleted")}>{gitlab?.commitsDeleted ?? "—"}</DetailRow>
      <DetailRow label={t("dataSources.details.count.commitFilesPending")}>{gitlab?.commitFilesPending ?? "—"}</DetailRow>
      <DetailRow label={t("dataSources.details.count.mergeRequests")}>{gitlab?.mergeRequests ?? "—"}</DetailRow>
      <DetailRow label={t("dataSources.details.count.mergeRequestsMerged")}>{gitlab?.mergeRequestsMerged ?? "—"}</DetailRow>
      <DetailRow label={t("dataSources.details.count.mrEvents")}>{gitlab?.mrEvents ?? "—"}</DetailRow>
      <DetailRow label={t("dataSources.details.count.mrDetailsPending")}>{gitlab?.mrDetailsPending ?? "—"}</DetailRow>
      <DetailRow label={t("dataSources.details.count.mrDiffStatsMissing")}>{gitlab?.mrDiffStatsMissing ?? "—"}</DetailRow>
      <DetailRow label={t("dataSources.details.count.mergeRequestsDeleted")}>{gitlab?.mergeRequestsDeleted ?? "—"}</DetailRow>
      <DetailRow label={t("dataSources.details.count.mrDetailsUnread")}>{gitlab?.mrDetailsUnread ?? "—"}</DetailRow>
    </>
  );
}

/** The connection table's kind rows: Jira's site, service account and project keys, or GitLab's instance and group paths. */
function ConnectionKindRows({ connection }: { connection: DataSourceResponse }) {
  const { t } = useTranslation();
  const { jira, gitlab } = connection;
  return (
    <>
      <DetailRow label={t("dataSources.column.kind")}>{t(`dataSources.kind.${connection.kind}`)}</DetailRow>
      {jira && (
        <>
          <DetailRow label={t("dataSources.field.siteUrl")}>{jira.siteUrl}</DetailRow>
          <DetailRow label={t("dataSources.field.email")}>{jira.email}</DetailRow>
          <DetailRow label={t("dataSources.column.scope")}>{jira.projectKeys.join(", ")}</DetailRow>
        </>
      )}
      {gitlab && (
        <>
          <DetailRow label={t("dataSources.field.baseUrl")}>{gitlab.baseUrl}</DetailRow>
          <DetailRow label={t("dataSources.column.scope")}>{gitlab.groupPaths.join(", ")}</DetailRow>
          <DetailRow label={t("dataSources.field.pathExclusions")}>
            {gitlab.pathExclusions.length > 0 ? gitlab.pathExclusions.join(", ") : t("dataSources.details.pathExclusionsNone")}
          </DetailRow>
        </>
      )}
    </>
  );
}

/** A Jira connection's raw-store counts. */
function JiraCountRows({ counts }: { counts: SyncCounts }) {
  const { t } = useTranslation();
  const entities = Object.entries(counts.entitiesByKind);
  const entitiesText = entities.length > 0 ? entities.map(([entity, count]) => `${entity}: ${count}`).join(", ") : "—";
  return (
    <>
      <DetailRow label={t("dataSources.details.count.rawIssues")}>{counts.rawIssues}</DetailRow>
      <DetailRow label={t("dataSources.details.count.tombstonedDeleted")}>{counts.tombstonedDeleted}</DetailRow>
      <DetailRow label={t("dataSources.details.count.tombstonedMovedOut")}>{counts.tombstonedMovedOut}</DetailRow>
      <DetailRow label={t("dataSources.details.count.changelogs")}>{counts.changelogs}</DetailRow>
      <DetailRow label={t("dataSources.details.count.worklogs")}>{counts.worklogs}</DetailRow>
      <DetailRow label={t("dataSources.details.count.entitiesByKind")}>{entitiesText}</DetailRow>
      <DetailRow label={t("dataSources.details.count.needsProcessing")}>{counts.needsProcessing}</DetailRow>
    </>
  );
}

/** The raw-store counts table: the connection's kind picks the rows (GitLab's projects and users, or Jira's issue store). */
function CountsSection({ counts, kind }: { counts: SyncCounts; kind: DataSourceResponse["kind"] }) {
  const { t } = useTranslation();
  return (
    <Stack gap="xs">
      <Title order={3} size="h4">{t("dataSources.details.counts")}</Title>
      <Table aria-label={t("dataSources.details.counts")}>
        <Table.Tbody>{kind === "GITLAB" ? <GitLabCountRows counts={counts} /> : <JiraCountRows counts={counts} />}</Table.Tbody>
      </Table>
    </Stack>
  );
}

/**
 * One data source's operational surface (`/data-sources/:id`, ADMIN only — plan §9/§10/§11):
 * connection summary, the current job's progress (auto-refreshing every 5s only while one is
 * open — the sync-jobs history refreshes on the same condition), cursors, raw-store counts, and the
 * paged/filterable sync-jobs history. Actions request
 * the same three job kinds `DataSources.tsx`'s "Sync now" does, plus Reconcile/Reprocess and
 * Cancel — all through the one generic `requestSyncJob`/`cancelSyncJob` pair.
 */
export default function DataSourceDetails() {
  const { t } = useTranslation();
  const { id: idParam } = useParams();
  const id = Number(idParam);
  const idIsValid = Number.isFinite(id) && id > 0;
  const admin = useAdmin();
  const queryClient = useQueryClient();

  const status = useQuery({
    queryKey: ["dataSources", "status", id],
    queryFn: () => getDataSourceStatus(id),
    enabled: idIsValid && admin,
    refetchInterval: (query) => (isOpen(query.state.data?.currentJob?.status) ? AUTO_REFRESH_MS : false),
  });
  // The history polls whenever the summary does (the server's `currentJob` is the open job: RUNNING, else the oldest
  // PENDING) AND while any visible history row is still open, so a row that is not the summary's job (a second
  // PENDING one, or one filtered in) is still followed to Running and on to its terminal state.
  const jobOpen = isOpen(status.data?.currentJob?.status);

  const [jobKind, setJobKind] = useState<SyncJobKind | null>(null);
  const [jobStatus, setJobStatus] = useState<SyncJobStatus | null>(null);
  const [jobsPage, setJobsPage] = useState(1);
  const [jobsPageSize, setJobsPageSize] = useState(PAGE_SIZE);

  const jobs = useQuery({
    queryKey: ["dataSources", "syncJobs", id, jobsPage, jobsPageSize, jobKind, jobStatus],
    queryFn: () => listSyncJobs(id, { page: jobsPage, pageSize: jobsPageSize, kind: jobKind ?? undefined, status: jobStatus ?? undefined }),
    placeholderData: keepPreviousData,
    enabled: idIsValid && admin,
    refetchInterval: (query) =>
      jobOpen || query.state.data?.items.some((job) => isOpen(job.status)) ? AUTO_REFRESH_MS : false,
  });
  // One last refetch when the open job finishes: the interval stops the moment the summary reports no open job,
  // and the tick that raced it may have read the history before the job's terminal state landed.
  const wasOpen = useRef(false);
  const refetchJobs = jobs.refetch;
  useEffect(() => {
    if (wasOpen.current && !jobOpen) void refetchJobs();
    wasOpen.current = jobOpen;
  }, [jobOpen, refetchJobs]);

  const [editing, setEditing] = useState(false);
  const [reprocessConfirmOpen, setReprocessConfirmOpen] = useState(false);
  const [requestingKind, setRequestingKind] = useState<SyncJobKind | null>(null);
  const [cancellingId, setCancellingId] = useState<number | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  async function refresh() {
    await refreshQueriesAfterMutation(queryClient, ["dataSources"]);
  }

  async function runJob(kind: "SYNC" | "RECONCILE" | "REPROCESS") {
    setActionError(null);
    setRequestingKind(kind);
    try {
      const result = await requestSyncJob(id, { kind });
      const toasts = JOB_TOASTS[kind];
      showSuccessToast(t(result.coalesced ? toasts.coalesced : toasts.requested));
      await refresh();
    } catch (err) {
      setActionError(
        saveErrorMessage(err, t, {
          notFound: "dataSources.saveGone",
          invalid: "dataSources.details.actionDisabled",
          failedStatus: "common.error.actionFailedStatus",
          failed: "common.error.actionFailed",
        }),
      );
    } finally {
      setRequestingKind(null);
    }
  }

  async function cancelJob(job: SyncJobResponse) {
    setActionError(null);
    setCancellingId(job.id);
    try {
      await cancelSyncJob(id, job.id);
      showSuccessToast(t("dataSources.toast.jobCancelled"));
      await refresh();
    } catch (err) {
      setActionError(
        saveErrorMessage(err, t, {
          conflict: "dataSources.details.cancelConflict",
          notFound: "dataSources.saveGone",
          failedStatus: "common.error.actionFailedStatus",
          failed: "common.error.actionFailed",
        }),
      );
    } finally {
      setCancellingId(null);
    }
  }

  if (status.isLoading || status.isError || !status.data) {
    const notFound = status.error instanceof ApiError && status.error.status === 404;
    return (
      <EditPageLoadState
        isLoading={status.isLoading}
        message={notFound ? t("dataSources.notFound") : loadErrorMessage(status.error, t)}
        backTo={dataSourcesPath}
        backLabel={t("dataSources.backToDataSources")}
        title={t("dataSources.title")}
      />
    );
  }
  const data = status.data;
  const connection = data.connection;
  const currentJob = data.currentJob;
  const jiraKind = connection.kind === "JIRA_CLOUD";

  return (
    <Stack gap="md">
      <PageHeader
        title={connection.name}
        backTo={{ to: dataSourcesPath, label: t("dataSources.backToDataSources") }}
        actions={
          <>
            <Button
              variant="default"
              leftSection={<IconRefresh size={16} />}
              loading={requestingKind === "SYNC"}
              disabled={requestingKind !== null}
              onClick={() => void runJob("SYNC")}
            >
              {t("dataSources.details.syncNow")}
            </Button>
            <Button
              variant="default"
              leftSection={<IconRotateClockwise size={16} />}
              loading={requestingKind === "RECONCILE"}
              disabled={requestingKind !== null}
              onClick={() => void runJob("RECONCILE")}
            >
              {t("dataSources.details.reconcileNow")}
            </Button>
            {/* REPROCESS is a no-op success for a GitLab source until PR 10 (`gitlab-integration.md`): not offered. */}
            {jiraKind && (
              <Button
                variant="default"
                leftSection={<IconRecycle size={16} />}
                disabled={requestingKind !== null}
                onClick={() => setReprocessConfirmOpen(true)}
              >
                {t("dataSources.details.reprocess")}
              </Button>
            )}
            <Button variant="default" leftSection={<IconPencil size={16} />} onClick={() => setEditing(true)}>
              {t("common.action.edit")}
            </Button>
            {currentJob && (
              <Button
                variant="default"
                color="red"
                leftSection={<IconBan size={16} />}
                loading={cancellingId === currentJob.id}
                onClick={() => void cancelJob(currentJob)}
              >
                {t("dataSources.details.cancel")}
              </Button>
            )}
          </>
        }
        // The data profile, raw-issue inspector and metrics configuration read Jira's issue store: a GitLab source has none (409 server-side).
        toolbar={jiraKind ? (
          <Group gap="sm">
            <Button
              component={RouterLink}
              to={dataSourceProfilePath(id)}
              variant="subtle"
              size="xs"
              leftSection={<IconReportAnalytics size={14} />}
            >
              {t("dataSources.profile.title")}
            </Button>
            <Button
              component={RouterLink}
              to={dataSourceInspectPath(id)}
              variant="subtle"
              size="xs"
              leftSection={<IconFileSearch size={14} />}
            >
              {t("dataSources.inspect.title")}
            </Button>
            <Button
              component={RouterLink}
              to={dataSourceMetricsConfigPath(id)}
              variant="subtle"
              size="xs"
              leftSection={<IconAdjustments size={14} />}
            >
              {t("metrics.config.title")}
            </Button>
          </Group>
        ) : undefined}
      />

      {actionError && (
        <FormErrorAlert>
          {actionError}
        </FormErrorAlert>
      )}

      <Box maw={CONTENT_MAX_WIDTH}>
        <Stack gap="lg">
          <Stack gap="xs">
            <Title order={3} size="h4">{t("dataSources.details.connection")}</Title>
            <DataSourcePausedAlert status={connection.status} />
            <Table aria-label={t("dataSources.details.connection")}>
              <Table.Tbody>
                <Table.Tr>
                  <Table.Th>{t("dataSources.column.state")}</Table.Th>
                  <Table.Td>
                    <Group gap={4} wrap="nowrap">
                      <Badge color={dataSourceStateColor(connection.status.state)} variant="light">
                        {t(`dataSources.state.${connection.status.state}`)}
                      </Badge>
                      <DataSourcePausedBadge status={connection.status} />
                    </Group>
                  </Table.Td>
                </Table.Tr>
                <ConnectionKindRows connection={connection} />
                <Table.Tr>
                  <Table.Th>{t("dataSources.column.enabled")}</Table.Th>
                  <Table.Td>{connection.enabled ? t("dataSources.yes") : t("dataSources.no")}</Table.Td>
                </Table.Tr>
                <Table.Tr>
                  <Table.Th>{t("dataSources.field.syncInterval")}</Table.Th>
                  <Table.Td>{connection.syncIntervalMinutes}</Table.Td>
                </Table.Tr>
                <Table.Tr>
                  <Table.Th>{t("dataSources.field.backfillFrom")}</Table.Th>
                  <Table.Td>{connection.backfillFrom}</Table.Td>
                </Table.Tr>
                <Table.Tr>
                  <Table.Th>{t("dataSources.field.reconcileHour")}</Table.Th>
                  <Table.Td>{connection.reconcileHourUtc}</Table.Td>
                </Table.Tr>
                <Table.Tr>
                  <Table.Th>{t("dataSources.details.lastSyncStarted")}</Table.Th>
                  <Table.Td>{formatEpochMillis(connection.status.lastSyncStartedAt, t)}</Table.Td>
                </Table.Tr>
                <Table.Tr>
                  <Table.Th>{t("dataSources.column.lastSuccess")}</Table.Th>
                  <Table.Td>{formatEpochMillis(connection.status.lastSyncSucceededAt, t)}</Table.Td>
                </Table.Tr>
                <Table.Tr>
                  <Table.Th>{t("dataSources.details.lastError")}</Table.Th>
                  <Table.Td>{connection.status.lastSyncErrorCode ?? "—"}</Table.Td>
                </Table.Tr>
                <Table.Tr>
                  <Table.Th>{t("dataSources.details.consecutiveFailures")}</Table.Th>
                  <Table.Td>{connection.status.consecutiveFailures}</Table.Td>
                </Table.Tr>
              </Table.Tbody>
            </Table>
          </Stack>

          {currentJob && <CurrentJobSection job={currentJob} />}

          <Stack gap="xs">
            <Title order={3} size="h4">{t("dataSources.details.cursors")}</Title>
            <CursorTable cursors={data.cursors} />
          </Stack>

          <CountsSection counts={data.counts} kind={connection.kind} />

          <Stack gap="xs">
            <Title order={3} size="h4">{t("dataSources.details.jobsTitle")}</Title>
            <SyncJobsTable
              data={jobs.data}
              isLoading={jobs.isLoading}
              isError={jobs.isError}
              error={jobs.error}
              kind={jobKind}
              onKindChange={(k) => {
                setJobKind(k);
                setJobsPage(1);
              }}
              status={jobStatus}
              onStatusChange={(s) => {
                setJobStatus(s);
                setJobsPage(1);
              }}
              page={jobsPage}
              pageSize={jobsPageSize}
              onPageChange={setJobsPage}
              onPageSizeChange={(size) => {
                setJobsPageSize(size);
                setJobsPage(1);
              }}
              onCancel={(job) => void cancelJob(job)}
              cancellingId={cancellingId}
            />
          </Stack>
        </Stack>
      </Box>

      {editing && (
        <DataSourceEditorModal
          target={connection}
          onClose={() => setEditing(false)}
          onSaved={async () => {
            setEditing(false);
            await refresh();
          }}
        />
      )}

      <ConfirmActionModal
        opened={jiraKind && reprocessConfirmOpen}
        onClose={() => setReprocessConfirmOpen(false)}
        title={t("dataSources.details.reprocessTitle")}
        message={t("dataSources.details.reprocessBody")}
        cancelLabel={t("common.action.cancel")}
        confirmLabel={t("dataSources.details.reprocess")}
        onConfirm={() => {
          setReprocessConfirmOpen(false);
          void runJob("REPROCESS");
        }}
        confirmColor="flow"
      />
    </Stack>
  );
}
