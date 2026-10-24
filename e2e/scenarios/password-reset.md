# Self-service password reset

- **Spec**: [tests/password-reset.spec.ts](../tests/password-reset.spec.ts)
- **Actors**: an anonymous visitor on the reset form; the seed administrator
  (`admin@flow.local`) only to create/delete the throwaway account
- **Owns** (exclusive server-side state): one throwaway user account (email carries the
  `e2e` marker), deleted at the end. The reset requests use unique per-run emails, so the
  in-memory per-email throttle never collides across runs; the per-IP request bucket (5/min in
  production, lifted on dev stacks like the login bucket) and the per-IP confirm bucket (10/min) are
  never the subject, and the two tests together stay far under both. Delivered messages remain in
  the Mailpit catcher — that is what a mail catcher is for.

## Scenario: the forgot-password link leads to the reset form; unknown emails get the neutral answer

1. A visitor on the login page follows the **Forgot password?** link.
   - *Expected*: the `/reset-password` form renders (lazy route) with a **Send reset link** button.
2. They submit a unique unknown email address.
   - *Expected*: the neutral confirmation ("if an account with this address exists…") —
     account existence is unobservable.
3. They submit another unique address twice in a row.
   - *Expected*: the first answer is the same neutral confirmation; the second shows the
     neutral temporary-limit message — uniformly, even though the account does not exist.
     The server enforces one request per minute for that submitted address.

## Scenario: a reset link preserves the old password until confirmation and cannot be reused

*Skips itself when Mailpit (`E2E_MAILPIT_URL`, default `http://localhost:8028`, the compose stack's catcher) is
unreachable — the email roundtrip cannot be observed on a log-transport dev stack.*

1. The admin creates a throwaway user through the real UI (capturing the one-time revealed
   password) and signs out.
2. A visitor submits the throwaway user's email on the reset form.
   - *Expected*: the neutral confirmation; asynchronously, a "Reset your Flow password"
     email lands in Mailpit, saying "Your password is unchanged" and carrying one
     `/reset-password/confirm#token=…` link (a 43-character token in the fragment). The spec
     follows the link by its path and fragment, relative to the stack under test, so a
     `MAIL_APP_URL` naming another origin does not matter.
3. They sign in through the real form with the ORIGINAL password, then open the link while signed in.
   - *Expected*: the original password still works (requesting a reset changed nothing); the
     **Set new password** button shows and the address bar has lost the fragment; the session's bearer
     token still answers (`403` on the Flow users list, a regular user — not `401`).
4. They enter a chosen password and its confirmation and submit.
   - *Expected*: "Your password has been reset"; the earlier bearer token now answers `401`
     (the reset ended every session).
5. They sign in through the form with the chosen password, sign out, and try the ORIGINAL password.
   - *Expected*: the chosen password opens the session; the original gives "Invalid email or password".
6. They reopen the same link and submit a password again.
   - *Expected*: "This reset link is invalid…" with a **Request another reset link** link — the link
     works once.
7. Whatever happened above, the admin deletes the throwaway account.

## Not covered here (and why)

- **The 503 on mail-less deployments, malformed-email 400s, the 15-minute expiry, a superseded or
  email-changed grant, audit events, a failed delivery revoking its grant** — server-tested
  (`PasswordResetTest`, `PasswordResetConfirmationTest`, `PasswordResetServiceTest`); the SPA's
  per-status messages and the fragment handling are unit-tested (`ResetPassword.test.tsx`,
  `ConfirmPasswordReset.test.tsx`).
- **The per-IP request (5/min) and confirm (10/min) buckets** — lifted or far from reach on dev
  stacks; exercising the production limits from one host would poison the rest of the run (the
  login-lockout precedent).
