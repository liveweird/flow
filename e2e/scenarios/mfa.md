# Email MFA and the feature-flags surfaces

- **Spec**: [tests/mfa.spec.ts](../tests/mfa.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`) curating flags; a throwaway user
  signing in with the second factor
- **Owns** (exclusive server-side state): one throwaway user account per test (emails carry
  the `e2e` marker), deleted at the end. Flag toggles touch ONLY the throwaway users' rows —
  **the seed admin's MFA flag is never touched** (enabling it would make every other spec's
  login demand an emailed code). Delivered messages remain in the Mailpit catcher.

## Scenario: admin toggles a user's MFA on the feature-flags screen and the per-user editor

1. The admin creates a throwaway user through the real UI.
2. On **/feature-flags**, filtered to the throwaway user's email, the Email MFA switch
   starts OFF.
   - *Expected*: the inverted default — every new user begins with the MFA-disabled row.
3. They flip the row switch on.
   - *Expected*: the wholesale features PUT fires and the saved toast appears.
4. On the per-user editor (`/users/:id/features`, the Users table's Features button), the
   switch shows ON; they turn it off and save.
   - *Expected*: back on `/users` — the flag round-trips through both admin surfaces.
5. They delete the throwaway user.

## Scenario: an MFA-enabled account signs in with the emailed code

*Skips itself when Mailpit (`http://localhost:8028`) is unreachable.*

1. The admin creates a throwaway user, waits for **Show password** to become **Hide password** and
   captures the non-masked generated password, enables their MFA in the per-user editor, and
   signs out.
2. The user submits correct credentials on the login form.
   - *Expected*: the card switches to the sign-in-code step (no session yet); a
     "Flow: your sign-in code" email with a 6-digit code lands in Mailpit.
3. They type the code into the PIN inputs and verify.
   - *Expected*: the session opens — the authenticated shell renders.
4. The admin deletes the throwaway account.

## Not covered here (and why)

- **The uniform-401 failure matrix (wrong/expired code, attempt cap), the mail-less 503,
  challenge single-use, the audit events** — server-tested (`MfaLoginTest`,
  `MfaChallengesTest`); the SPA's per-status messages are unit-tested (`Login.test.tsx`).
- **Bulk enable/disable on /feature-flags** — acting on every filtered row is unsafe against
  the shared user table (it could catch other specs' throwaway rows mid-run); unit-tested
  (`FeatureFlags.test.tsx`) and server-backed by the same per-user PUT.
