import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Badge, Box, Button, Group, Menu, Stack, Table, Text } from "@mantine/core";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconCalendarOff, IconUserPlus, IconUsersGroup } from "@tabler/icons-react";
import {
  deleteTeamJiraMembership,
  listJiraUsers,
  listTeamJiraMemberships,
  updateTeamJiraMembership,
  type TeamMembershipResponse,
} from "../api/metrics";
import { useAdmin } from "../auth";
import { epochMillisToIsoDate, nowEpochMillis, startOfTodayEpochMillis } from "../utils/isoDate";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import ConfirmDeleteModal from "./ConfirmDeleteModal";
import EmptyState from "./EmptyState";
import JiraMemberModal from "./JiraMemberModal";
import LoadingBlock from "./LoadingBlock";
import RowActionsMenu from "./RowActionsMenu";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";

const DIRECTORY_PAGE_SIZE = 100;

/** `now ∈ [validFrom, validTo)` — `validTo == null` means open-ended (always current from `validFrom` on). */
function isCurrent(row: TeamMembershipResponse, nowMillis: number): boolean {
  return row.validFrom <= nowMillis && (row.validTo == null || row.validTo > nowMillis);
}

/**
 * D1's dated Jira-user team membership (`.claude/docs/domain-model.md` "Configuration"), mounted
 * on `TeamDetails` below the Flow-login roster. Everyone reads the history; only ADMINs add,
 * end or remove a row (`.claude/docs/authorization.md`'s `/jira-memberships` bullet).
 */
export default function TeamJiraMembers({ teamId }: { teamId: number }) {
  const { t } = useTranslation();
  const admin = useAdmin();
  const queryClient = useQueryClient();
  const [adding, setAdding] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const memberships = useQuery({
    queryKey: ["team-jira-memberships", teamId],
    queryFn: () => listTeamJiraMemberships(teamId),
    enabled: Number.isFinite(teamId),
  });

  // Best-effort display-name resolution: every account that ever held a membership row is
  // UNIT-relevant (JiraUsersRoutes.kt's `everMemberedAccountIds`), so one page of the UNIT
  // directory covers this team's whole history unless the unit has grown past the page size —
  // an unresolved account simply falls back to its raw accountId below.
  const directory = useQuery({
    queryKey: ["jira-users", "directory", "UNIT"],
    queryFn: () => listJiraUsers({ page: 1, pageSize: DIRECTORY_PAGE_SIZE, sort: "displayName", scope: "UNIT" }),
  });
  const namesByAccountId = new Map((directory.data?.items ?? []).map((person) => [person.accountId, person.displayName]));

  async function refresh() {
    await queryClient.invalidateQueries({ queryKey: ["team-jira-memberships", teamId] });
  }

  const endMembership = useMutation({
    mutationFn: (row: TeamMembershipResponse) =>
      updateTeamJiraMembership(teamId, row.id, { validFrom: row.validFrom, validTo: startOfTodayEpochMillis() }),
    onSuccess: async () => {
      showSuccessToast(t("metrics.teamMembers.toast.ended"));
      await refresh();
    },
    onError: (err) => {
      setActionError(
        saveErrorMessage(err, t, {
          conflict: "metrics.teamMembers.endConflict",
          invalid: "metrics.teamMembers.endInvalid",
          failedStatus: "common.error.actionFailedStatus",
          failed: "common.error.actionFailed",
        }),
      );
    },
  });

  const remove = useDeleteConfirm<TeamMembershipResponse>({
    mutationFn: (row) => deleteTeamJiraMembership(teamId, row.id),
    onSuccess: refresh,
    successMessage: t("metrics.teamMembers.toast.removed"),
  });

  const now = nowEpochMillis();
  const currentAccountIds = new Set(
    (memberships.data?.items ?? []).filter((row) => isCurrent(row, now)).map((row) => row.accountId),
  );

  if (memberships.isLoading) return <LoadingBlock />;
  if (memberships.isError) {
    return (
      <Alert color="red" variant="light">
        {loadErrorMessage(memberships.error, t)}
      </Alert>
    );
  }

  const rows = memberships.data?.items ?? [];

  return (
    <Stack gap="sm">
      <Group justify="space-between" align="center">
        <Text fw={600} size="sm">
          {t("metrics.teamMembers.title")}
        </Text>
        {admin && (
          <Button size="xs" variant="default" leftSection={<IconUserPlus size={14} />} onClick={() => setAdding(true)}>
            {t("metrics.teamMembers.addMember")}
          </Button>
        )}
      </Group>
      {actionError && (
        <Alert color="red" variant="light" onClose={() => setActionError(null)} withCloseButton>
          {actionError}
        </Alert>
      )}
      {rows.length === 0 ? (
        <EmptyState icon={IconUsersGroup} label={t("metrics.teamMembers.empty")} />
      ) : (
        <Box style={{ overflowX: "auto" }}>
          <Table aria-label={t("metrics.teamMembers.tableAria")}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t("metrics.teamMembers.field.person")}</Table.Th>
                <Table.Th>{t("metrics.teamMembers.field.validFrom")}</Table.Th>
                <Table.Th>{t("metrics.teamMembers.field.validTo")}</Table.Th>
                <Table.Th />
                {admin && <Table.Th aria-label={t("common.table.operations")} style={{ width: 1 }} />}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((row) => {
                const current = isCurrent(row, now);
                const displayName = namesByAccountId.get(row.accountId) ?? row.accountId;
                return (
                  <Table.Tr key={row.id}>
                    <Table.Td>
                      <Text size="sm">{displayName}</Text>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm">{epochMillisToIsoDate(row.validFrom)}</Text>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm">{row.validTo == null ? t("metrics.teamMembers.openEnded") : epochMillisToIsoDate(row.validTo)}</Text>
                    </Table.Td>
                    <Table.Td>
                      {current && (
                        <Badge size="xs" variant="light" color="teal">
                          {t("metrics.teamMembers.current")}
                        </Badge>
                      )}
                    </Table.Td>
                    {admin && (
                      <Table.Td style={{ width: 1 }} ta="right">
                        <RowActionsMenu label={t("common.table.operationsAria", { name: displayName })} loading={endMembership.isPending}>
                          {row.validTo == null && (
                            <Menu.Item
                              leftSection={<IconCalendarOff size={14} />}
                              onClick={() => {
                                setActionError(null);
                                endMembership.mutate(row);
                              }}
                            >
                              {t("metrics.teamMembers.endMembership")}
                            </Menu.Item>
                          )}
                          <Menu.Item color="red" onClick={() => remove.requestDelete(row)}>
                            {t("common.action.delete")}
                          </Menu.Item>
                        </RowActionsMenu>
                      </Table.Td>
                    )}
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </Box>
      )}

      {adding && (
        <JiraMemberModal
          teamId={teamId}
          excludeAccountIds={currentAccountIds}
          onClose={() => setAdding(false)}
          onCreated={async () => {
            setAdding(false);
            await refresh();
          }}
        />
      )}

      <ConfirmDeleteModal
        confirm={remove}
        title={t("metrics.teamMembers.removeTitle")}
        errorTitle={t("metrics.teamMembers.removeFailed")}
        body={(row) => t("metrics.teamMembers.removeBody", { name: namesByAccountId.get(row.accountId) ?? row.accountId })}
      />
    </Stack>
  );
}
