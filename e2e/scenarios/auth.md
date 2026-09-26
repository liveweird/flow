# Login and logout

- **Spec**: [tests/auth.spec.ts](../tests/auth.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`)
- **Owns** (exclusive server-side state): nothing — read-only (sessions only; no seeded account
  is ever mutated)

## Scenario: signing out from a protected route starts the next login at home

1. The admin signs in through the real login form — email, password, "Sign in".
   - *Expected*: the app shell is up — the **Home** heading and the header account-menu button are
     visible.
2. The admin opens the **Teams** page, then opens the header account menu and clicks **Sign out**
   while the server response is held pending.
   - *Expected*: they are back on the login screen — the **Sign in** button and the
     "You've been signed out." banner are visible.
3. Without reloading the page or clearing browser storage, the admin signs in again through the
   displayed form.
   - *Expected*: the new session starts at the **Home** page. The protected route from
     before the explicit sign-out is not restored.
4. The delayed server response is allowed to finish.
   - *Expected*: the new session remains signed in.

## Scenario: invalid credentials are rejected

1. A visitor submits the login form with the admin's email and a wrong password.
   - *Expected*: the "Invalid email or password" rejection is shown.

## Scenario: a deep link is guarded and lands back after signing in

1. An anonymous visitor opens `/some/deep/path?tab=history#details`.
   - *Expected*: they are bounced to the sign-in form.
2. They sign in with the admin's credentials.
   - *Expected*: the app returns to the exact requested path, query and hash inside the shell — for an unknown path
     that is the **Page not found** page, with the header account-menu button visible (never a blank
     document).

## Not covered here (and why)

- **Login lockout (429)** — repeated failed logins would lock the seeded account in the shared
  database and poison the rest of the run. Covered by `LoginThrottleTest` / `LoginLockoutTest`
  (server).
- **Token refresh / expiry** — needs clock control; covered by server tests and the
  `web/src/api/api.test.ts` unit tests.
