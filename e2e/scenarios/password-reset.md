# Self-service password reset

- **Spec**: [tests/password-reset.spec.ts](../tests/password-reset.spec.ts)
- **Actors**: an anonymous visitor on the reset form; the seed administrator
  (`admin@flow.local`) only to create/delete the throwaway account
- **Owns** (exclusive server-side state): one throwaway user account (email carries the
  `e2e` marker), deleted at the end. The reset requests use unique per-run emails, so the
  in-memory per-email throttle never collides across runs; the dev stack lifts the per-IP
  reset bucket (100/min, the login-bucket idiom), so back-to-back runs never trip it. Delivered messages remain in the Mailpit catcher —
  that is what a mail catcher is for.

## Scenario: the forgot-password link leads to the reset form; unknown emails get the neutral answer

1. A visitor on the login page follows the **Forgot password?** link.
   - *Expected*: the `/reset-password` form renders (lazy route).
2. They submit a unique unknown email address.
   - *Expected*: the neutral confirmation ("if an account with this address exists…") —
     account existence is unobservable.
3. They submit another unique address twice in a row.
   - *Expected*: the first answer is the same neutral confirmation; the second shows the
     neutral temporary-limit message — uniformly, even though the account does not exist.
     The server enforces one request per minute for that submitted address.

## Scenario: a reset email delivers a working new password and kills the old one

*Skips itself when Mailpit (`http://localhost:8028`, the compose stack's catcher) is
unreachable — the email roundtrip cannot be observed on a log-transport dev stack.*

1. The admin creates a throwaway user through the real UI (capturing the one-time revealed
   password) and signs out.
2. A visitor submits the throwaway user's email on the reset form.
   - *Expected*: the neutral confirmation; asynchronously, a "Your new Flow password"
     email lands in Mailpit carrying a 16-character generated password on its own line.
3. They sign in with the emailed password.
   - *Expected*: the session opens — the emailed password works.
4. They sign out and try the ORIGINAL (creation-time) password.
   - *Expected*: "Invalid email or password" — the reset killed the old password.
5. The admin deletes the throwaway account.

## Not covered here (and why)

- **The 503 on mail-less deployments, malformed-email 400s, audit events, send-before-store
  on delivery failure** — server-tested (`PasswordResetTest`); the SPA's per-status messages
  are unit-tested (`ResetPassword.test.tsx`).
- **The per-IP reset bucket** — lifted on dev stacks; exercising the production 5/min from
  one host would poison the rest of the run (the login-lockout precedent).
