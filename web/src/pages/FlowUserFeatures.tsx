import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, Navigate, useNavigate, useParams } from "react-router-dom";
import { Alert, Button, Group, Paper, Stack, Switch, Text } from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/http";
import { FEATURES, type Feature } from "../api/session";
import { useAdmin } from "../auth";
import { getFlowUser, updateFlowUserFeatures } from "../api/flowUsers";
import EditPageLoadState from "../components/EditPageLoadState";
import { showSuccessToast } from "../utils/toast";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import PageHeader from "../components/PageHeader";
import { FORM_MAX_WIDTH } from "../utils/layout";
import { flowUsersPath } from "../utils/flowUserLinks";

/**
 * The per-user feature-flags editor (Lettuce's, ported): one switch per feature
 * (checked = enabled), saved as a wholesale replace via PUT /flow-users/{id}/features.
 * ADMIN-only; reached from the Flow users table's Features button.
 */
export default function FlowUserFeatures() {
  const { t } = useTranslation();
  const admin = useAdmin();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // The admin's unsaved toggles; the effective state derives from the loaded user + these,
  // so no effect-driven seeding is needed. checked = the feature is ENABLED.
  const [overrides, setOverrides] = useState<Partial<Record<Feature, boolean>>>({});

  const idIsValid = Number.isFinite(id) && id > 0;

  const { data, isLoading, isError, error: fetchError } = useQuery({
    queryKey: ["flowUser", id],
    queryFn: () => getFlowUser(id),
    enabled: idIsValid && admin,
    retry: false,
  });

  const enabled: Record<Feature, boolean> | null =
    data == null
      ? null
      : (Object.fromEntries(
          FEATURES.map((f) => [f, overrides[f] ?? !(data.disabledFeatures ?? []).includes(f)]),
        ) as Record<Feature, boolean>);

  if (!idIsValid) return <Navigate to={flowUsersPath} replace />;

  async function onSave() {
    if (enabled == null) return;
    setError(null);
    setSubmitting(true);
    try {
      await updateFlowUserFeatures(id, FEATURES.filter((f) => !enabled[f]));
      await queryClient.invalidateQueries({ queryKey: ["flowUsers"] });
      await queryClient.invalidateQueries({ queryKey: ["flowUser", id] });
      showSuccessToast(t("flowUsers.toast.featuresSaved"));
      navigate(flowUsersPath, { replace: true });
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          notFound: "flowUsers.userNotFound",
          failedStatus: "flowUsers.featuresFailedStatus",
          failed: "flowUsers.featuresFailedNetwork",
        }),
      );
    } finally {
      setSubmitting(false);
    }
  }

  const notFound = isError && fetchError instanceof ApiError && fetchError.status === 404;

  return (
    <Stack gap="md">
      <PageHeader title={t("flowUsers.featuresTitle")} backTo={{ to: flowUsersPath, label: t("flowUsers.title") }} />
      <Paper withBorder p="xl" maw={FORM_MAX_WIDTH}>
        <Stack>
          {isLoading || isError || enabled == null ? (
            <EditPageLoadState
              isLoading={isLoading || (!isError && enabled == null)}
              message={notFound ? t("flowUsers.userNotFound") : loadErrorMessage(fetchError, t)}
              backTo={flowUsersPath}
              backLabel={t("flowUsers.backToUsers")}
            />
          ) : (
            <Stack>
              {data && (
                <Text c="dimmed" size="sm">
                  {t("flowUsers.featuresHint", { name: data.name, email: data.email })}
                </Text>
              )}
              {FEATURES.map((f) => (
                <Switch
                  key={f}
                  label={t(`common.feature.${f}`)}
                  description={t(`common.featureHint.${f}`)}
                  checked={enabled[f]}
                  onChange={(e) => {
                    const value = e.currentTarget.checked;
                    setOverrides((prev) => ({ ...prev, [f]: value }));
                  }}
                />
              ))}
              {error && (
                <Alert color="red" variant="light">
                  {error}
                </Alert>
              )}
              <Group justify="flex-end" gap="sm">
                <Button component={RouterLink} to={flowUsersPath} variant="default">
                  {t("common.action.cancel")}
                </Button>
                <Button onClick={onSave} loading={submitting}>
                  {t("common.action.save")}
                </Button>
              </Group>
            </Stack>
          )}
        </Stack>
      </Paper>
    </Stack>
  );
}
