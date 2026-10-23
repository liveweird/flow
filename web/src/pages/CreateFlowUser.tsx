import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useNavigate } from "react-router-dom";
import { Button, Group, Paper, Stack } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQueryClient } from "@tanstack/react-query";
import { IconMail } from "@tabler/icons-react";
import { createFlowUser } from "../api/flowUsers";
import OneTimePasswordModal from "../components/OneTimePasswordModal";
import FlowUserFormFields from "../components/FlowUserFormFields";
import { generatePassword } from "../utils/password";
import { EMPTY_FLOW_USER_FORM, rolesOf, flowUserFormValidation, type FlowUserFormValues } from "../utils/flowUserForm";
import { saveErrorMessage } from "../utils/saveError";
import PageHeader from "../components/PageHeader";
import { FORM_MAX_WIDTH } from "../utils/layout";
import { flowUsersPath } from "../utils/flowUserLinks";
import FormErrorAlert from "../components/FormErrorAlert";

export default function CreateFlowUser() {
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

  const form = useForm<FlowUserFormValues>({
    initialValues: EMPTY_FLOW_USER_FORM,
    validate: flowUserFormValidation(t),
  });


  async function onSubmit(values: FlowUserFormValues) {
    setError(null);
    setSubmitting(true);
    const password = generatePassword();
    try {
      await createFlowUser({
        name: values.name.trim(),
        email: values.email.trim(),
        password,
        roles: rolesOf(values),
        language: values.language,
      });
      await queryClient.invalidateQueries({ queryKey: ["flowUsers"] });
      setCreated({ name: values.name.trim(), email: values.email.trim().toLowerCase(), password });
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          conflict: "flowUsers.emailAlreadyInUse",
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
    navigate(flowUsersPath, { replace: true });
  }

  // A client-side mail draft for handing the credentials over (no mail infra involved);
  // RFC 6068 mandates CRLF line breaks in mailto bodies.
  const mailtoHref = created
    ? `mailto:${encodeURIComponent(created.email).replace(/%40/g, "@")}` +
      `?subject=${encodeURIComponent(t("flowUsers.onboardingEmailSubject"))}` +
      `&body=${encodeURIComponent(
        t("flowUsers.onboardingEmailBody", {
          name: created.name,
          url: window.location.origin,
          password: created.password,
        }).replace(/\n/g, "\r\n"),
      )}`
    : undefined;

  return (
    <Stack gap="md">
      <PageHeader title={t("flowUsers.createUser")} backTo={{ to: flowUsersPath, label: t("flowUsers.title") }} />
      <Paper withBorder p="xl" maw={FORM_MAX_WIDTH}>
        <form onSubmit={form.onSubmit(onSubmit)} noValidate>
          <Stack>
            {/* No password field: it is generated on submit and revealed exactly once. */}
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
                {t("common.action.create")}
              </Button>
            </Group>
          </Stack>
        </form>
      </Paper>

      <OneTimePasswordModal
        reveal={created}
        title={t("flowUsers.createdTitle")}
        onClose={closeConfirmation}
        secondaryAction={
          <Button component="a" href={mailtoHref} variant="light" leftSection={<IconMail size={16} />}>
            {t("flowUsers.composeOnboardingEmail")}
          </Button>
        }
      />
    </Stack>
  );
}
