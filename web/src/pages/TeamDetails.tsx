import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useParams } from "react-router-dom";
import { Box, Button, Stack, Text } from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { IconPencil } from "@tabler/icons-react";
import { ApiError } from "../api/http";
import { useAdmin } from "../auth";
import { getTeam } from "../api/teams";
import EditPageLoadState from "../components/EditPageLoadState";
import PageHeader from "../components/PageHeader";
import TeamEditorModal from "../components/TeamEditorModal";
import TeamJiraMembers from "../components/TeamJiraMembers";
import { CONTENT_MAX_WIDTH } from "../utils/layout";
import { loadErrorMessage } from "../utils/saveError";
import { teamsPath } from "../utils/teamLinks";

/**
 * One team (`/teams/:id`): its name, description and manager, and the dated Jira-account membership
 * (`TeamJiraMembers`, D1) — the only member list a team has. Everyone reads it, its manager included; an ADMIN
 * edits the team (name, manager) and its Jira members.
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
        description={
          <>
            {data.description && (
              <Text span display="block">
                {data.description}
              </Text>
            )}
            <Text span display="block">
              {data.managerName ? t("teams.managerLine", { name: data.managerName }) : t("teams.noManager")}
            </Text>
          </>
        }
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
        <TeamJiraMembers teamId={id} />
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
    </Stack>
  );
}
