import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useNavigate } from "react-router-dom";
import { Alert, Anchor, Badge, Button, Menu, Stack, Table, Text } from "@mantine/core";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconExternalLink, IconPencil, IconPlugConnected, IconPlus, IconRefresh, IconTrash } from "@tabler/icons-react";
import { useAdmin } from "../auth";
import {
  deleteDataSource,
  listDataSources,
  requestSyncJob,
  type DataSourceListItem,
  type DataSourceResponse,
} from "../api/dataSources";
import ClearableTextInput from "../components/ClearableTextInput";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import DataSourceEditorModal from "../components/DataSourceEditorModal";
import FilterPanel from "../components/FilterPanel";
import PageHeader from "../components/PageHeader";
import RegistryListTable from "../components/RegistryListTable";
import RowActionsMenu from "../components/RowActionsMenu";
import SortHeader from "../components/SortHeader";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { useRegistryListControls } from "../hooks/useRegistryListControls";
import { dataSourcePath } from "../utils/dataSourceLinks";
import { dataSourceStateColor, formatEpochMillis } from "../utils/dataSourceState";
import { saveErrorMessage } from "../utils/saveError";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { showSuccessToast } from "../utils/toast";

const SORT_FIELDS = ["name"] as const;
type SortField = (typeof SORT_FIELDS)[number];

const SETTINGS_KEY = "dataSources";

/**
 * The Data sources registry (`/data-sources`, ADMIN only — plan §9/§10): the Teams registry
 * template (`useRegistryListControls` + `RegistryListTable`). Unlike Teams, a list row already
 * carries everything the editor needs (`DataSourceResponse` minus the write-only token), so Edit
 * opens straight from the row with no extra detail fetch.
 */
