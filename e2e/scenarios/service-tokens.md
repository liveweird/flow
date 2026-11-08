# Service tokens (Administration > Service tokens, and the token-only integration reads)

- **Spec**: [tests/service-tokens.spec.ts](../tests/service-tokens.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`) through the browser; a second, API-only actor, "another application", that
  presents the new token as a bearer value and never signs in
- **Owns** (exclusive server-side state): its `e2e-token-*` service-token rows (a unique name per run). A token is never deleted
  (revoking is terminal and the registry has no DELETE), so the test revokes its token itself, and a `finally` revokes that
  one token by id (taken from the create response) if the test failed first, never a sweep by name; the unique name keeps re-runs clear of the live-name unique index. The secret is never typed,
  logged or asserted on by value: it is read from the one-time reveal panel into a variable that only feeds the `Authorization` header

## Scenario: admin creates a service token, reads Flow's teams with it, and revokes it

1. The admin signs in and clicks **Service tokens** in the Administration section of the sidebar.
   - *Expected*: the page opens at `/service-tokens` with its title and the hint that the secret is shown once.
2. They type a unique `e2e-token-…` name (the **Expires** choice stays **Never**) and click **Create token**.
   - *Expected*: a panel "Token created: <name>" with the note "Copy it now, it is never shown again."
3. They press the eye toggle in the panel.
   - *Expected*: the revealed value starts with `flow_int_` (asserted as a boolean, never by value).
4. They look at the token's row in the table.
   - *Expected*: the status **Active**; **Expires** and **Last used** both read **Never**.
5. "Another application" calls `GET /api/v1/integration/me` and `GET /api/v1/integration/teams` with the value as its bearer token.
   - *Expected*: `me` answers `200` with the token's name; `teams` answers `200` with an `items` list.
6. The admin clicks the row's **Revoke the token <name>** button and confirms **Revoke** in the dialog "Revoke this token?".
   - *Expected*: the toast "Service token revoked"; the row now reads **Revoked** and has no button at all; the reveal panel is gone.
7. "Another application" calls `GET /api/v1/integration/me` with the same token again.
   - *Expected*: `401` with `Content-Type: application/problem+json`.

## Not covered here (and why)

- The expiry choices (90 days, 1 year, 2 years), the **Expired** status, the "Just now"/age rendering of **Last used**, the name-clash `409`
  message, the refused revoke and the non-admin session are unit-tested in `web/src/pages/ServiceTokens.test.tsx` and
  `web/src/utils/serviceTokens.test.ts`; the server rules (hashing, expiry, uniform `401`, the surface allow-list, the rate buckets, the audit
  events) by the server suite (`ServiceToken*Test`).
- The Teams document's contents (members, sprints, capacity): `IntegrationTeamsReadTest`; the e2e only proves the token reaches it.
