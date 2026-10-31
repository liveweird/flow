# Data sources (the Jira ingestion admin surface, and the GitLab kind since 1.34.0)

- **Spec**: [tests/data-sources.spec.ts](../tests/data-sources.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`); one throwaway user in the second test
- **Owns** (exclusive server-side state): its throwaway data sources (unique `e2e-jira-*` and `e2e-gitlab-*` names),
  each deleted at the end of its test (the cleanup hook removes it through the API if a step fails first); its throwaway user in the last test. The synced
  connection's `raw.*`/`norm.*` rows are NOT deleted with it (see "Not covered here" below) — the
  compose stack's Postgres volume accumulates ~1,200 raw issues per run until the grace period
  purges them.

The file holds the Playwright lock `REPORTS_EXCLUSIVE_LOCKS.dataSources` (its first test syncs and derives a live connection), which `reports.spec.ts` also holds:
the two never run at the same time, whatever the dispatch order.

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
     status reads **Succeeded** and the raw-store counts show **Raw issues: 1201** — the full
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

## Scenario: admin connects the GitLab stub, syncs it, and reads back its projects, users, commits and merge requests

1. The admin signs in, opens **New data source**, fills a unique name and picks **GitLab** in the **Kind** control.
   - *Expected*: the form swaps to the GitLab fields (Base URL, Access token, Group paths, **Excluded paths**); the
     option is enabled because the compose stack sets `GITLAB_STUB_BASE_URL`. **Excluded paths** is prefilled with the
     server's built-in defaults from the kinds read (the pill `package-lock.json` is present).
2. They enter the base URL `http://gitlab-stub:8080` (the stub's own origin is the only one the server accepts
   while `GITLAB_ALLOWED_HOSTS` is blank), an access token (any non-empty value works against the stub) and the
   group paths `platform` and `apps`.
3. They click **Test connection**.
   - *Expected*: every REQUIRED probe row reports **OK** and no Cloud ID is shown (it is a Jira-only figure).
4. They click **Create**.
   - *Expected*: the modal closes; the new connection appears in the filtered list.
5. They open the new data source's details page.
   - *Expected*: the connection table names the kind **GitLab**, the groups `platform, apps` and the **Excluded paths**
     (the defaults, including `package-lock.json`); there is no **Data
     profile**, **Raw issue inspector** or **Metrics configuration** link and no **Reprocess** button (they are Jira-only;
     the server answers `409` for them on a GitLab source).
6. They click **Sync now**.
   - *Expected*: eventually (a reload each attempt) a **Sync** job row reads **Succeeded** and the raw-store counts show
     **Projects: 4** and **Users: 8** - the stub's in-scope projects (`sample-data/gitlab/expected.json`
     `projects.inScopeIds`) and its users (`users.list`) - plus **Commits: 300**, **Merge commits: 19** and
     **Commits awaiting file details: 0** (`commits.inScopeTotals`; one sync drains the stub's 281 non-merge diffs
     under the default per-run budget), plus the merge requests: **Merge requests: 60** (`mergeRequests.total`),
     **Merged merge requests: 34** (`mergeRequests.byState.merged`), **MR events: 698** (`events.noteTotal` 582 +
     `approvalsEndpointTotal` 40 + `reviewersTotal` 76), **Merge requests awaiting details: 0** (one sync drains the
     details under the default per-run budget) and **Merge requests without size statistics: 1**
     (`mergeRequests.nullDiffStatsGap`, the one merge request whose GraphQL answer carries no size).
7. Back on the Data sources list, they delete the connection from its row menu.
   - *Expected*: the row is gone.

## Scenario: admin cannot move a backfill date before the history horizon

1. The admin signs in and opens **New data source**.
   - *Expected*: the **Backfill from** hint names the history horizon's first day ("The history
     horizon allows dates from YYYY-MM-DD on; blank means 24 months back, or that day when it is
     later.") - 24 months back on a fresh stack.
2. They fill a unique name, the site URL `https://flow-e2e.atlassian.net`, a service-account email,
   an API token and one project key, then type a **Backfill from** date three years back and click
   **Create**.
   - *Expected*: the field shows "Backfill date must not be before YYYY-MM-DD (the history horizon)"
     and no create request is sent; nothing is created, so there is nothing to clean up.

## Scenario: a regular user sees no Data sources surface

1. The admin creates a throwaway user and signs out.
2. The user signs in.
   - *Expected*: there is no **Data sources** nav link, and visiting `/data-sources` directly
     redirects to Home (the `RequireAdmin` route guard).
3. The admin signs back in and deletes the throwaway user (the cleanup hook also removes it through the API if a step
   failed first).

## Not covered here (and why)

- **GitLab day-2 deltas and the token-scope refusal** (a project moved out of the groups, `TOKEN_SCOPE_TOO_BROAD`,
  `TOKEN_INACTIVE`, `NOT_ADMIN`) - pinned by the server's `GitLabConnectorTest` and the editor's component tests; the journey drives
  the day-zero happy path only. The force-push re-list, the stored-head range walk and the per-file line counts are
  pinned by the server's `GitLabCommitsStreamTest`/`GitLabCommitsWalkTest`; the incremental merge-request listing, the day-2 merge-request updates, the GraphQL gap and the details budget are pinned by the server's `GitLabMrStreamsTest`/`GitLabMrWalkTest`; a deleted merge request is reconciled in a later version.
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
