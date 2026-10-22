import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, Navigate, useNavigate, useParams } from "react-router-dom";
import { Button, Group, Paper, Stack } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getFlowUser, setFlowUserLanguage, updateFlowUser } from "../api/flowUsers";
import { ApiError } from "../api/http";
import { getUserId } from "../api/session";
import { useAdmin } from "../auth";
import i18n, { asSupportedLanguage } from "../i18n";
import EditPageLoadState from "../components/EditPageLoadState";
import FlowUserFormFields from "../components/FlowUserFormFields";
import { EMPTY_FLOW_USER_FORM, rolesOf, flowUserFormValidation, type FlowUserFormValues } from "../utils/flowUserForm";
import { isLastAdminConflict, loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import PageHeader from "../components/PageHeader";
import { FORM_MAX_WIDTH } from "../utils/layout";
import { flowUsersPath } from "../utils/flowUserLinks";
import FormErrorAlert from "../components/FormErrorAlert";

export default function EditFlowUser() {
  const { t } = useTranslation();
  const admin = useAdmin();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const form = useForm<FlowUserFormValues>({
    initialValues: EMPTY_FLOW_USER_FORM,
    validate: flowUserFormValidation(t),
  });

  const idIsValid = Number.isFinite(id) && id > 0;

  const { data, isLoading, isError, error: fetchError } = useQuery({
    queryKey: ["flowUser", id],
    queryFn: () => getFlowUser(id),
    enabled: idIsValid && admin,
    retry: false,
  });

  // Derived, not effect-set: initialize applies once (the guarded-initialize idiom).
  if (data && !form.initialized) {
    form.initialize({
      name: data.name,
      email: data.email,
      admin: data.roles.includes("ADMIN"),
      language: asSupportedLanguage(data.language),
    });
  }

  if (!idIsValid) return <Navigate to={flowUsersPath} replace />;

  async function onSubmit(values: FlowUserFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      await updateFlowUser(id, {
        name: values.name.trim(),
        email: values.email.trim(),
        roles: rolesOf(values),
      });
      // The language rides its own endpoint (V18) — saved only when actually changed,
      // AFTER the main update so a failed save never half-applies.
      if (data && values.language !== asSupportedLanguage(data.language)) {
        await setFlowUserLanguage(id, values.language);
        // Editing one's own account applies the language immediately; others pick it up
        // at their next sign-in or token refresh.
        if (getUserId() === id) void i18n.changeLanguage(values.language);
      }
      await queryClient.invalidateQueries({ queryKey: ["flowUsers"] });
      await queryClient.invalidateQueries({ queryKey: ["flowUser", id] });
      showSuccessToast(t("flowUsers.toast.updated"));
      navigate(flowUsersPath, { replace: true });
    } catch (err) {
      setError(
        isLastAdminConflict(err)
          ? t("flowUsers.lastAdminError")
          : saveErrorMessage(err, t, {
              notFound: "flowUsers.userGone",
              conflict: "flowUsers.emailAlreadyInUse",
              failedStatus: "common.error.saveFailedStatus",
              failed: "common.error.saveFailedNetwork",
            }),
      );
    } finally {
      setSubmitting(false);
    }
  }

  const notFound = isError && fetchError instanceof ApiError && fetchError.status === 404;

  return (
    <Stack gap="md">
      <PageHeader title={t("flowUsers.editUser")} backTo={{ to: flowUsersPath, label: t("flowUsers.title") }} />
      <Paper withBorder p="xl" maw={FORM_MAX_WIDTH}>
        <Stack>
          {isLoading || isError ? (
            <EditPageLoadState
              isLoading={isLoading}
              message={notFound ? t("flowUsers.userNotFound") : loadErrorMessage(fetchError, t)}
              backTo={flowUsersPath}
              backLabel={t("flowUsers.backToUsers")}
            />
          ) : (
            <form onSubmit={form.onSubmit(onSubmit)} noValidate>
              <Stack>
                <FlowUserFormFields form={form} />
                {error && (
                  <FormErrorAlert>
                    {error}
                  </FormErrorAlert>
                )}
                <Group justify="flex-end" gap="sm">
                  <Button component={RouterLink} to={flowUsersPath} variant="default">
                    {t("common.action.cancel")}
                  </Button>
                  <Button type="submit" loading={submitting}>
                    {t("common.action.save")}
                  </Button>
                </Group>
              </Stack>
            </form>
          )}
        </Stack>
      </Paper>
    </Stack>
  );
}
