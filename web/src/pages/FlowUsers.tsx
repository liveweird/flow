import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Badge, Button, Group, Menu, Modal, Select, Stack, Table, Text } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconKey, IconPencil, IconPlus, IconToggleLeft, IconTrash, IconUsers } from "@tabler/icons-react";
import { deleteFlowUser, listFlowUsers, type FlowUserResponse } from "../api/flowUsers";
import { getUserId } from "../api/session";
import { useAdmin } from "../auth";
import ClearableTextInput from "../components/ClearableTextInput";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import FilterPanel from "../components/FilterPanel";
import OneTimePasswordModal from "../components/OneTimePasswordModal";
import PaginationBar from "../components/PaginationBar";
import RowActionsMenu from "../components/RowActionsMenu";
import SortHeader from "../components/SortHeader";
import TableLoadingRow from "../components/TableLoadingRow";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { usePagedSort } from "../hooks/usePagedSort";
import { useResetPassword } from "../hooks/useResetPassword";
import { isString, useStoredState } from "../hooks/useStoredState";
import { saveErrorMessage } from "../utils/saveError";
import PageHeader from "../components/PageHeader";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { editFlowUserPath, newFlowUserPath, flowUserFeaturesPath } from "../utils/flowUserLinks";
import ErrorAlert from "../components/ErrorAlert";
import FormErrorAlert from "../components/FormErrorAlert";

const SORT_FIELDS = ["name", "email"] as const;
type SortField = (typeof SORT_FIELDS)[number];

const SETTINGS_KEY = "flowUsers";

