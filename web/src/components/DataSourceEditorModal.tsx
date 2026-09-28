import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Button, Modal, NumberInput, PasswordInput, Select, Stack, Switch, TagsInput, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { ApiError } from "../api/http";
import {
  createDataSource,
  testDataSourceAdHoc,
  testDataSourceStored,
  updateDataSource,
  type ConnectionTestResult,
  type DataSourceResponse,
} from "../api/dataSources";
import {
  dataSourceFormValidation,
  dataSourceSaveErrorMessage,
  EMPTY_DATA_SOURCE_FORM,
  fromDataSourceResponse,
  MAX_API_TOKEN_LENGTH,
  MAX_DATA_SOURCE_NAME_LENGTH,
  MAX_PROJECT_KEYS,
  MAX_RECONCILE_HOUR_UTC,
  MAX_SYNC_INTERVAL_MINUTES,
  MIN_RECONCILE_HOUR_UTC,
  MIN_SYNC_INTERVAL_MINUTES,
  testConnectionErrorMessage,
  toDataSourceRequest,
  toJiraConnectionRequest,
  type DataSourceFormValues,
} from "../utils/dataSourceForm";
import { showSuccessToast } from "../utils/toast";
import ConnectionTestResults from "./ConnectionTestResults";
import RegistryEditorActions from "./RegistryEditorActions";

/**
 * Create (target null) / edit (target set) — a port of Covenant's `ToadieConnectionEditorModal`
 * (the site URL is the connection's identity, so it disables like Toadie's `baseUrl` once a
 * connection exists). Adds the Test-connection button the Toadie modal has no counterpart for:
 * it probes with the CURRENT form values (an ad-hoc call) unless editing with a blank token
 * field, which probes with the stored, already-saved token instead.
 */
export default function DataSourceEditorModal({
  target,
  onClose,
  onSaved,
}: {
  target: DataSourceResponse | null;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [testing, setTesting] = useState(false);
  const [testError, setTestError] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<ConnectionTestResult | null>(null);

  const isEdit = target !== null;
  const form = useForm<DataSourceFormValues>({
    initialValues: target ? fromDataSourceResponse(target) : EMPTY_DATA_SOURCE_FORM,
    validate: dataSourceFormValidation(t, isEdit),
  });

  async function save(values: DataSourceFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      if (target) {
        await updateDataSource(target.id, toDataSourceRequest(values));
        showSuccessToast(t("dataSources.toast.saved"));
      } else {
        await createDataSource(toDataSourceRequest(values));
        showSuccessToast(t("dataSources.toast.created"));
      }
      await onSaved();
    } catch (err) {
      // The 409 is about THIS control (a case-insensitive name clash — siteUrl can't change
      // once a connection exists, so a saved conflict here is always the name).
      if (err instanceof ApiError && err.status === 409) {
        form.setFieldError("name", t("dataSources.saveConflict"));
      } else {
        setError(dataSourceSaveErrorMessage(err, t));
      }
      setSubmitting(false);
    }
  }

  /** Validates only the Jira connection fields the probe needs — the rest of the form may be incomplete. */
  function jiraFieldsValid(): boolean {
    const rules = dataSourceFormValidation(t, isEdit);
    const errors = {
      siteUrl: rules.siteUrl(form.values.siteUrl),
      email: rules.email(form.values.email),
      apiToken: rules.apiToken(form.values.apiToken),
      projectKeys: rules.projectKeys(form.values.projectKeys),
    };
    for (const [field, message] of Object.entries(errors)) {
      form.setFieldError(field, message ?? undefined);
    }
    return Object.values(errors).every((message) => !message);
  }

  async function runTest() {
    setTestError(null);
    if (!jiraFieldsValid()) return;
    setTesting(true);
    setTestResult(null);
    try {
      const result = target && form.values.apiToken.trim() === ""
        ? await testDataSourceStored(target.id)
        : await testDataSourceAdHoc({ jira: toJiraConnectionRequest(form.values) });
      setTestResult(result);
    } catch (err) {
      setTestError(testConnectionErrorMessage(err, t));
    } finally {
      setTesting(false);
    }
  }

  return (
    <Modal
      closeButtonProps={{ "aria-label": t("common.action.close") }}
      opened
      onClose={onClose}
      title={target ? t("dataSources.editTitle") : t("dataSources.createTitle")}
      size="lg"
      centered
    >
      <form onSubmit={form.onSubmit(save)} noValidate>
        <Stack>
          <TextInput
            label={t("common.field.name")}
            maxLength={MAX_DATA_SOURCE_NAME_LENGTH}
            data-autofocus
            {...form.getInputProps("name")}
          />
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
          <TextInput
            label={t("dataSources.field.backfillFrom")}
            description={t("dataSources.field.backfillFromHint")}
            placeholder="YYYY-MM-DD"
            {...form.getInputProps("backfillFrom")}
          />
          <NumberInput
            label={t("dataSources.field.syncInterval")}
            min={MIN_SYNC_INTERVAL_MINUTES}
            max={MAX_SYNC_INTERVAL_MINUTES}
            allowDecimal={false}
            {...form.getInputProps("syncIntervalMinutes")}
          />
          <NumberInput
            label={t("dataSources.field.reconcileHour")}
            min={MIN_RECONCILE_HOUR_UTC}
            max={MAX_RECONCILE_HOUR_UTC}
            allowDecimal={false}
            {...form.getInputProps("reconcileHourUtc")}
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
          <Switch label={t("dataSources.field.enabled")} {...form.getInputProps("enabled", { type: "checkbox" })} />

          <Button type="button" variant="default" loading={testing} onClick={() => void runTest()}>
            {t("dataSources.testConnection")}
          </Button>
          {testError && (
            <Alert color="red" variant="light">
              {testError}
            </Alert>
          )}
          {testResult && <ConnectionTestResults result={testResult} />}

          <RegistryEditorActions error={error} submitting={submitting} isEdit={isEdit} onClose={onClose} gap="sm" />
        </Stack>
      </form>
    </Modal>
  );
}
