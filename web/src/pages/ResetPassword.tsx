import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Anchor, Button, Stack, Text, TextInput } from "@mantine/core";
import { isEmail, useForm } from "@mantine/form";
import { requestPasswordReset } from "../api/auth";
import AuthCard from "../components/AuthCard";
import { MAX_EMAIL_LENGTH } from "../utils/userForm";
import { saveErrorMessage } from "../utils/saveError";

export default function ResetPassword() {
  const { t } = useTranslation();
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [sent, setSent] = useState(false);

  const form = useForm({
    initialValues: { email: "" },
    validate: { email: isEmail(t("auth.invalidEmail")) },
  });

  async function onSubmit(values: { email: string }) {
    setError(null);
    setSubmitting(true);
    try {
      await requestPasswordReset(values.email);
      setSent(true);
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          tooManyRequests: "auth.resetThrottled",
          unavailable: "auth.resetUnavailable",
          failed: "auth.resetFailedGeneric",
        }),
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthCard title={t("auth.resetTitle")}>
      {sent ? (
        // Always the same neutral confirmation — whether or not the account exists is
        // deliberately unobservable (mirrors the server's uniform 202).
        <Alert color="teal" variant="light">
          {t("auth.resetConfirmation")}
        </Alert>
      ) : (
        <form onSubmit={form.onSubmit(onSubmit)} noValidate>
          <Stack>
            <Text size="sm" c="dimmed">
              {t("auth.resetIntro")}
            </Text>
            <TextInput
              label={t("common.field.email")}
              type="email"
              autoFocus
              autoComplete="email"
              maxLength={MAX_EMAIL_LENGTH}
              {...form.getInputProps("email")}
            />
            {error && (
              <Alert color="red" variant="light">
                {error}
              </Alert>
            )}
            <Button type="submit" loading={submitting} fullWidth>
              {t("auth.resetSubmit")}
            </Button>
          </Stack>
        </form>
      )}
      <Anchor component={RouterLink} to="/login" size="sm" ta="center">
        {t("auth.backToSignIn")}
      </Anchor>
    </AuthCard>
  );
}
