import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, Navigate, useNavigate, useParams } from "react-router-dom";
import { Alert, Button, Group, Paper, Stack } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getUser, setUserLanguage, updateUser } from "../api/users";
import { ApiError } from "../api/http";
import { getUserId } from "../api/session";
import { useAdmin } from "../auth";
import i18n, { asSupportedLanguage } from "../i18n";
import EditPageLoadState from "../components/EditPageLoadState";
import UserFormFields from "../components/UserFormFields";
import { EMPTY_USER_FORM, rolesOf, userFormValidation, type UserFormValues } from "../utils/userForm";
import { isLastAdminConflict, loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import PageHeader from "../components/PageHeader";
import { FORM_MAX_WIDTH } from "../utils/layout";

export default function EditUser() {
  const { t } = useTranslation();
  const admin = useAdmin();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const form = useForm<UserFormValues>({
    initialValues: EMPTY_USER_FORM,
    validate: userFormValidation(t),
  });

  const idIsValid = Number.isFinite(id) && id > 0;

  const { data, isLoading, isError, error: fetchError } = useQuery({
    queryKey: ["user", id],
    queryFn: () => getUser(id),
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

  if (!idIsValid) return <Navigate to="/users" replace />;

  async function onSubmit(values: UserFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      await updateUser(id, {
        name: values.name.trim(),
        email: values.email.trim(),
        roles: rolesOf(values),
      });
      // The language rides its own endpoint (V18) — saved only when actually changed,
      // AFTER the main update so a failed save never half-applies.
      if (data && values.language !== asSupportedLanguage(data.language)) {
        await setUserLanguage(id, values.language);
        // Editing one's own account applies the language immediately; others pick it up
        // at their next sign-in or token refresh.
        if (getUserId() === id) void i18n.changeLanguage(values.language);
      }
      await queryClient.invalidateQueries({ queryKey: ["users"] });
      await queryClient.invalidateQueries({ queryKey: ["user", id] });
      showSuccessToast(t("users.toast.updated"));
      navigate("/users", { replace: true });
    } catch (err) {
      setError(
        isLastAdminConflict(err)
          ? t("users.lastAdminError")
          : saveErrorMessage(err, t, {
              notFound: "users.userGone",
              conflict: "users.emailAlreadyInUse",
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
      <PageHeader title={t("users.editUser")} backTo={{ to: "/users", label: t("users.title") }} />
      <Paper withBorder p="xl" maw={FORM_MAX_WIDTH}>
        <Stack>
          {isLoading || isError ? (
            <EditPageLoadState
              isLoading={isLoading}
              message={notFound ? t("users.userNotFound") : loadErrorMessage(fetchError, t)}
              backTo="/users"
              backLabel={t("users.backToUsers")}
            />
          ) : (
            <form onSubmit={form.onSubmit(onSubmit)} noValidate>
              <Stack>
                <UserFormFields form={form} />
                {error && (
                  <Alert color="red" variant="light">
                    {error}
                  </Alert>
                )}
                <Group justify="flex-end" gap="sm">
                  <Button component={RouterLink} to="/users" variant="default">
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
