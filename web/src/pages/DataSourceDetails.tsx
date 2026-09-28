import { useState } from "react";
import type { ParseKeys } from "i18next";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useParams } from "react-router-dom";
import { Alert, Badge, Box, Button, Group, Stack, Table, Text, Title } from "@mantine/core";
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
  type SyncCounts,
  type SyncJobKind,
  type SyncJobResponse,
  type SyncJobStatus,
} from "../api/dataSources";
import ConfirmActionModal from "../components/ConfirmActionModal";
import CursorTable from "../components/CursorTable";
import DataSourceEditorModal from "../components/DataSourceEditorModal";
import EditPageLoadState from "../components/EditPageLoadState";
import JobStateBadge from "../components/JobStateBadge";
import PageHeader from "../components/PageHeader";
import SyncJobsTable from "../components/SyncJobsTable";
import { dataSourceInspectPath, dataSourceMetricsConfigPath, dataSourceProfilePath, dataSourcesPath } from "../utils/dataSourceLinks";
import { dataSourceStateColor, formatEpochMillis } from "../utils/dataSourceState";
import { CONTENT_MAX_WIDTH } from "../utils/layout";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";

const JOB_TOASTS: Record<"SYNC" | "RECONCILE" | "REPROCESS", { requested: ParseKeys; coalesced: ParseKeys }> = {
  SYNC: { requested: "dataSources.toast.syncRequested", coalesced: "dataSources.toast.syncCoalesced" },
  RECONCILE: { requested: "dataSources.toast.reconcileRequested", coalesced: "dataSources.toast.reconcileCoalesced" },
  REPROCESS: { requested: "dataSources.toast.reprocessRequested", coalesced: "dataSources.toast.reprocessCoalesced" },
};

const PAGE_SIZE = 20;

/** True while a job is still open — the auto-refresh condition (plan §10/§11: refetch every 5s only then). */
function isOpen(status: SyncJobStatus | undefined): boolean {
  return status === "PENDING" || status === "RUNNING";
}

