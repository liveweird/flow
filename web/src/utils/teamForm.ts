import type { TFunction } from "i18next";
import type { TeamCreateBody, TeamResponse, TeamUpdateBody } from "../api/teams";
import { descriptionRule, nameRule } from "./formRules";
import { saveErrorMessage } from "./saveError";

// Server limits (teams/Team.kt) mirrored client-side.
export const MAX_TEAM_NAME_LENGTH = 100;
export const MAX_TEAM_DESCRIPTION_LENGTH = 500;

export type TeamFormValues = {
  name: string;
  description: string;
};

export const EMPTY_TEAM_FORM: TeamFormValues = { name: "", description: "" };

export function toTeamFormValues(team: TeamResponse): TeamFormValues {
  return { name: team.name, description: team.description ?? "" };
}

/** Validation rules shared by the create and edit modal (mirrors the server's checks). */
export function teamFormValidation(t: TFunction) {
  return {
    name: nameRule(t, "teams.validation.nameLength", MAX_TEAM_NAME_LENGTH),
    description: descriptionRule(t, "teams.validation.descriptionLength", MAX_TEAM_DESCRIPTION_LENGTH),
  };
}

/** The wire body: a blank description travels as null (the server stores NULL, never ""). */
export function toTeamBody(values: TeamFormValues): TeamCreateBody & TeamUpdateBody {
  const description = values.description.trim();
  return { name: values.name.trim(), description: description === "" ? null : description };
}

/** The team save vocabulary — 409 is about the name field and is handled by the caller. */
export function teamSaveErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    forbidden: "teams.saveForbidden",
    notFound: "teams.saveGone",
    invalid: "teams.saveInvalid",
    failedStatus: "common.error.saveFailedStatus",
    failed: "common.error.saveFailedNetwork",
  });
}
