import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Badge, Button, Checkbox, Group, Modal, Select, Stack, Table, Text } from "@mantine/core";
import { useMutation, useQuery } from "@tanstack/react-query";
import { IconUsersGroup } from "@tabler/icons-react";
import { listJiraTeams, listTeamJiraSeedCandidates, seedTeamJiraMemberships } from "../api/metrics";
import { epochMillisToIsoDateInZone } from "../utils/isoDate";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import EmptyState from "./EmptyState";
import ErrorAlert from "./ErrorAlert";
import LoadingBlock from "./LoadingBlock";
import ScrollRegion from "./ScrollRegion";

/** The server's cap on one seed request's `accountIds` (`MAX_SEED_ACCOUNTS`). */
const MAX_SEED_ACCOUNTS = 100;

/**
 * "Seed from Jira Team field" (D1, ADMIN only): pick an Atlassian Team, review the people assigned to its issues
 * (`GET /api/v1/teams/{id}/jira-memberships/seed-candidates`) and apply the ticked ones
 * (`POST …/seed`). Each seeded membership starts on the calendar day (configured metrics zone) of the person's
 * earliest issue for that team and stays open-ended; a person who already has an overlapping membership is shown
 * with the conflicting team, unticked and disabled. The server recomputes the candidates on apply, so a stale list can
 * never create anything the preview would not.
 */
// Dates and the conflicting team's name stay on one line; the ScrollRegion scrolls sideways on a narrow screen.
const NO_WRAP = { whiteSpace: "nowrap" } as const;
const BADGE_FULL_LABEL = { root: { overflow: "visible" }, label: { overflow: "visible" } } as const;

