import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Group, Paper, PasswordInput, Stack } from "@mantine/core";
import { useForm } from "@mantine/form";
import { changeFlowUserPassword } from "../api/flowUsers";
import { getUserId } from "../api/session";
import { utf8ByteLength } from "../utils/password";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import { MAX_PASSWORD_BYTES, MIN_PASSWORD_LENGTH } from "../utils/flowUserForm";
import PageHeader from "../components/PageHeader";
import { FORM_MAX_WIDTH } from "../utils/layout";
import FormErrorAlert from "../components/FormErrorAlert";

type Values = { currentPassword: string; password: string; confirm: string };

/** Every user's self-service password change (the current password is always required). */
export default function ChangePassword() {
  const { t } = useTranslation();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const form = useForm<Values>({
    initialValues: { currentPassword: "", password: "", confirm: "" },
    validate: {
      currentPassword: (v) => (v ? null : t("flowUsers.validation.currentPasswordRequired")),
      password: (v) => {
        if (v.length < MIN_PASSWORD_LENGTH) return t("flowUsers.validation.passwordLength");
        // The bcrypt ceiling counts BYTES, not characters (multibyte input bites earlier).
        if (utf8ByteLength(v) > MAX_PASSWORD_BYTES) return t("flowUsers.validation.passwordTooLong");
        return null;
      },
      confirm: (v, values) => (v === values.password ? null : t("flowUsers.validation.passwordsMismatch")),
    },
  });

  async function onSubmit(values: Values) {
    const userId = getUserId();
    if (userId === null) return;
    setError(null);
    setSubmitting(true);
    try {
      await changeFlowUserPassword(userId, {
        password: values.password,
        currentPassword: values.currentPassword,
      });
      showSuccessToast(t("flowUsers.toast.passwordChanged"));
      form.reset();
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          forbidden: "flowUsers.wrongCurrentPassword",
          failedStatus: "flowUsers.changeFailedStatus",
          failed: "flowUsers.changeFailedNetwork",
        }),
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Stack gap="md">
      <PageHeader title={t("flowUsers.changePasswordTitle")} />
      <Paper withBorder p="xl" maw={FORM_MAX_WIDTH}>
        <form onSubmit={form.onSubmit(onSubmit)} noValidate>
          <Stack>
            <PasswordInput
              label={t("flowUsers.currentPassword")}
              autoComplete="current-password"
              required
              {...form.getInputProps("currentPassword")}
            />
            <PasswordInput
              label={t("flowUsers.newPassword")}
              autoComplete="new-password"
              required
              {...form.getInputProps("password")}
            />
            <PasswordInput
              label={t("flowUsers.confirmPassword")}
              autoComplete="new-password"
              required
              {...form.getInputProps("confirm")}
            />
            {error && (
              <FormErrorAlert>
                {error}
              </FormErrorAlert>
            )}
            <Group justify="flex-end">
              <Button type="submit" loading={submitting}>
                {t("common.action.save")}
              </Button>
            </Group>
          </Stack>
        </form>
      </Paper>
    </Stack>
  );
}
