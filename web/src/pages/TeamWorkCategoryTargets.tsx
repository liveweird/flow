import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useParams } from "react-router-dom";
import { Alert, Badge, Box, Button, Group, NumberInput, Stack, Table, Text } from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/http";
import {
  getTeam,
  getTeamWorkCategoryTargets,
  updateTeamWorkCategoryTargets,
  type TeamWorkCategoryTargetRow,
  type TeamWorkCategoryTargets as TargetsResponse,
  type TeamWorkCategoryTargetsBody,
} from "../api/teams";
import { useAdmin } from "../auth";
import ConfirmActionModal from "../components/ConfirmActionModal";
import EditPageLoadState from "../components/EditPageLoadState";
import PageHeader from "../components/PageHeader";
import { CONTENT_MAX_WIDTH } from "../utils/layout";
import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { teamsPath } from "../utils/teamLinks";
import { showSuccessToast } from "../utils/toast";
import FormErrorAlert from "../components/FormErrorAlert";

/** The server's literal for the "No category" row (`TeamWorkCategoryTargets` in the spec). */
const UNCATEGORIZED = "UNCATEGORIZED";
const TARGET_TOTAL = 100;

type Draft = Record<string, string | number>;

/**
 * One team's work-category targets (`/teams/:id/work-category-targets`, A43): the share of its effort each work
 * category should take, in whole percent. Everyone reads the table; an ADMIN edits it (the server is the rule —
 * the page only leaves out the inputs and Save). The set is saved whole: exactly 100 %, or every field empty for none.
 */
export default function TeamWorkCategoryTargets() {
  const { t } = useTranslation();
  const { id: idParam } = useParams();
  const id = Number(idParam);
  const validId = Number.isInteger(id) && id > 0;

  const team = useQuery({ queryKey: ["teams", "detail", id], queryFn: () => getTeam(id), enabled: validId });
  const targets = useQuery({
    queryKey: ["teams", "work-category-targets", id],
    queryFn: () => getTeamWorkCategoryTargets(id),
    enabled: validId,
  });

  if (!validId || !team.data || !targets.data) {
    const failure = team.error ?? targets.error;
    const notFound = !validId || (failure instanceof ApiError && failure.status === 404);
    return (
      <EditPageLoadState
        isLoading={validId && !failure}
        message={notFound ? t("teams.notFound") : loadErrorMessage(failure, t)}
        backTo={teamsPath}
        backLabel={t("teams.backToTeams")}
        title={t("teams.targets.title")}
      />
    );
  }

  // Keyed by the stored set's stamp: a save (or another admin's change) brings the fresh stored state in, while a
  // background refetch that changed nothing never replaces what is being typed.
  return <TargetsEditor key={targets.data.updatedAt ?? "none"} teamId={id} teamName={team.data.name} data={targets.data} />;
}

function initialDraft(rows: readonly TeamWorkCategoryTargetRow[]): Draft {
  return Object.fromEntries(rows.filter((row) => row.mapped).map((row) => [row.key, row.targetPercent ?? ""]));
}

