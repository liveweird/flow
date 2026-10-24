import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useLocation, useNavigate } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { Alert, Anchor, Button, PasswordInput, Stack, Text } from "@mantine/core";
import { useForm } from "@mantine/form";
import { confirmPasswordReset } from "../api/auth";
import { ApiError } from "../api/http";
import { clearSession } from "../api/session";
import { notifyAuthChange } from "../auth";
import AuthCard from "../components/AuthCard";
import FormErrorAlert from "../components/FormErrorAlert";
import { newPasswordValidation } from "../utils/flowUserForm";
import { saveErrorMessage } from "../utils/saveError";

/** The server's token shape (32 random bytes, base64url): anything else cannot be a link we sent. */
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/;

/**
 * The page behind the emailed reset link (`/reset-password/confirm#token=…`). The token lives in the URL FRAGMENT (never
 * in a server log or a Referer), is read once into component state and stripped from the address bar and history;
 * it is sent to nothing but the confirm POST. Loading the page consumes nothing; the form is shown for any well-formed
 * token and an unusable one is learned at submit (401). Not behind `RedirectIfAuthed`: on success the page clears this
 * device's local session itself (the reset ended every session of the account) and sets no return destination.
 */
export default function ConfirmPasswordReset() {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [token, setToken] = useState(() => new URLSearchParams(location.hash.slice(1)).get("token") ?? "");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [completed, setCompleted] = useState(false);
  const form = useForm({
    initialValues: { password: "", confirm: "" },
    validate: newPasswordValidation(t),
  });

  useEffect(() => {
    if (location.hash || location.search) {
      navigate({ pathname: location.pathname, search: "", hash: "" }, { replace: true });
    }
  }, [location.hash, location.search, location.pathname, navigate]);

  async function onSubmit(values: { password: string }) {
    setError(null);
    setSubmitting(true);
    try {
      await confirmPasswordReset(token, values.password);
      setToken("");
      form.reset();
      // The reset bumped the credential revision: every session of the account, this device's included, is over.
      // No /logout call - nothing is left to revoke.
      clearSession();
      queryClient.clear();
      setCompleted(true);
      notifyAuthChange();
    } catch (err) {
      // A 401 means this link is spent for good; every other failure keeps it so the user can retry.
      if (err instanceof ApiError && err.status === 401) setToken("");
      setError(
        saveErrorMessage(err, t, {
          unauthorized: "auth.resetLinkInvalid",
          invalid: "flowUsers.validation.passwordLength",
          tooManyRequests: "auth.resetConfirmThrottled",
          failed: "auth.resetFailedGeneric",
        }),
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthCard title={t("auth.resetChooseTitle")}>
      {completed ? (
        <Alert color="teal" variant="light">
          {t("auth.resetCompleted")}
        </Alert>
      ) : TOKEN_PATTERN.test(token) ? (
        <form onSubmit={form.onSubmit(onSubmit)} noValidate>
          <Stack>
            <Text size="sm" c="dimmed">
              {t("auth.resetChooseIntro")}
            </Text>
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
            {error && <FormErrorAlert>{error}</FormErrorAlert>}
            <Button type="submit" loading={submitting} fullWidth>
              {t("auth.resetConfirmSubmit")}
            </Button>
          </Stack>
        </form>
      ) : (
        <Stack>
          <FormErrorAlert>{t("auth.resetLinkInvalid")}</FormErrorAlert>
          <Anchor component={RouterLink} to="/reset-password">
            {t("auth.resetRequestAnother")}
          </Anchor>
        </Stack>
      )}
      <Anchor component={RouterLink} to="/login" size="sm" ta="center">
        {t("auth.backToSignIn")}
      </Anchor>
    </AuthCard>
  );
}
