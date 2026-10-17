import { useState } from "react";
import { Modal, Stack } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useTranslation } from "react-i18next";
import { ApiError } from "../api/http";
import { createTeam, updateTeam, type TeamResponse } from "../api/teams";
import RegistryEditorActions from "./RegistryEditorActions";
import RegistryMetadataFields from "./RegistryMetadataFields";
import TeamManagerSelect from "./TeamManagerSelect";
import {
  EMPTY_TEAM_FORM,
  MAX_TEAM_DESCRIPTION_LENGTH,
  MAX_TEAM_NAME_LENGTH,
  teamFormValidation,
  teamSaveErrorMessage,
  toTeamBody,
  toTeamFormValues,
  type TeamFormValues,
} from "../utils/teamForm";
import { showSuccessToast } from "../utils/toast";

/**
 * Create (target null) / edit (target set) — one modal, the same field block (the shared
 * registry shape) plus the optional Manager (a user who may edit the team's capacity). The Jira
 * members are managed on the details page, not here.
 */
export default function TeamEditorModal({
  target,
  onClose,
  onSaved,
}: {
  target: TeamResponse | null;
  onClose: () => void;
  onSaved: (saved: TeamResponse | null) => Promise<void>;
}) {
  const { t } = useTranslation();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const form = useForm<TeamFormValues>({
    initialValues: target ? toTeamFormValues(target) : EMPTY_TEAM_FORM,
    validate: teamFormValidation(t),
  });

  async function save(values: TeamFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      let saved: TeamResponse | null = null;
      if (target) {
        await updateTeam(target.id, toTeamBody(values));
        showSuccessToast(t("teams.toast.saved"));
      } else {
        saved = await createTeam(toTeamBody(values));
        showSuccessToast(t("teams.toast.created"));
      }
      await onSaved(saved);
    } catch (err) {
      // The 409 is about THIS control (the case-insensitive name clash) — mark the field.
      if (err instanceof ApiError && err.status === 409) {
        form.setFieldError("name", t("teams.saveConflict"));
      } else {
        setError(teamSaveErrorMessage(err, t));
      }
      setSubmitting(false);
    }
  }

  return (
    <Modal closeButtonProps={{ "aria-label": t("common.action.close") }} opened onClose={onClose} title={target ? t("teams.editTitle") : t("teams.createTitle")} centered>
      <form onSubmit={form.onSubmit(save)} noValidate>
        <Stack>
          <RegistryMetadataFields
            nameMaxLength={MAX_TEAM_NAME_LENGTH}
            descriptionMaxLength={MAX_TEAM_DESCRIPTION_LENGTH}
            descriptionLength={form.values.description.length}
            nameInputProps={form.getInputProps("name")}
            descriptionInputProps={form.getInputProps("description")}
          />
          <TeamManagerSelect
            picked={form.values.managerUserId !== null && form.values.managerLabel !== null ? { value: form.values.managerUserId, label: form.values.managerLabel } : null}
            onPick={(picked) => {
              form.setFieldValue("managerUserId", picked?.value ?? null);
              form.setFieldValue("managerLabel", picked?.label ?? null);
            }}
          />
          <RegistryEditorActions error={error} submitting={submitting} isEdit={Boolean(target)} onClose={onClose} gap="sm" />
        </Stack>
      </form>
    </Modal>
  );
}
