# Data sources (the v0.2.0 Jira ingestion admin surface)

- **Spec**: [tests/data-sources.spec.ts](../tests/data-sources.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`); one throwaway user in the second test
- **Owns** (exclusive server-side state): its throwaway data source (unique `e2e-jira-*` name),
  deleted at the end of the first test (the cleanup hook removes it through the API if a step fails first); its throwaway user in the second test. The synced
  connection's `raw.*`/`norm.*` rows are NOT deleted with it (see "Not covered here" below) — the
  compose stack's Postgres volume accumulates ~1,200 raw issues per run until the grace period
  purges them.

## Scenario: admin connects the Jira stub, syncs it, and inspects the result

1. The admin signs in and opens **New data source** from the Data sources list.
2. They fill a unique name, the site URL `https://flow-e2e.atlassian.net` (any valid
   `*.atlassian.net` tenant works — the compose stack reroutes every Jira call to the `jira-stub`
   service via `JIRA_STUB_BASE_URL`, so no real Atlassian tenant is ever reached), a
   service-account email, an API token, and the four in-scope project keys (`FLO`, `PLT`, `GTM`,
   `OPS`) into the project-keys tag input, leaving the backfill date blank.
3. They click **Test connection**.
   - *Expected*: every REQUIRED probe row reports **OK**, and the resolved Cloud ID renders below
     the table (`sample-data/README.md`'s fixed `cloudId`).
4. They click **Create**.
   - *Expected*: the modal closes; the new connection appears in the filtered list.
5. They open the new data source's details page and click **Sync now**.
   - *Expected*: eventually (the page's own auto-refresh, or a reload) the most recent sync job's
     status reads **Succeeded** and the raw-store counts show **Raw issues: 1200** — the full
     in-scope total across the four projects (`sample-data/jira/expected.json`
     `issues.totalInScope`).
6. They open the **Data profile** page.
   - *Expected*: the Projects and Workflows sections render, and project `FLO` is listed.
7. They open the **Raw issue inspector** and look up `FLO-1`.
   - *Expected*: the issue's work-item summary and raw payload render under its key.
8. They look up a malformed key (`not a valid key!!`).
   - *Expected*: an inline "Enter a valid issue key…" error renders; the key field and **Look
     up** button stay usable (no page-level error state).
9. Back on the Data sources list, they delete the connection from its row menu.
   - *Expected*: the row is gone.

## Scenario: a regular user sees no Data sources surface

1. The admin creates a throwaway user and signs out.
2. The user signs in.
   - *Expected*: there is no **Data sources** nav link, and visiting `/data-sources` directly
     redirects to Home (the `RequireAdmin` route guard).
3. The admin signs back in and deletes the throwaway user (the cleanup hook also removes it through the API if a step
   failed first).

## Not covered here (and why)

- **Validation and conflicts** (a malformed site URL, a duplicate name, a fixed site URL on
  update, `FORBIDDEN_SCOPE`/`AUTHENTICATION_FAILED` test-connection rows) — pinned by the
  server's `DataSourceRoutesTest`/`DataSourceTestConnectionTest` and the SPA's
  `DataSourceEditorModal.test.tsx`; the journey drives the happy path only.
- **Sync-job history filtering, cancellation, Reconcile/Reprocess** — pinned by
  `SyncJobRoutesTest` (server) and the details page's own component tests; the journey only
  drives the one manual "Sync now" every connection needs before anything else is worth reading.
- **Purge after soft-delete.** Deleting a connection here only disables it — its `raw.*`/`norm.*`
  rows purge asynchronously, `ingest.purgeGraceDays` (default 7) after the delete
  (`.claude/docs/ingestion.md` "Scheduling"). This spec's own delete therefore leaves ~1,200 raw
  issues behind in the compose stack's Postgres volume for the rest of that grace period — by
  design, not a leak this spec can or should clean up itself.
