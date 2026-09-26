import { useState } from "react";
import { useTranslation } from "react-i18next";
import { changeUserPassword, type UserResponse } from "../api/users";
import { generatePassword } from "../utils/password";
import { saveErrorMessage } from "../utils/saveError";

/**
 * The admin reset-password flow: confirm → generate client-side → PUT → the one-time reveal
 * (OneTimePasswordModal). Owns the target/pending/error/revealed state; the page renders the
 * confirm modal and the reveal modal from it.
 */
export function useResetPassword() {
  const { t } = useTranslation();
  const [target, setTarget] = useState<UserResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [revealed, setRevealed] = useState<{ email: string; password: string } | null>(null);

  function request(user: UserResponse) {
    setError(null);
    setTarget(user);
  }

  function clearTarget() {
    setTarget(null);
  }

  async function confirm() {
    if (!target) return;
    setError(null);
    setPending(true);
    const password = generatePassword();
    try {
      await changeUserPassword(target.id, { password });
      setRevealed({ email: target.email, password });
      setTarget(null);
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          failedStatus: "users.resetFailedStatus",
          failed: "users.resetFailedNetwork",
        }),
      );
    } finally {
      setPending(false);
    }
  }

  return {
    target,
    error,
    pending,
    revealed,
    request,
    clearTarget,
    confirm,
    closeReveal: () => setRevealed(null),
  };
}