export default function DataSources() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const admin = useAdmin();
  const queryClient = useQueryClient();
  const { nameFilter, setNameFilter, debouncedName, nameFilterActive, page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } =
    useRegistryListControls<SortField>({ settingsKey: SETTINGS_KEY, sortFields: SORT_FIELDS, initialSortField: "name" });

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["dataSources", "list", page, pageSize, sortParam, debouncedName],
    queryFn: () => listDataSources({ page, pageSize, sort: sortParam, name: debouncedName || undefined }),
    placeholderData: keepPreviousData,
    enabled: admin,
  });

  const [editorTarget, setEditorTarget] = useState<DataSourceResponse | "new" | null>(null);

  const [syncingId, setSyncingId] = useState<number | null>(null);
  const [syncError, setSyncError] = useState<string | null>(null);

  async function syncNow(row: DataSourceListItem) {
    setSyncError(null);
    setSyncingId(row.id);
    try {
      const result = await requestSyncJob(row.id, { kind: "SYNC" });
      showSuccessToast(result.coalesced ? t("dataSources.toast.syncCoalesced") : t("dataSources.toast.syncRequested"));
      await refreshQueriesAfterMutation(queryClient, ["dataSources"]);
    } catch (err) {
      setSyncError(
        saveErrorMessage(err, t, {
          notFound: "dataSources.saveGone",
          failedStatus: "common.error.actionFailedStatus",
          failed: "common.error.actionFailed",
        }),
      );
    } finally {
      setSyncingId(null);
    }
  }

  const remove = useDeleteConfirm<DataSourceListItem>({
    mutationFn: (row) => deleteDataSource(row.id),
    onSuccess: () => refreshQueriesAfterMutation(queryClient, ["dataSources"]),
    successMessage: t("dataSources.toast.deleted"),
  });

  const total = data?.total ?? 0;
  const columnCount = 7;

  return (
    <Stack gap="md">
      <PageHeader
        title={t("dataSources.title")}
        description={t("dataSources.intro")}
        actions={
          <Button leftSection={<IconPlus size={16} />} onClick={() => setEditorTarget("new")}>
            {t("dataSources.newDataSource")}
          </Button>
        }
      />

      <FilterPanel activeFilterCount={nameFilterActive ? 1 : 0} storageKey={SETTINGS_KEY}>
        <ClearableTextInput
          label={t("common.field.name")}
          value={nameFilter}
          onChange={setNameFilter}
          clearLabel={t("common.filter.clearName")}
        />
      </FilterPanel>

      {syncError && (
        <Alert color="red" variant="light">
          {syncError}
        </Alert>
      )}

      <RegistryListTable
        label={t("dataSources.title")}
        errorTitle={t("dataSources.loadFailed")}
        error={error}
        isError={isError}
        isLoading={isLoading}
        hasData={Boolean(data)}
        rowCount={data?.items.length ?? 0}
        columnCount={columnCount}
        emptyIcon={IconPlugConnected}
        emptyLabel={t("dataSources.empty")}
        header={
          <Table.Tr>
            <SortHeader field="name" label={t("common.field.name")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <Table.Th>{t("dataSources.column.site")}</Table.Th>
            <Table.Th>{t("dataSources.column.projects")}</Table.Th>
            <Table.Th>{t("dataSources.column.enabled")}</Table.Th>
            <Table.Th>{t("dataSources.column.lastSuccess")}</Table.Th>
            <Table.Th>{t("dataSources.column.state")}</Table.Th>
            <Table.Th aria-label={t("common.table.operations")} style={{ width: 1 }} />
          </Table.Tr>
        }
        rows={data?.items.map((dataSource) => {
          const host = new URL(dataSource.jira.siteUrl).host;
          return (
            <Table.Tr key={dataSource.id}>
              <Table.Td>
                {/* The name is the way into the details page — a real link (the detail-link rule). */}
                <Anchor
                  component={RouterLink}
                  to={dataSourcePath(dataSource.id)}
                  fw={500}
                  size="sm"
                  aria-label={t("dataSources.openAria", { name: dataSource.name })}
                >
                  {dataSource.name}
                </Anchor>
              </Table.Td>
              <Table.Td>
                <Text size="sm">{host}</Text>
              </Table.Td>
              <Table.Td>
                <Text size="sm" lineClamp={1}>
                  {dataSource.jira.projectKeys.join(", ")}
                </Text>
              </Table.Td>
              <Table.Td>
                <Text size="sm" c={dataSource.enabled ? undefined : "dimmed"}>
                  {dataSource.enabled ? t("dataSources.yes") : t("dataSources.no")}
                </Text>
              </Table.Td>
              <Table.Td>
                <Text size="sm">{formatEpochMillis(dataSource.status.lastSyncSucceededAt, t)}</Text>
              </Table.Td>
              <Table.Td>
                <Badge color={dataSourceStateColor(dataSource.status.state)} variant="light">
                  {t(`dataSources.state.${dataSource.status.state}`)}
                </Badge>
              </Table.Td>
              <Table.Td style={{ width: 1 }} ta="right">
                <RowActionsMenu
                  label={t("common.table.operationsAria", { name: dataSource.name })}
                  loading={syncingId === dataSource.id}
                >
                  <Menu.Item
                    leftSection={<IconExternalLink size={14} />}
                    onClick={() => navigate(dataSourcePath(dataSource.id))}
                  >
                    {t("dataSources.open")}
                  </Menu.Item>
                  <Menu.Item
                    leftSection={<IconRefresh size={14} />}
                    onClick={() => void syncNow(dataSource)}
                    aria-label={t("dataSources.syncNowAria", { name: dataSource.name })}
                  >
                    {t("dataSources.syncNow")}
                  </Menu.Item>
                  <Menu.Item
                    leftSection={<IconPencil size={14} />}
                    onClick={() => setEditorTarget(dataSource)}
                    aria-label={t("common.action.editAria", { name: dataSource.name })}
                  >
                    {t("common.action.edit")}
                  </Menu.Item>
                  <Menu.Divider />
                  <Menu.Item
                    color="red"
                    leftSection={<IconTrash size={14} />}
                    onClick={() => remove.requestDelete(dataSource)}
                    aria-label={t("common.action.deleteAria", { name: dataSource.name })}
                  >
                    {t("common.action.delete")}
                  </Menu.Item>
                </RowActionsMenu>
              </Table.Td>
            </Table.Tr>
          );
        })}
        total={total}
        page={page}
        pageSize={pageSize}
        onPageChange={setPage}
        onPageSizeChange={setPageSize}
      />

      {editorTarget !== null && (
        <DataSourceEditorModal
          target={editorTarget === "new" ? null : editorTarget}
          onClose={() => setEditorTarget(null)}
          onSaved={async () => {
            setEditorTarget(null);
            await refreshQueriesAfterMutation(queryClient, ["dataSources"]);
          }}
        />
      )}

      <ConfirmDeleteModal
        confirm={remove}
        title={t("dataSources.deleteTitle")}
        errorTitle={t("dataSources.deleteFailed")}
        errorMessage={(err) =>
          saveErrorMessage(err, t, {
            conflict: "dataSources.deleteConflict",
            failedStatus: "common.error.actionFailedStatus",
            failed: "common.error.actionFailed",
          })
        }
        body={(dataSource) => t("dataSources.deleteBody", { name: dataSource.name })}
      />
    </Stack>
  );
}