export default function FlowUsers() {
  const { t } = useTranslation();
  const admin = useAdmin();
  const queryClient = useQueryClient();
  const currentUserId = getUserId();
  const [nameFilter, setNameFilter] = useStoredState(`${SETTINGS_KEY}.filter.name`, "", isString);
  const [emailFilter, setEmailFilter] = useStoredState(`${SETTINGS_KEY}.filter.email`, "", isString);
  const [roleFilter, setRoleFilter] = useStoredState(`${SETTINGS_KEY}.filter.role`, "", isString);
  const activeFilterCount =
    (nameFilter.trim() ? 1 : 0) + (emailFilter.trim() ? 1 : 0) + (roleFilter ? 1 : 0);

  const [debouncedName] = useDebouncedValue(nameFilter, 300);
  const [debouncedEmail] = useDebouncedValue(emailFilter, 300);

  const { page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } =
    usePagedSort<SortField>("name", [debouncedName, debouncedEmail, roleFilter], {
      key: SETTINGS_KEY,
      sortFields: SORT_FIELDS,
    });

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["flowUsers", page, pageSize, sortParam, debouncedName, debouncedEmail, roleFilter],
    queryFn: () =>
      listFlowUsers({
        page,
        pageSize,
        sort: sortParam,
        name: debouncedName || undefined,
        email: debouncedEmail || undefined,
        role: roleFilter || undefined,
      }),
    placeholderData: keepPreviousData,
    enabled: admin,
  });

  const deleteConfirm = useDeleteConfirm<FlowUserResponse>({
    mutationFn: (row) => deleteFlowUser(row.id),
    onSuccess: () => refreshQueriesAfterMutation(queryClient, ["flowUsers"]),
    successMessage: t("flowUsers.toast.deleted"),
  });

  // The reset flow: confirm → generate client-side → PUT → the one-time reveal modal.
  const reset = useResetPassword();


  const total = data?.total ?? 0;
  const columnCount = 4;

  return (
    <Stack gap="md">
      <PageHeader
        title={t("flowUsers.title")}
        actions={
          <>
            <Button component={RouterLink} to={newFlowUserPath} leftSection={<IconPlus size={16} />}>
              {t("flowUsers.createUser")}
            </Button>
          </>
        }
      />

      <FilterPanel activeFilterCount={activeFilterCount} storageKey={SETTINGS_KEY}>
        <ClearableTextInput
          label={t("common.field.name")}
          value={nameFilter}
          onChange={setNameFilter}
          clearLabel={t("common.filter.clearName")}
        />
        <ClearableTextInput
          label={t("common.field.email")}
          value={emailFilter}
          onChange={setEmailFilter}
          clearLabel={t("flowUsers.clearEmailFilter")}
        />
        <Select
          label={t("common.field.role")}
          placeholder={t("common.state.any")}
          data={[
            { value: "ADMIN", label: t("common.role.ADMIN") },
            { value: "USER", label: t("flowUsers.rolePlain") },
          ]}
          value={roleFilter || null}
          onChange={(v) => setRoleFilter(v ?? "")}
          clearable
          clearButtonProps={{ "aria-label": t("flowUsers.clearRoleFilter") }}
        />
      </FilterPanel>

      {isError && <ErrorAlert error={error} title={t("flowUsers.loadFailed")} />}

      <Table aria-label={t("flowUsers.title")}>
        <Table.Thead>
          <Table.Tr>
            <SortHeader
              field="name"
              label={t("common.field.name")}
              activeField={sortField}
              activeDir={sortDir}
              onToggle={toggleSort}
            />
            <SortHeader
              field="email"
              label={t("common.field.email")}
              activeField={sortField}
              activeDir={sortDir}
              onToggle={toggleSort}
            />
            <Table.Th>{t("common.field.role")}</Table.Th>
            <Table.Th aria-label={t("common.table.operations")} style={{ width: 1 }} />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {isLoading && !data ? (
            <TableLoadingRow colSpan={columnCount} />
          ) : data && data.items.length > 0 ? (
            data.items.map((user) => {
              const self = user.id === currentUserId;
              return (
                <Table.Tr key={user.id}>
                  <Table.Td>
                    <Group gap={6} wrap="nowrap">
                      <Text size="sm" fw={500}>
                        {user.name}
                      </Text>
                      {self && (
                        <Badge size="xs" variant="light">
                          {t("flowUsers.youBadge")}
                        </Badge>
                      )}
                    </Group>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{user.email}</Text>
                  </Table.Td>
                  <Table.Td>
                    {user.roles.includes("ADMIN") ? (
                      <Badge variant="outline" color="gray" size="sm">
                        {t("common.role.ADMIN")}
                      </Badge>
                    ) : (
                      <Text size="sm" c="dimmed">
                        —
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td style={{ width: 1 }} ta="right">
                    <RowActionsMenu label={t("common.table.operationsAria", { name: user.name })}>
                      <Menu.Item
                        component={RouterLink}
                        to={editFlowUserPath(user.id)}
                        leftSection={<IconPencil size={14} />}
                        aria-label={t("common.action.editAria", { name: user.name })}
                      >
                        {t("common.action.edit")}
                      </Menu.Item>
                      <Menu.Item
                        component={RouterLink}
                        to={flowUserFeaturesPath(user.id)}
                        leftSection={<IconToggleLeft size={14} />}
                        aria-label={t("flowUsers.featuresAria", { name: user.name })}
                      >
                        {t("flowUsers.featuresAction")}
                      </Menu.Item>
                      {/* Own row: no reset (self-change needs the current password — the
                          Change password page) and no delete (the server 403s it anyway). */}
                      {!self && (
                        <>
                          <Menu.Divider />
                          <Menu.Item
                            leftSection={<IconKey size={14} />}
                            onClick={() => reset.request(user)}
                            aria-label={t("flowUsers.resetPasswordAria", { name: user.name })}
                          >
                            {t("flowUsers.resetPassword")}
                          </Menu.Item>
                          <Menu.Item
                            color="red"
                            leftSection={<IconTrash size={14} />}
                            onClick={() => deleteConfirm.requestDelete(user)}
                            aria-label={t("common.action.deleteAria", { name: user.name })}
                          >
                            {t("common.action.delete")}
                          </Menu.Item>
                        </>
                      )}
                    </RowActionsMenu>
                  </Table.Td>
                </Table.Tr>
              );
            })
          ) : !isError ? (
            <Table.Tr>
              <Table.Td colSpan={columnCount}>
                <EmptyState
                  icon={IconUsers}
                  label={t("flowUsers.noUsers")}
                />
              </Table.Td>
            </Table.Tr>
          ) : null}
        </Table.Tbody>
      </Table>

      <PaginationBar
        total={total}
        page={page}
        pageSize={pageSize}
        onPageChange={setPage}
        onPageSizeChange={setPageSize}
      />

      <ConfirmDeleteModal
        confirm={deleteConfirm}
        title={t("flowUsers.deleteTitle")}
        errorTitle={t("flowUsers.deleteFailed")}
        errorMessage={(err) =>
          saveErrorMessage(err, t, {
            conflict: "flowUsers.lastAdminError",
            failedStatus: "common.error.actionFailedStatus",
            failed: "common.error.actionFailed",
          })
        }
        body={(target) => (
          <>
            {t("flowUsers.deleteBody", { name: target.name, email: target.email })}{" "}
            {t("flowUsers.deleteUndone")}
          </>
        )}
      />

      <Modal closeButtonProps={{ "aria-label": t("common.action.close") }}
        opened={reset.target !== null}
        onClose={() => {
          if (!reset.pending) reset.clearTarget();
        }}
        title={t("flowUsers.resetTitle")}
        centered
      >
        {reset.target && (
          <Stack gap="md">
            <Text>{t("flowUsers.resetBody", { name: reset.target.name })}</Text>
            {reset.error && (
              <FormErrorAlert title={t("flowUsers.resetFailedTitle")}>
                {reset.error}
              </FormErrorAlert>
            )}
            <Group justify="flex-end" gap="sm">
              <Button variant="default" onClick={reset.clearTarget} disabled={reset.pending}>
                {t("common.action.cancel")}
              </Button>
              <Button onClick={() => void reset.confirm()} loading={reset.pending}>
                {t("flowUsers.resetConfirm")}
              </Button>
            </Group>
          </Stack>
        )}
      </Modal>

      <OneTimePasswordModal
        reveal={reset.revealed}
        title={t("flowUsers.resetDoneTitle")}
        onClose={reset.closeReveal}
      />
    </Stack>
  );
}
