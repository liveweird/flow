import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useParams } from "react-router-dom";
import { Alert, Badge, Box, Button, Group, Menu, Select, Stack, Table, Text } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconPencil, IconUserMinus, IconUserPlus, IconUsersGroup } from "@tabler/icons-react";
import { ApiError } from "../api/http";
import { useAdmin } from "../auth";
import { addTeamMember, getTeam, removeTeamMember, type TeamMember } from "../api/teams";
import { listUsers } from "../api/users";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EditPageLoadState from "../components/EditPageLoadState";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import RowActionsMenu from "../components/RowActionsMenu";
import TeamEditorModal from "../components/TeamEditorModal";
import TeamJiraMembers from "../components/TeamJiraMembers";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { CONTENT_MAX_WIDTH } from "../utils/layout";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { teamsPath } from "../utils/teamLinks";
import { showSuccessToast } from "../utils/toast";

/**
 * One team and its roster (`/teams/:id`). Everyone reads it (a soft-deleted member stays,
 * flagged); an ADMIN renames the team and adds/removes members — the add picker searches the
 * users list server-side (name filter, debounced), excluding current members.
 */
export default function TeamDetails() {
  const { t } = useTranslation();
  const { id: idParam } = useParams();
  const id = Number(idParam);
  const queryClient = useQueryClient();
  const admin = useAdmin();

  const team = useQuery({ queryKey: ["teams", "detail", id], queryFn: () => getTeam(id), enabled: Number.isFinite(id) });
  const [editing, setEditing] = useState(false);

  async function refresh() {
    await queryClient.invalidateQueries({ queryKey: ["teams"] });
  }

  const remove = useDeleteConfirm<TeamMember>({
    mutationFn: (member) => removeTeamMember(id, member.userId),
    onSuccess: refresh,
    successMessage: t("teams.toast.memberRemoved"),
  });

  const memberIds = new Set((team.data?.members ?? []).map((m) => m.userId));

  if (team.isLoading || team.isError || !team.data) {
    const notFound = team.error instanceof ApiError && team.error.status === 404;
    return (
      <EditPageLoadState
        isLoading={team.isLoading}
        message={notFound ? t("teams.notFound") : loadErrorMessage(team.error, t)}
        backTo={teamsPath}
        backLabel={t("teams.backToTeams")}
        title={t("teams.title")}
      />
    );
  }
  const data = team.data;

  return (
    <Stack gap="md">
      <PageHeader
        title={data.name}
        description={data.description ?? undefined}
        backTo={{ to: teamsPath, label: t("teams.backToTeams") }}
        actions={
          admin && (
            <Button variant="default" leftSection={<IconPencil size={16} />} onClick={() => setEditing(true)}>
              {t("common.action.edit")}
            </Button>
          )
        }
      />
      <Box maw={CONTENT_MAX_WIDTH}>
        <Stack>
          {admin && <AddMemberPicker teamId={id} memberIds={memberIds} onAdded={refresh} />}
          {data.members.length === 0 ? (
            <EmptyState icon={IconUsersGroup} label={t("teams.noMembers")} />
          ) : (
            <Table aria-label={t("teams.membersOfAria", { name: data.name })}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t("common.field.name")}</Table.Th>
                  <Table.Th>{t("common.field.email")}</Table.Th>
                  {admin && <Table.Th aria-label={t("common.table.operations")} style={{ width: 1 }} />}
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {data.members.map((member) => (
                  <Table.Tr key={member.userId}>
                    <Table.Td>
                      <Group gap={6} wrap="nowrap">
                        <Text size="sm" fw={500} c={member.deleted ? "dimmed" : undefined}>
                          {member.name}
                        </Text>
                        {member.deleted && (
                          <Badge size="xs" variant="outline" color="gray">
                            {t("teams.deletedMember")}
                          </Badge>
                        )}
                      </Group>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm">{member.email}</Text>
                    </Table.Td>
                    {admin && (
                      <Table.Td style={{ width: 1 }} ta="right">
                        <RowActionsMenu label={t("common.table.operationsAria", { name: member.name })}>
                          <Menu.Item
                            color="red"
                            leftSection={<IconUserMinus size={14} />}
                            onClick={() => remove.requestDelete(member)}
                            aria-label={t("teams.removeAria", { name: member.name })}
                          >
                            {t("teams.remove")}
                          </Menu.Item>
                        </RowActionsMenu>
                      </Table.Td>
                    )}
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          )}
          <TeamJiraMembers teamId={id} />
        </Stack>
      </Box>

      {editing && (
        <TeamEditorModal
          target={data}
          onClose={() => setEditing(false)}
          onSaved={async () => {
            setEditing(false);
            await refresh();
          }}
        />
      )}

      <ConfirmDeleteModal
        confirm={remove}
        title={t("teams.removeTitle")}
        errorTitle={t("teams.removeFailed")}
        errorMessage={(err) =>
          saveErrorMessage(err, t, { failedStatus: "common.error.actionFailedStatus", failed: "common.error.actionFailed" })
        }
        body={(member) => t("teams.removeBody", { name: member.name, team: data.name })}
      />
    </Stack>
  );
}

/** The ADMIN add-member row: a searchable Select over the users list (server-side name filter). */
function AddMemberPicker({ teamId, memberIds, onAdded }: { teamId: number; memberIds: Set<number>; onAdded: () => Promise<void> }) {
  const { t } = useTranslation();
  const [search, setSearch] = useState("");
  const [picked, setPicked] = useState<string | null>(null);
  const [debounced] = useDebouncedValue(search.trim(), 300);

  const users = useQuery({
    queryKey: ["users", "picker", debounced],
    queryFn: () => listUsers({ page: 1, pageSize: 50, sort: "name", name: debounced || undefined }),
  });
  const options = (users.data?.items ?? [])
    .filter((u) => !memberIds.has(u.id))
    .map((u) => ({ value: String(u.id), label: `${u.name} (${u.email})` }));

  const add = useMutation({
    mutationFn: (userId: number) => addTeamMember(teamId, userId),
    onSuccess: async () => {
      setPicked(null);
      setSearch("");
      showSuccessToast(t("teams.toast.memberAdded"));
      await onAdded();
    },
  });

  return (
    <Stack gap="xs">
      <Group align="flex-end" gap="sm">
        <Select
          label={t("teams.addMember")}
          placeholder={t("teams.pickUser")}
          data={options}
          searchable
          searchValue={search}
          onSearchChange={setSearch}
          value={picked}
          onChange={setPicked}
          nothingFoundMessage={t("teams.noMatchingUsers")}
          clearable
          w={360}
        />
        <Button
          leftSection={<IconUserPlus size={16} />}
          disabled={!picked}
          loading={add.isPending}
          onClick={() => picked && add.mutate(Number(picked))}
        >
          {t("teams.add")}
        </Button>
      </Group>
      {add.isError && (
        <Alert color="red" variant="light" title={t("teams.addFailed")}>
          {saveErrorMessage(add.error, t, {
            conflict: "teams.addConflict",
            notFound: "teams.addGone",
            failedStatus: "common.error.actionFailedStatus",
            failed: "common.error.actionFailed",
          })}
        </Alert>
      )}
    </Stack>
  );
}
