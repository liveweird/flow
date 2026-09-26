import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useNavigate } from "react-router-dom";
import { Alert, Button, Group, Paper, Stack } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQueryClient } from "@tanstack/react-query";
import { IconMail } from "@tabler/icons-react";
import { createUser } from "../api/users";
import OneTimePasswordModal from "../components/OneTimePasswordModal";
import UserFormFields from "../components/UserFormFields";
import { generatePassword } from "../utils/password";
import { EMPTY_USER_FORM, rolesOf, userFormValidation, type UserFormValues } from "../utils/userForm";
import { saveErrorMessage } from "../utils/saveError";
import PageHeader from "../components/PageHeader";
import { FORM_MAX_WIDTH } from "../utils/layout";

export default function CreateUser() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // Set on successful creation; the confirmation modal is the ONLY place this password ever
  // appears (the server stores just a bcrypt hash), so closing the modal discards it for good.
  const [created, setCreated] = useState<{ name: string; email: string; password: string } | null>(
    null,
  );

  const form = useForm<UserFormValues>({
    initialValues: EMPTY_USER_FORM,
    validate: userFormValidation(t),
  });


  async function onSubmit(values: UserFormValues) {
    setError(null);
    setSubmitting(true);
    const password = generatePassword();
    try {
      await createUser({
        name: values.name.trim(),
        email: values.email.trim(),
        password,
        roles: rolesOf(values),
        language: values.language,
      });
      await queryClient.invalidateQueries({ queryKey: ["users"] });
      setCreated({ name: values.name.trim(), email: values.email.trim().toLowerCase(), password });
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          conflict: "users.emailAlreadyInUse",
          failedStatus: "common.error.createFailedStatus",
          failed: "common.error.createFailedNetwork",
        }),
      );
    } finally {
      setSubmitting(false);
    }
  }

  function closeConfirmation() {
    setCreated(null); // the plaintext is dropped here, for good
    navigate("/users", { replace: true });
  }

  // A client-side mail draft for handing the credentials over (no mail infra involved);
  // RFC 6068 mandates CRLF line breaks in mailto bodies.
  const mailtoHref = created
    ? `mailto:${encodeURIComponent(created.email).replace(/%40/g, "@")}` +
      `?subject=${encodeURIComponent(t("users.onboardingEmailSubject"))}` +
      `&body=${encodeURIComponent(
        t("users.onboardingEmailBody", {
          name: created.name,
          url: window.location.origin,
          password: created.password,
        }).replace(/\n/g, "\r\n"),
      )}`
    : undefined;

  return (
    <Stack gap="md">
      <PageHeader title={t("users.createUser")} backTo={{ to: "/users", label: t("users.title") }} />
      <Paper withBorder p="xl" maw={FORM_MAX_WIDTH}>
        <form onSubmit={form.onSubmit(onSubmit)} noValidate>
          <Stack>
            {/* No password field: it is generated on submit and revealed exactly once. */}
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
                {t("common.action.create")}
              </Button>
            </Group>
          </Stack>
        </form>
      </Paper>

      <OneTimePasswordModal
        reveal={created}
        title={t("users.createdTitle")}
        onClose={closeConfirmation}
        secondaryAction={
          <Button component="a" href={mailtoHref} variant="light" leftSection={<IconMail size={16} />}>
            {t("users.composeOnboardingEmail")}
          </Button>
        }
      />
    </Stack>
  );
}
