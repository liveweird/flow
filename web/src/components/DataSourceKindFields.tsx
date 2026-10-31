import { useTranslation } from "react-i18next";
import { Button, Input, PasswordInput, SegmentedControl, Select, TagsInput, TextInput } from "@mantine/core";
import type { UseFormReturnType } from "@mantine/form";
import type { DataSourceKind } from "../api/dataSources";
import {
  MAX_API_TOKEN_LENGTH,
  MAX_GROUP_PATHS,
  MAX_PATH_EXCLUSIONS,
  MAX_PROJECT_KEYS,
  type DataSourceFormValues,
} from "../utils/dataSourceForm";

type DataSourceForm = UseFormReturnType<DataSourceFormValues>;

const KINDS: DataSourceKind[] = ["JIRA_CLOUD", "GITLAB"];

/**
 * The kind picker: chosen at create, then fixed (the server answers a changed kind with 409). A kind this server cannot offer
 * (GitLab without an operator allow-list, or while the `/kinds` read is pending or failed) is disabled, with the reason under
 * the control once the read has answered, or a "could not check" note when it failed.
 */
export function DataSourceKindPicker({
  value,
  onChange,
  disabled,
  gitlabEnabled,
  kindsKnown,
  kindsFailed,
}: {
  value: DataSourceKind;
  onChange: (kind: DataSourceKind) => void;
  disabled: boolean;
  gitlabEnabled: boolean;
  kindsKnown: boolean;
  kindsFailed: boolean;
}) {
  const { t } = useTranslation();
  let hint: string | undefined;
  if (kindsFailed) hint = t("dataSources.field.kindUnknownHint");
  else if (kindsKnown && !gitlabEnabled) hint = t("dataSources.field.kindDisabledHint");
  return (
    <Input.Wrapper
      labelElement="div"
      label={t("dataSources.field.kind")}
      description={hint}
    >
      <SegmentedControl
        fullWidth
        aria-label={t("dataSources.field.kind")}
        value={value}
        disabled={disabled}
        onChange={(next) => onChange(next as DataSourceKind)}
        data={KINDS.map((kind) => ({
          value: kind,
          label: t(`dataSources.kind.${kind}`),
          disabled: kind === "GITLAB" && !gitlabEnabled && value !== "GITLAB",
        }))}
      />
    </Input.Wrapper>
  );
}

/** The Jira Cloud connection block: site, service account, token, project keys and the auth scheme. */
export function JiraConnectionFields({ form, isEdit }: { form: DataSourceForm; isEdit: boolean }) {
  const { t } = useTranslation();
  return (
    <>
      <TextInput
        label={t("dataSources.field.siteUrl")}
        description={t("dataSources.field.siteUrlHint")}
        placeholder="https://your-tenant.atlassian.net"
        disabled={isEdit}
        {...form.getInputProps("siteUrl")}
      />
      <TextInput label={t("dataSources.field.email")} {...form.getInputProps("email")} />
      <PasswordInput
        label={t("dataSources.field.apiToken")}
        description={isEdit ? t("dataSources.field.apiTokenKeep") : undefined}
        maxLength={MAX_API_TOKEN_LENGTH}
        autoComplete="new-password"
        {...form.getInputProps("apiToken")}
      />
      <TagsInput
        label={t("dataSources.field.projectKeys")}
        description={t("dataSources.field.projectKeysHint")}
        maxTags={MAX_PROJECT_KEYS}
        {...form.getInputProps("projectKeys")}
        onChange={(values) => form.setFieldValue("projectKeys", values.map((value) => value.toUpperCase()))}
      />
      <Select
        label={t("dataSources.field.authScheme")}
        data={[
          { value: "BASIC", label: t("dataSources.authScheme.BASIC") },
          { value: "BEARER", label: t("dataSources.authScheme.BEARER") },
        ]}
        allowDeselect={false}
        {...form.getInputProps("authScheme")}
      />
    </>
  );
}

/** The GitLab connection block: instance origin (fixed once created), read-only token, the group paths to read and the excluded paths. */
export function GitLabConnectionFields({
  form,
  isEdit,
  allowedOrigins,
  defaultPathExclusions,
}: {
  form: DataSourceForm;
  isEdit: boolean;
  allowedOrigins: string[];
  /** The server's built-in excluded-path list (`GET /data-sources/kinds`), what "Reset to defaults" restores. */
  defaultPathExclusions: string[];
}) {
  const { t } = useTranslation();
  const atDefaults =
    form.values.pathExclusions.length === defaultPathExclusions.length &&
    form.values.pathExclusions.every((pattern, index) => pattern === defaultPathExclusions[index]);
  return (
    <>
      <TextInput
        label={t("dataSources.field.baseUrl")}
        description={
          allowedOrigins.length > 0
            ? t("dataSources.field.baseUrlHint", { origins: allowedOrigins.join(", ") })
            : t("dataSources.field.baseUrlHintOpen")
        }
        placeholder="https://gitlab.example.com"
        disabled={isEdit}
        {...form.getInputProps("baseUrl")}
      />
      <PasswordInput
        label={t("dataSources.field.token")}
        description={isEdit ? t("dataSources.field.tokenKeep") : t("dataSources.field.tokenHint")}
        maxLength={MAX_API_TOKEN_LENGTH}
        autoComplete="new-password"
        {...form.getInputProps("token")}
      />
      <TagsInput
        label={t("dataSources.field.groupPaths")}
        description={t("dataSources.field.groupPathsHint")}
        maxTags={MAX_GROUP_PATHS}
        {...form.getInputProps("groupPaths")}
        onChange={(values) => form.setFieldValue("groupPaths", values.map((value) => value.toLowerCase()))}
      />
      <div>
        <TagsInput
          label={t("dataSources.field.pathExclusions")}
          description={t("dataSources.field.pathExclusionsHint")}
          maxTags={MAX_PATH_EXCLUSIONS}
          splitChars={[",", " "]}
          {...form.getInputProps("pathExclusions")}
        />
        <Button
          type="button"
          variant="subtle"
          size="compact-xs"
          mt={4}
          disabled={atDefaults}
          onClick={() => form.setFieldValue("pathExclusions", [...defaultPathExclusions])}
        >
          {t("dataSources.field.pathExclusionsReset")}
        </Button>
      </div>
    </>
  );
}