function TargetsEditor({ teamId, teamName, data }: { teamId: number; teamName: string; data: TargetsResponse }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const admin = useAdmin();
  const [draft, setDraft] = useState<Draft>(() => initialDraft(data.categories));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmingClear, setConfirmingClear] = useState(false);

  // An admin edits the mapped rows (what a save keeps); everyone else reads the stored set as it is.
  const counted = admin ? data.categories.filter((row) => row.mapped) : data.categories;
  const valueOf = (row: TeamWorkCategoryTargetRow): string | number => (admin && row.mapped ? (draft[row.key] ?? "") : (row.targetPercent ?? ""));
  const filled = counted.filter((row) => valueOf(row) !== "");
  const total = filled.reduce((sum, row) => sum + Number(valueOf(row)), 0);
  const valid = filled.length === 0 || total === TARGET_TOTAL;
  const hasUnmapped = data.categories.some((row) => !row.mapped);
  const hasStored = data.categories.some((row) => row.targetPercent !== null);

  async function replace(targets: TeamWorkCategoryTargetsBody["targets"], toast: string) {
    setError(null);
    setSaving(true);
    try {
      await updateTeamWorkCategoryTargets(teamId, { targets });
      await refreshQueriesAfterMutation(queryClient, ["teams", "work-category-targets", teamId]);
      showSuccessToast(toast);
      setConfirmingClear(false);
    } catch (err) {
      setConfirmingClear(false);
      setError(
        saveErrorMessage(err, t, {
          forbidden: "teams.targets.error.forbidden",
          notFound: "teams.targets.error.notFound",
          invalid: "teams.targets.error.invalid",
          failed: "teams.targets.error.failed",
        }),
      );
    } finally {
      setSaving(false);
    }
  }

  const categoryLabel = (row: TeamWorkCategoryTargetRow) => (row.key === UNCATEGORIZED ? t("teams.targets.noCategory") : row.key);

  return (
    <Stack gap="md">
      <PageHeader
        title={t("teams.targets.heading", { name: teamName })}
        description={t("teams.targets.intro")}
        backTo={{ to: teamsPath, label: t("teams.backToTeams") }}
      />
      <Box maw={CONTENT_MAX_WIDTH}>
        <Stack gap="sm">
          {!admin && (
            <Alert color="gray" variant="light">
              {t("teams.targets.readOnly")}
            </Alert>
          )}
          {!hasStored && (
            <Text size="sm" c="dimmed">
              {t("teams.targets.none")}
            </Text>
          )}
          <Table aria-label={t("teams.targets.tableAria")}>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t("teams.targets.column.category")}</Table.Th>
                <Table.Th>{t("teams.targets.column.target")}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {data.categories.map((row) => (
                <Table.Tr key={row.key}>
                  <Table.Td>
                    <Group gap="xs" wrap="nowrap">
                      <Text size="sm" c={row.key === UNCATEGORIZED ? "dimmed" : undefined}>
                        {categoryLabel(row)}
                      </Text>
                      {!row.mapped && (
                        <Badge color="orange" size="sm">
                          {t("teams.targets.unmapped")}
                        </Badge>
                      )}
                    </Group>
                  </Table.Td>
                  <Table.Td>
                    {admin && row.mapped ? (
                      <NumberInput
                        aria-label={t("teams.targets.inputAria", { name: categoryLabel(row) })}
                        w={120}
                        min={0}
                        max={TARGET_TOTAL}
                        allowDecimal={false}
                        allowNegative={false}
                        clampBehavior="strict"
                        hideControls
                        rightSection="%"
                        rightSectionWidth={32}
                        value={draft[row.key] ?? ""}
                        onChange={(value) => setDraft((previous) => ({ ...previous, [row.key]: value }))}
                      />
                    ) : (
                      <Text size="sm" c={row.targetPercent === null ? "dimmed" : undefined}>
                        {row.targetPercent === null ? "—" : `${row.targetPercent} %`}
                      </Text>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
            <Table.Tfoot>
              <Table.Tr>
                <Table.Th>{t("teams.targets.total")}</Table.Th>
                <Table.Th>
                  <Text span fw={600} size="sm" c={filled.length === 0 ? "dimmed" : valid ? "teal" : "red"}>
                    {filled.length === 0 ? "—" : `${total} %`}
                  </Text>
                </Table.Th>
              </Table.Tr>
            </Table.Tfoot>
          </Table>

          {admin && !valid && (
            <FormErrorAlert>
              {t("teams.targets.invalidTotal", { total })}
            </FormErrorAlert>
          )}
          {admin && (
            <Text size="sm" c="dimmed">
              {t("teams.targets.rule")}
            </Text>
          )}
          {hasUnmapped && (
            <Text size="sm" c="dimmed">
              {t(admin ? "teams.targets.unmappedHintAdmin" : "teams.targets.unmappedHint")}
            </Text>
          )}
          {error && (
            <FormErrorAlert>
              {error}
            </FormErrorAlert>
          )}
          {admin && (
            <Group gap="sm">
              <Button
                loading={saving}
                disabled={!valid}
                onClick={() =>
                  void replace(
                    filled.map((row) => ({ key: row.key, targetPercent: Number(valueOf(row)) })),
                    t("teams.targets.toast.saved"),
                  )
                }
              >
                {t("common.action.save")}
              </Button>
              <Button variant="default" disabled={!hasStored || saving} onClick={() => setConfirmingClear(true)}>
                {t("teams.targets.clear")}
              </Button>
            </Group>
          )}
        </Stack>
      </Box>

      <ConfirmActionModal
        opened={confirmingClear}
        onClose={() => setConfirmingClear(false)}
        title={t("teams.targets.clearTitle")}
        message={t("teams.targets.clearBody", { name: teamName })}
        cancelLabel={t("common.action.cancel")}
        confirmLabel={t("teams.targets.clear")}
        onConfirm={() => void replace([], t("teams.targets.toast.cleared"))}
        loading={saving}
      />
    </Stack>
  );
}