export default function JiraSeedModal({
  teamId,
  timeZone,
  onClose,
  onSeeded,
}: {
  teamId: number;
  /** The configured metrics zone the first/last-seen days are read in. */
  timeZone: string;
  onClose: () => void;
  onSeeded: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const [atlassianTeamId, setAtlassianTeamId] = useState<string | null>(null);
  // The ticked people are stored EXPLICITLY, per Atlassian team: everyone without a conflict starts ticked on the FIRST load of a
  // team's candidates only, so a later refetch (window focus) never auto-ticks a candidate that appeared since.
  const [ticks, setTicks] = useState<{ teamId: string; ids: ReadonlySet<string> } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const teams = useQuery({ queryKey: ["jira-teams"], queryFn: listJiraTeams });
  const candidates = useQuery({
    queryKey: ["team-jira-seed-candidates", teamId, atlassianTeamId],
    queryFn: () => listTeamJiraSeedCandidates(teamId, atlassianTeamId as string),
    enabled: atlassianTeamId !== null,
    // The preview is a decision aid: never serve a cached list from an earlier opening.
    gcTime: 0,
  });

  const rows = candidates.data ?? [];
  if (candidates.data && atlassianTeamId !== null && ticks?.teamId !== atlassianTeamId) {
    setTicks({ teamId: atlassianTeamId, ids: new Set(candidates.data.filter((row) => row.conflict == null).map((row) => row.accountId)) });
  }
  const isTicked = (row: (typeof rows)[number]) => row.conflict == null && (ticks?.ids.has(row.accountId) ?? false);
  const selected = rows.filter(isTicked).map((row) => row.accountId);
  const tooMany = selected.length > MAX_SEED_ACCOUNTS;

  const apply = useMutation({
    mutationFn: () => seedTeamJiraMemberships(teamId, { atlassianTeamId: atlassianTeamId as string, accountIds: selected }),
    onSuccess: async (result) => {
      showSuccessToast(t("metrics.teamSeed.toast.seeded", { created: result.created.length, skipped: result.skipped.length }));
      await onSeeded();
    },
    onError: (err) => {
      setError(
        saveErrorMessage(err, t, {
          forbidden: "metrics.teamSeed.error.forbidden",
          notFound: "metrics.teamSeed.error.notFound",
          conflict: "metrics.teamSeed.error.conflict",
          invalid: "metrics.teamSeed.error.invalid",
          failedStatus: "common.error.actionFailedStatus",
          failed: "common.error.actionFailed",
        }),
      );
    },
  });

  function pickTeam(value: string | null) {
    setAtlassianTeamId(value);
    setTicks(null);
    setError(null);
  }

  function toggle(accountId: string, checked: boolean) {
    setTicks((current) => {
      if (current === null) return current;
      const ids = new Set(current.ids);
      if (checked) ids.add(accountId);
      else ids.delete(accountId);
      return { teamId: current.teamId, ids };
    });
  }

  const options = (teams.data ?? []).map((team) => ({
    value: team.id,
    label: t("metrics.teamSeed.teamOption", { name: team.name, count: team.issueCount }),
  }));

  return (
    <Modal
      closeButtonProps={{ "aria-label": t("common.action.close") }}
      opened
      onClose={onClose}
      title={t("metrics.teamSeed.title")}
      size={1000}
      centered
    >
      <Stack>
        <Text size="sm" c="dimmed">
          {t("metrics.teamSeed.intro")}
        </Text>
        {teams.isError && <ErrorAlert error={teams.error} />}
        <Select
          label={t("metrics.teamSeed.field.team")}
          description={t("metrics.teamSeed.field.teamHint")}
          placeholder={t("metrics.teamSeed.pickTeam")}
          data={options}
          value={atlassianTeamId}
          onChange={pickTeam}
          searchable
          nothingFoundMessage={t("metrics.teamSeed.noTeams")}
          disabled={teams.isLoading}
        />
        {atlassianTeamId !== null && candidates.isLoading && <LoadingBlock />}
        {candidates.isError && <ErrorAlert error={candidates.error} />}
        {candidates.isSuccess && rows.length === 0 && <EmptyState icon={IconUsersGroup} label={t("metrics.teamSeed.noCandidates")} />}
        {rows.length > 0 && (
          <ScrollRegion label={t("metrics.teamSeed.tableAria")} minWidth={900} maxHeight={360}>
            <Table aria-label={t("metrics.teamSeed.tableAria")}>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th aria-label={t("metrics.teamSeed.field.pick")} style={{ width: 1 }} />
                  <Table.Th>{t("metrics.teamSeed.field.person")}</Table.Th>
                  <Table.Th>{t("metrics.teamSeed.field.issues")}</Table.Th>
                  <Table.Th>{t("metrics.teamSeed.field.firstSeen")}</Table.Th>
                  <Table.Th>{t("metrics.teamSeed.field.lastSeen")}</Table.Th>
                  <Table.Th>{t("metrics.teamSeed.field.startsOn")}</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {rows.map((row) => (
                  <Table.Tr key={row.accountId}>
                    <Table.Td>
                      <Checkbox
                        aria-label={t("metrics.teamSeed.pickAria", { name: row.displayName })}
                        checked={isTicked(row)}
                        disabled={row.conflict != null || apply.isPending}
                        onChange={(event) => toggle(row.accountId, event.currentTarget.checked)}
                      />
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm">{row.displayName}</Text>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm">{row.issueCount}</Text>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm" style={NO_WRAP}>
                        {epochMillisToIsoDateInZone(row.firstSeen, timeZone)}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm" style={NO_WRAP}>
                        {epochMillisToIsoDateInZone(row.lastSeen, timeZone)}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm" style={NO_WRAP}>
                        {epochMillisToIsoDateInZone(row.validFrom, timeZone)}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      {row.conflict != null && (
                        <Badge size="xs" variant="light" color="orange" styles={BADGE_FULL_LABEL}>
                          {t("metrics.teamSeed.conflict", { team: row.conflict.teamName })}
                        </Badge>
                      )}
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </ScrollRegion>
        )}
        {rows.length > 0 && (
          <Text size="xs" c="dimmed">
            {t("metrics.teamSeed.hint")}
          </Text>
        )}
        {tooMany && (
          <Alert color="red" variant="light">
            {t("metrics.teamSeed.tooMany", { max: MAX_SEED_ACCOUNTS })}
          </Alert>
        )}
        {error && (
          <Alert color="red" variant="light">
            {error}
          </Alert>
        )}
        <Group justify="flex-end" gap="sm">
          <Button type="button" variant="default" onClick={onClose} disabled={apply.isPending}>
            {t("common.action.cancel")}
          </Button>
          <Button
            type="button"
            loading={apply.isPending}
            disabled={selected.length === 0 || tooMany}
            onClick={() => {
              setError(null);
              apply.mutate();
            }}
          >
            {t("metrics.teamSeed.apply", { count: selected.length })}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
