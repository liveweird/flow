import { useState } from "react";
import { useTranslation } from "react-i18next";
import { changeFlowUserPassword, type FlowUserResponse } from "../api/flowUsers";
import { generatePassword } from "../utils/password";
import { saveErrorMessage } from "../utils/saveError";

/**
 * The admin reset-password flow: confirm → generate client-side → PUT → the one-time reveal
 * (OneTimePasswordModal). Owns the target/pending/error/revealed state; the page renders the
 * confirm modal and the reveal modal from it.
 */
export function useResetPassword() {
  const { t } = useTranslation();
  const [target, setTarget] = useState<FlowUserResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [revealed, setRevealed] = useState<{ email: string; password: string } | null>(null);

  function request(user: FlowUserResponse) {
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
      await changeFlowUserPassword(target.id, { password });
      setRevealed({ email: target.email, password });
      setTarget(null);
    } catch (err) {
      setError(
        saveErrorMessage(err, t, {
          failedStatus: "flowUsers.resetFailedStatus",
          failed: "flowUsers.resetFailedNetwork",
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
