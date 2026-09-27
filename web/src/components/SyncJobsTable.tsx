import { Button, Group, Select, Table, Text } from "@mantine/core";
import { IconListDetails } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import type { SyncJobKind, SyncJobPage, SyncJobResponse, SyncJobStatus } from "../api/dataSources";
import { formatEpochMillis } from "../utils/dataSourceState";
import JobStateBadge from "./JobStateBadge";
import RegistryListTable from "./RegistryListTable";

const JOB_KINDS: SyncJobKind[] = ["SYNC", "RECONCILE", "REPROCESS", "PURGE"];
const JOB_STATUSES: SyncJobStatus[] = ["PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"];

/** A job may be cancelled while it is still open — a terminal job (SUCCEEDED/FAILED/CANCELLED) has no action left. */
function isCancellable(job: SyncJobResponse): boolean {
  return job.status === "PENDING" || job.status === "RUNNING";
}

/**
 * The details page's sync-jobs history (plan §10/§11): paged, filterable by kind/status, with a
 * per-row Cancel for any job still open. Shares `RegistryListTable`'s load/error/empty/pagination
 * shell — this component owns only the filters row and the job-specific columns.
 */
export default function SyncJobsTable({
  data,
  isLoading,
  isError,
  error,
  kind,
  onKindChange,
  status,
  onStatusChange,
  page,
  pageSize,
  onPageChange,
  onPageSizeChange,
  onCancel,
  cancellingId,
}: {
  data: SyncJobPage | undefined;
  isLoading: boolean;
  isError: boolean;
  error: unknown;
  kind: SyncJobKind | null;
  onKindChange: (kind: SyncJobKind | null) => void;
  status: SyncJobStatus | null;
  onStatusChange: (status: SyncJobStatus | null) => void;
  page: number;
  pageSize: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (size: number) => void;
  onCancel: (job: SyncJobResponse) => void;
  cancellingId: number | null;
}) {
  const { t } = useTranslation();
  const columnCount = 8;

  return (
    <>
      <Group gap="sm">
        <Select
          label={t("dataSources.details.jobKindFilter")}
          value={kind}
          onChange={(v) => onKindChange(v as SyncJobKind | null)}
          clearable
          placeholder={t("common.state.any")}
          data={JOB_KINDS.map((k) => ({ value: k, label: t(`dataSources.job.kind.${k}`) }))}
          w={200}
        />
        <Select
          label={t("dataSources.details.jobStatusFilter")}
          value={status}
          onChange={(v) => onStatusChange(v as SyncJobStatus | null)}
          clearable
          placeholder={t("common.state.any")}
          data={JOB_STATUSES.map((s) => ({ value: s, label: t(`dataSources.job.status.${s}`) }))}
          w={200}
        />
      </Group>

      <RegistryListTable
        errorTitle={t("dataSources.details.jobsLoadFailed")}
        error={error}
        isError={isError}
        isLoading={isLoading}
        hasData={Boolean(data)}
        rowCount={data?.items.length ?? 0}
        columnCount={columnCount}
        emptyIcon={IconListDetails}
        emptyLabel={t("dataSources.details.noJobs")}
        header={
          <Table.Tr>
            <Table.Th>{t("dataSources.details.jobColumn.kind")}</Table.Th>
            <Table.Th>{t("dataSources.details.jobColumn.status")}</Table.Th>
            <Table.Th>{t("dataSources.details.jobColumn.requested")}</Table.Th>
            <Table.Th>{t("dataSources.details.jobColumn.started")}</Table.Th>
            <Table.Th>{t("dataSources.details.jobColumn.finished")}</Table.Th>
            <Table.Th>{t("dataSources.details.jobColumn.attempt")}</Table.Th>
            <Table.Th>{t("dataSources.details.jobColumn.error")}</Table.Th>
            <Table.Th aria-label={t("common.table.operations")} style={{ width: 1 }} />
          </Table.Tr>
        }
        rows={data?.items.map((job) => (
          <Table.Tr key={job.id}>
            <Table.Td>
              <Text size="sm">{t(`dataSources.job.kind.${job.kind}`)}</Text>
            </Table.Td>
            <Table.Td>
              <JobStateBadge status={job.status} />
            </Table.Td>
            <Table.Td>
              <Text size="sm">{formatEpochMillis(job.requestedAt, t)}</Text>
            </Table.Td>
            <Table.Td>
              <Text size="sm">{formatEpochMillis(job.startedAt, t)}</Text>
            </Table.Td>
            <Table.Td>
              <Text size="sm">{formatEpochMillis(job.finishedAt, t)}</Text>
            </Table.Td>
            <Table.Td>
              <Text size="sm">
                {job.attempt} / {job.maxAttempts}
              </Text>
            </Table.Td>
            <Table.Td>
              <Text size="sm" c="dimmed">
                {job.errorCode ?? "—"}
              </Text>
            </Table.Td>
            <Table.Td style={{ width: 1 }} ta="right">
              {isCancellable(job) && (
                <Button
                  size="xs"
                  variant="subtle"
                  color="red"
                  loading={cancellingId === job.id}
                  onClick={() => onCancel(job)}
                  aria-label={t("dataSources.details.cancelJobAria", { id: job.id })}
                >
                  {t("dataSources.details.cancelJob")}
                </Button>
              )}
            </Table.Td>
          </Table.Tr>
        ))}
        total={data?.total ?? 0}
        page={page}
        pageSize={pageSize}
        onPageChange={onPageChange}
        onPageSizeChange={onPageSizeChange}
      />
    </>
  );
}
