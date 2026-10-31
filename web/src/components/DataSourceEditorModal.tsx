import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { Button, Modal, NumberInput, Stack, Switch, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import type { ParseKeys } from "i18next";
import { ApiError } from "../api/http";
import { getMetricsSettings } from "../api/metrics";
import {
  createDataSource,
  getDataSourceKinds,
  testDataSourceAdHoc,
  testDataSourceStored,
  updateDataSource,
  type ConnectionTestResult,
  type DataSourceKind,
  type DataSourceResponse,
} from "../api/dataSources";
import {
  dataSourceConflictField,
  dataSourceFormValidation,
  dataSourceSaveErrorMessage,
  EMPTY_DATA_SOURCE_FORM,
  fromDataSourceResponse,
  MAX_DATA_SOURCE_NAME_LENGTH,
  MAX_RECONCILE_HOUR_UTC,
  MAX_SYNC_INTERVAL_MINUTES,
  MIN_RECONCILE_HOUR_UTC,
  MIN_SYNC_INTERVAL_MINUTES,
  testConnectionErrorMessage,
  toDataSourceRequest,
  toGitLabConnectionRequest,
  toJiraConnectionRequest,
  type DataSourceFormValues,
} from "../utils/dataSourceForm";
import { showSuccessToast } from "../utils/toast";
import ConnectionTestResults from "./ConnectionTestResults";
import { DataSourceKindPicker, GitLabConnectionFields, JiraConnectionFields } from "./DataSourceKindFields";
import RegistryEditorActions from "./RegistryEditorActions";
import FormErrorAlert from "./FormErrorAlert";

/** The fixed message under the field a 409 names ([dataSourceConflictField]). */
const CONFLICT_MESSAGE_KEYS: Record<ReturnType<typeof dataSourceConflictField>, ParseKeys> = {
  name: "dataSources.saveConflict",
  siteUrl: "dataSources.saveConflictSite",
  projectKeys: "dataSources.saveConflictProjects",
  baseUrl: "dataSources.saveConflictInstance",
  groupPaths: "dataSources.saveConflictGroups",
};

/** The connection fields each kind's Test connection needs (the rest of the form may be incomplete). */
const PROBE_FIELDS: Record<DataSourceKind, ("siteUrl" | "email" | "apiToken" | "projectKeys" | "baseUrl" | "token" | "groupPaths")[]> = {
  JIRA_CLOUD: ["siteUrl", "email", "apiToken", "projectKeys"],
  GITLAB: ["baseUrl", "token", "groupPaths"],
};

/** The values a kind switch empties: the OTHER kind's connection fields, so nothing typed for one kind lingers under the other. */
const KIND_RESETS: Record<DataSourceKind, Partial<DataSourceFormValues>> = {
  JIRA_CLOUD: { baseUrl: "", token: "", groupPaths: [] },
  GITLAB: { siteUrl: "", email: "", apiToken: "", projectKeys: [], authScheme: "BASIC" },
};

/**
 * Create (target null) / edit (target set) — a port of Covenant's `ToadieConnectionEditorModal`
 * (the site URL / GitLab base URL is the connection's identity, so it disables like Toadie's `baseUrl`
 * once a connection exists, and so does the kind). Adds the Test-connection button the Toadie modal has no counterpart for:
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
  // The history horizon's first day is the backfill floor (A48). The modal is ADMIN-only; a failed read just means no horizon hint
  // and no client-side horizon check (the server still enforces it).
  const settings = useQuery({ queryKey: ["metrics-settings"], queryFn: getMetricsSettings });
  const horizonFrom = settings.data?.historyHorizonFrom ?? null;
  const backfillContext = { horizonFrom, storedBackfillFrom: target?.backfillFrom };
  // What this server can create (GitLab needs an operator allow-list): until the read answers, GitLab is not offered.
  const kinds = useQuery({ queryKey: ["dataSourceKinds"], queryFn: getDataSourceKinds });
  const gitlabInfo = kinds.data?.items.find((item) => item.kind === "GITLAB");
  const gitlabEnabled = gitlabInfo?.enabled === true;
  const allowedOrigins = gitlabInfo?.allowedOrigins ?? [];
  const form = useForm<DataSourceFormValues>({
    initialValues: target ? fromDataSourceResponse(target) : EMPTY_DATA_SOURCE_FORM,
    validate: dataSourceFormValidation(t, isEdit, backfillContext, allowedOrigins),
  });
  const kind = form.values.kind;

  function selectKind(next: DataSourceKind) {
    form.setValues({ kind: next, ...KIND_RESETS[next] });
    form.clearErrors();
    setTestError(null);
    setTestResult(null);
  }

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
      // A 409 is about ONE control: the name clash, another Jira site (create only) or a project
      // key another data source already holds — told apart by the problem `type`, shown in fixed words.
      if (err instanceof ApiError && err.status === 409) {
        const field = dataSourceConflictField(err);
        form.setFieldError(field, t(CONFLICT_MESSAGE_KEYS[field]));
      } else {
        setError(dataSourceSaveErrorMessage(err, t));
      }
      setSubmitting(false);
    }
  }

  /** Validates only the active kind's connection fields the probe needs — the rest of the form may be incomplete. */
  function connectionFieldsValid(): boolean {
    const rules = dataSourceFormValidation(t, isEdit, backfillContext, allowedOrigins);
    const values = form.values;
    const messages = PROBE_FIELDS[kind].map((field) => {
      // The rule's value type differs per field (string / string[]), so the lookup is by name over the whole values.
      const rule = rules[field] as (value: unknown, all: DataSourceFormValues) => string | null;
      const message = rule(values[field], values);
      form.setFieldError(field, message ?? undefined);
      return message;
    });
    return messages.every((message) => !message);
  }

  async function runTest() {
    setTestError(null);
    if (!connectionFieldsValid()) return;
    setTesting(true);
    setTestResult(null);
    try {
      // Editing with the secret left blank probes the STORED connection (and its decrypted token), either kind.
      const secretBlank = (kind === "GITLAB" ? form.values.token : form.values.apiToken).trim() === "";
      const result = target && secretBlank
        ? await testDataSourceStored(target.id)
        : await testDataSourceAdHoc(
            kind === "GITLAB"
              ? { gitlab: toGitLabConnectionRequest(form.values) }
              : { jira: toJiraConnectionRequest(form.values) },
          );
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
          <DataSourceKindPicker
            value={kind}
            onChange={selectKind}
            disabled={isEdit}
            gitlabEnabled={gitlabEnabled}
            kindsKnown={kinds.isSuccess}
          />
          {kind === "GITLAB" ? (
            <GitLabConnectionFields form={form} isEdit={isEdit} allowedOrigins={allowedOrigins} />
          ) : (
            <JiraConnectionFields form={form} isEdit={isEdit} />
          )}
          <TextInput
            label={t("dataSources.field.backfillFrom")}
            description={
              horizonFrom
                ? `${t("dataSources.field.backfillFromHint")} ${t("dataSources.field.backfillFromHorizonHint", { floor: horizonFrom })}`
                : t("dataSources.field.backfillFromHint")
            }
            placeholder={t("common.dateFormatHint")}
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
          <Switch label={t("dataSources.field.enabled")} {...form.getInputProps("enabled", { type: "checkbox" })} />

          <Button type="button" variant="default" loading={testing} onClick={() => void runTest()}>
            {t("dataSources.testConnection")}
          </Button>
          {testError && (
            <FormErrorAlert>
              {testError}
            </FormErrorAlert>
          )}
          {testResult && <ConnectionTestResults result={testResult} />}

          <RegistryEditorActions error={error} submitting={submitting} isEdit={isEdit} onClose={onClose} gap="sm" />
        </Stack>
      </form>
    </Modal>
  );
}
