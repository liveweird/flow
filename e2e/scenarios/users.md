# User management

- **Spec**: [tests/users.spec.ts](../tests/users.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`); one throwaway user created through
  the UI (email carries the `e2e` marker)
- **Owns** (exclusive server-side state): its throwaway user, deleted at the end; the seed
  admin is never mutated — its row is only read (the own-row assertions)

## Scenario: admin creates a user who signs in, changes their password, and is finally deleted

1. The admin signs in and filters the Users list to their own row.
   - *Expected*: the row carries the **You** badge and offers **Edit** but neither **Delete**
     nor **Reset password** (self-reset needs the current password; self-delete is blocked
     server-side too).
2. The admin creates a throwaway user — no password field anywhere; on create, the one-time
   reveal modal shows the generated 16-character password (masked until **Show password**),
   which the journey captures before deliberately closing the modal.
3. The new user signs in with the revealed password.
   - *Expected*: no **Users** item in the nav, and `/users` bounces to the Home page — the management
     surface is ADMIN-only.
4. They change their own password on the **Change password** page (current + new + confirm).
5. Back as the admin: the throwaway user is promoted to **Administrator** via the edit form
   (the name-filtered list then shows the Admin badge), and afterwards deleted via the row's
   confirm modal.
6. A fresh, filtered load of the list shows no such user, and signing in with the deleted
   account's (changed) password is rejected.

## Not covered here (and why)

- **The last-administrator 409s and the self-delete 403** — pinned exhaustively by the server
  suite (`UserRoutesTest`, with `TestUsers.withSoloAdmins`) and the page unit tests; e2e keeps
  to the happy lifecycle.
- **The admin Reset-password reveal** — unit-tested (`Users.test.tsx`); the create-flow reveal
  covered here exercises the same component.