/** The current job's kind/status/stream plus its progress counters — split out to keep the page under the line cap. */
function CurrentJobSection({ job }: { job: SyncJobResponse }) {
  const { t } = useTranslation();
  return (
    <Stack gap="xs">
      <Title order={4}>{t("dataSources.details.currentJob")}</Title>
      <Table>
        <Table.Tbody>
          <Table.Tr>
            <Table.Th>{t("dataSources.details.jobColumn.kind")}</Table.Th>
            <Table.Td>{t(`dataSources.job.kind.${job.kind}`)}</Table.Td>
          </Table.Tr>
          <Table.Tr>
            <Table.Th>{t("dataSources.details.jobColumn.status")}</Table.Th>
            <Table.Td>
              <JobStateBadge status={job.status} />
            </Table.Td>
          </Table.Tr>
          <Table.Tr>
            <Table.Th>{t("dataSources.details.currentStream")}</Table.Th>
            <Table.Td>{job.currentStream ?? "—"}</Table.Td>
          </Table.Tr>
        </Table.Tbody>
      </Table>
      {job.progress && Object.keys(job.progress).length > 0 && (
        <Table>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t("dataSources.details.progressCounter")}</Table.Th>
              <Table.Th>{t("dataSources.details.progressValue")}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {Object.entries(job.progress).map(([key, value]) => (
              <Table.Tr key={key}>
                <Table.Td>
                  <Text size="sm">{key}</Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{typeof value === "object" ? JSON.stringify(value) : String(value)}</Text>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  );
}

/** The raw-store counts table — split out to keep the page under the line cap. */
function CountsSection({ counts }: { counts: SyncCounts }) {
  const { t } = useTranslation();
  const entitiesText =
    Object.entries(counts.entitiesByKind).length > 0
      ? Object.entries(counts.entitiesByKind)
          .map(([kind, count]) => `${kind}: ${count}`)
          .join(", ")
      : "—";
  return (
    <Stack gap="xs">
      <Title order={4}>{t("dataSources.details.counts")}</Title>
      <Table>
        <Table.Tbody>
          <Table.Tr>
            <Table.Th>{t("dataSources.details.count.rawIssues")}</Table.Th>
            <Table.Td>{counts.rawIssues}</Table.Td>
          </Table.Tr>
          <Table.Tr>
            <Table.Th>{t("dataSources.details.count.tombstonedDeleted")}</Table.Th>
            <Table.Td>{counts.tombstonedDeleted}</Table.Td>
          </Table.Tr>
          <Table.Tr>
            <Table.Th>{t("dataSources.details.count.tombstonedMovedOut")}</Table.Th>
            <Table.Td>{counts.tombstonedMovedOut}</Table.Td>
          </Table.Tr>
          <Table.Tr>
            <Table.Th>{t("dataSources.details.count.changelogs")}</Table.Th>
            <Table.Td>{counts.changelogs}</Table.Td>
          </Table.Tr>
          <Table.Tr>
            <Table.Th>{t("dataSources.details.count.worklogs")}</Table.Th>
            <Table.Td>{counts.worklogs}</Table.Td>
          </Table.Tr>
          <Table.Tr>
            <Table.Th>{t("dataSources.details.count.entitiesByKind")}</Table.Th>
            <Table.Td>{entitiesText}</Table.Td>
          </Table.Tr>
          <Table.Tr>
            <Table.Th>{t("dataSources.details.count.needsProcessing")}</Table.Th>
            <Table.Td>{counts.needsProcessing}</Table.Td>
          </Table.Tr>
        </Table.Tbody>
      </Table>
    </Stack>
  );
}

/**
 * One data source's operational surface (`/data-sources/:id`, ADMIN only — plan §9/§10/§11):
 * connection summary, the current job's progress (auto-refreshing every 5s only while one is
 * open), cursors, raw-store counts, and the paged/filterable sync-jobs history. Actions request
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
    refetchInterval: (query) => (isOpen(query.state.data?.currentJob?.status) ? 5000 : false),
  });

  const [jobKind, setJobKind] = useState<SyncJobKind | null>(null);
  const [jobStatus, setJobStatus] = useState<SyncJobStatus | null>(null);
  const [jobsPage, setJobsPage] = useState(1);
  const [jobsPageSize, setJobsPageSize] = useState(PAGE_SIZE);

  const jobs = useQuery({
    queryKey: ["dataSources", "syncJobs", id, jobsPage, jobsPageSize, jobKind, jobStatus],
    queryFn: () => listSyncJobs(id, { page: jobsPage, pageSize: jobsPageSize, kind: jobKind ?? undefined, status: jobStatus ?? undefined }),
    placeholderData: keepPreviousData,
    enabled: idIsValid && admin,
  });

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
      />
    );
  }
  const data = status.data;
  const connection = data.connection;
  const currentJob = data.currentJob;

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
            <Button
              variant="default"
              leftSection={<IconRecycle size={16} />}
              disabled={requestingKind !== null}
              onClick={() => setReprocessConfirmOpen(true)}
            >
              {t("dataSources.details.reprocess")}
            </Button>
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
        toolbar={
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
        }
      />

      {actionError && (
        <Alert color="red" variant="light">
          {actionError}
        </Alert>
      )}

      <Box maw={CONTENT_MAX_WIDTH}>
        <Stack gap="lg">
          <Stack gap="xs">
            <Title order={4}>{t("dataSources.details.connection")}</Title>
            <Table>
              <Table.Tbody>
                <Table.Tr>
                  <Table.Th>{t("dataSources.column.state")}</Table.Th>
                  <Table.Td>
                    <Badge color={dataSourceStateColor(connection.status.state)} variant="light">
                      {t(`dataSources.state.${connection.status.state}`)}
                    </Badge>
                  </Table.Td>
                </Table.Tr>
                <Table.Tr>
                  <Table.Th>{t("dataSources.field.siteUrl")}</Table.Th>
                  <Table.Td>{connection.jira.siteUrl}</Table.Td>
                </Table.Tr>
                <Table.Tr>
                  <Table.Th>{t("dataSources.field.email")}</Table.Th>
                  <Table.Td>{connection.jira.email}</Table.Td>
                </Table.Tr>
                <Table.Tr>
                  <Table.Th>{t("dataSources.column.projects")}</Table.Th>
                  <Table.Td>{connection.jira.projectKeys.join(", ")}</Table.Td>
                </Table.Tr>
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
            <Title order={4}>{t("dataSources.details.cursors")}</Title>
            <CursorTable cursors={data.cursors} />
          </Stack>

          <CountsSection counts={data.counts} />

          <Stack gap="xs">
            <Title order={4}>{t("dataSources.details.jobsTitle")}</Title>
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
        opened={reprocessConfirmOpen}
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
