import { useTranslation } from "react-i18next";
import { Badge, Group, Select, Stack, Text } from "@mantine/core";
import { IconCalendarStats, IconUsersGroup } from "@tabler/icons-react";
import { useQuery } from "@tanstack/react-query";
import { useNavigate, useSearchParams } from "react-router-dom";
import { getSprintCalendar, getTeamCapacity, listTeamSprintCalendars, type SprintCalendarSummary } from "../api/capacity";
import { getReportFilters } from "../api/reports";
import { listTeams } from "../api/teams";
import EmptyState from "../components/EmptyState";
import ErrorAlert from "../components/ErrorAlert";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import SprintCalendarEditor from "../components/SprintCalendarEditor";
import TeamCapacityDefaultCard from "../components/TeamCapacityDefaultCard";
import { capacityPath, capacitySprintPath, capacityTeamPath, parseCapacitySelection } from "../utils/capacityLinks";
import { formatDate } from "../utils/formatDate";

const PICKER_PAGE_SIZE = 100;

/** `name · from → to`, the dates read in the configured zone; a sprint without a start date is just its name. */
function sprintLabel(sprint: SprintCalendarSummary, timeZone: string): string {
  if (sprint.startAt === null) return sprint.name;
  const end = sprint.endAt ?? sprint.completeAt;
  return `${sprint.name} · ${formatDate(sprint.startAt, "—", timeZone)} → ${formatDate(end, "—", timeZone)}`;
}

/**
 * Administration > Capacity (A32): plan how many man-days a team has in a sprint. The URL is the
 * selection (`?teamId=&sprintId=`, deep-linkable; changes `replace`). Everyone reads; the server says
 * per team whether the caller may edit (`canEdit`: the team's manager or an ADMIN) and the inputs
 * follow it. Two surfaces for the picked team: its default figure (`TEAM_DEFAULT`) and, once a sprint
 * is picked, that sprint's person x day calendar (`components/SprintCalendarGrid.tsx`).
 */
export default function Capacity() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const { teamId, sprintId } = parseCapacitySelection(params);

  const teams = useQuery({
    queryKey: ["teams", "capacity-picker"],
    queryFn: () => listTeams({ page: 1, pageSize: PICKER_PAGE_SIZE, sort: "name" }),
  });
  // The configured zone (any authenticated user may read it here) so the sprint dates match the grid's days.
  const filters = useQuery({ queryKey: ["reports", "filters"], queryFn: getReportFilters, staleTime: 60_000 });
  const timeZone = filters.data?.timeZone ?? "UTC";

  const sprints = useQuery({
    queryKey: ["capacity", "sprints", teamId],
    queryFn: () => listTeamSprintCalendars(teamId as number, { page: 1, pageSize: PICKER_PAGE_SIZE, sort: "-startAt" }),
    enabled: teamId !== undefined,
  });
  const teamCapacity = useQuery({
    queryKey: ["capacity", "team", teamId],
    queryFn: () => getTeamCapacity(teamId as number),
    enabled: teamId !== undefined,
  });
  const calendar = useQuery({
    queryKey: ["capacity", "calendar", teamId, sprintId],
    queryFn: () => getSprintCalendar(teamId as number, sprintId as number),
    enabled: teamId !== undefined && sprintId !== undefined,
  });

  const teamOptions = (teams.data?.items ?? []).map((team) => ({ value: String(team.id), label: team.name }));
  const sprintItems = sprints.data?.items ?? [];
  const sprintOptions = sprintItems.map((sprint) => ({ value: String(sprint.sprintId), label: sprintLabel(sprint, timeZone) }));
  const hasCalendar = new Set(sprintItems.filter((sprint) => sprint.hasCalendar).map((sprint) => String(sprint.sprintId)));
  const truncated = sprints.data !== undefined && sprints.data.total > sprintItems.length;

  function pickTeam(value: string | null) {
    navigate(value === null ? capacityPath : capacityTeamPath(Number(value)), { replace: true });
  }

  function pickSprint(value: string | null) {
    if (teamId === undefined) return;
    navigate(value === null ? capacityTeamPath(teamId) : capacitySprintPath(teamId, Number(value)), { replace: true });
  }

  const toolbar = (
    <Group align="flex-start" gap="md" wrap="wrap">
      <Select
        label={t("capacity.team.label")}
        placeholder={t("capacity.team.placeholder")}
        data={teamOptions}
        value={teamId === undefined ? null : String(teamId)}
        onChange={pickTeam}
        searchable
        w={260}
        disabled={teams.data === undefined}
      />
      <Select
        label={t("capacity.sprint.label")}
        placeholder={t("capacity.sprint.placeholder")}
        data={sprintOptions}
        value={sprintId === undefined ? null : String(sprintId)}
        onChange={pickSprint}
        renderOption={({ option }) => (
          <Group gap="xs" wrap="nowrap" justify="space-between" w="100%">
            <Text size="sm">{option.label}</Text>
            {hasCalendar.has(option.value) && (
              <Badge size="xs" color="gray">
                {t("capacity.sprint.calendarBadge")}
              </Badge>
            )}
          </Group>
        )}
        searchable
        clearable
        w={420}
        disabled={teamId === undefined || sprints.data === undefined}
        description={truncated ? t("capacity.sprint.truncated", { shown: sprintItems.length, total: sprints.data?.total }) : undefined}
      />
    </Group>
  );

  return (
    <Stack gap="md">
      <PageHeader title={t("capacity.title")} description={t("capacity.description")} toolbar={toolbar} />
      {teams.isError && <ErrorAlert error={teams.error} />}
      {teams.data?.items.length === 0 && <EmptyState icon={IconUsersGroup} label={t("capacity.team.none")} />}
      {teamId === undefined && teams.data !== undefined && teams.data.items.length > 0 && (
        <EmptyState icon={IconCalendarStats} label={t("capacity.pick")} />
      )}
      {teamId !== undefined && (
        <>
          {teamCapacity.isError && <ErrorAlert error={teamCapacity.error} />}
          {teamCapacity.data && (
            <TeamCapacityDefaultCard key={`${teamCapacity.data.teamId}-${teamCapacity.data.updatedAt ?? "none"}`} capacity={teamCapacity.data} />
          )}
          {sprints.isError && <ErrorAlert error={sprints.error} />}
          {sprints.data?.total === 0 && <EmptyState icon={IconCalendarStats} label={t("capacity.sprint.none")} />}
          {sprintId === undefined && sprintItems.length > 0 && <EmptyState icon={IconCalendarStats} label={t("capacity.pick")} />}
          {sprintId !== undefined && calendar.isLoading && <LoadingBlock />}
          {sprintId !== undefined && calendar.isError && <ErrorAlert error={calendar.error} />}
          {calendar.data && sprintId !== undefined && (
            <SprintCalendarEditor
              key={`${calendar.data.teamId}-${calendar.data.sprintId}-${calendar.data.updatedAt ?? "none"}`}
              calendar={calendar.data}
            />
          )}
        </>
      )}
    </Stack>
  );
}
