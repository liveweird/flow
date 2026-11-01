# GitLab integration (self-managed)

The `gitlab/` package and the code-activity layer around it: a self-managed GitLab instance read through its REST API (plus
one GraphQL query), stored raw in `raw.gitlab_*`, processed into the connector-agnostic `norm.code_*` tables, and tied to
the Jira people through admin-confirmed identity links. The queue, worker, cursors and PROCESS/PROFILE mechanics it plugs
into are in `.claude/docs/ingestion.md`; the Jira connector it sits beside is `.claude/docs/jira-integration.md`.

**Status: as built, 1.34.0-1.38.0** (PR 12 closed the sequence with the scaled-world figures, `build-times.md` WHY 59, and the real-instance checklist, "Real-instance smoke run").
Each section is the as-built reference (file names, tests); the PR sequence is at the end. Nothing here derives metrics. Reports over
`norm.code_*` are **built as an endpoint, a page and a drill (1.39.0, 1.40.0, 1.41.0)**: Report 21 "Code activity" (`reports.md` "Report 21", `domain-model.md` A50, `measures.md` "Report 21") reads the
code layer at query time and attributes through the identity links below; it adds no migration, job kind or stream to this connector, and
`DataSourceKind.derivesMetrics` stays false for GITLAB.

## What is tracked, per person

- **Commits** on each project's **default branch**: count, files changed, lines added and deleted (per file, so lock-file and
  vendored churn can be excluded). Merge commits (more than one parent) are flagged and left out of counts.
- **Merge requests** they authored or merged: state, times, branches, size (additions, deletions, files).
- **Review activity** on merge requests: comments, diff comments, approvals, review requests, reviews and changes requested.

## Decisions

- **Self-managed GitLab, current version.** gitlab.com is not a target. GitLab served at the root of its host (no path
  prefix) and reached without an egress proxy (v1 defaults; revisit if a real instance needs either).
- **Scope = whole groups, subgroups included.** Archived projects are excluded. A project moved out of the configured groups
  is marked out of scope; its rows stay.
- **Token = an admin-issued, read-only personal access token** (a bot user with `read_api`). Admin, so that user emails are
  visible to the identity proposals. The token is refused unless EVERY scope starts with `read_` or is `admin_mode` (needed by an admin token on an instance
  with Admin Mode on, and read-only beside `read_*`; an allow-list: `api`, `sudo`, `write_*`,
  `create_runner`, `manage_runner`, `k8s_proxy`, `self_rotate`, `ai_features` and any scope GitLab adds later are all `TOKEN_SCOPE_TOO_BROAD`), at Test
  connection and again at every SYNC (the job fails closed); an answer missing `scopes` or `active` is `INVALID_RESPONSE`, never a pass, and an
  inactive token is `TOKEN_INACTIVE`.
- **No git mirror and no repository content.** Flow never clones and stores no code, diffs, commit bodies, MR descriptions
  or note bodies. Kept: the commit's first line and the MR title (≤255 chars, for the Jira-key linking: "Issue keys"), branch names,
  file paths and per-file line counts. System notes become action codes; their bodies are dropped.
- **Identity = proposed by email, confirmed by an admin.** Unmapped activity is kept and becomes attributable once linked.
- **The connector does not feed DERIVE.** `DataSourceKind.derivesMetrics` is false for GITLAB; every Jira-only path is
  gated on it (below).

## The connector seam (BACKLOG D6; as built, PR 3)

Before GitLab, the seam was Jira-shaped: `Connector.testConnection(siteUrl, email, apiToken…)`, `DataSourceRequest.jira`, the
V8 `kind` CHECK allowing only `JIRA_CLOUD`, and `jira/Jira.kt` *replacing* the connector registry attribute. What landed (full
reference: `ingestion.md` "Connector kinds"; schema: `persistence.md` V40):

- **V40** widens the `kind` CHECK (`source_connections_kind_check`) to `('JIRA_CLOUD','GITLAB')`; schema-only.
- `Connector.testConnection(target: ConnectionTarget)` (`ingest/Connector.kt`): a sealed type with `Jira` (today's five fields) and
  `GitLab(baseUrl, token, groupPaths)`; `JiraConnector` refuses the other case. The Jira routes build `ConnectionTarget.Jira`.
- `ingest/ConnectorRegistry.kt`: `Application.connectorRegistry()` (the `jobHandlerRegistry()` pattern), `register(connector)`
  (duplicate kind = boot error), `connectorFor`, `connectors()`. `configureJira` registers `JIRA_CLOUD`; **`configureGitLab` must
  register `GITLAB` the same way and run after `configureJira` in `application.yaml`**. A claimed job whose kind has no connector fails
  with `CONNECTOR_UNAVAILABLE` (PURGE included), never crashing the loop.
- `DataSourceKind(derivesMetrics)`: `JIRA_CLOUD(true)`, `GITLAB(false)`. Settings are sealed (`ConnectionSettings`): Jira's stored
  JSON unchanged, plus `GitLabConnectionSettings(baseUrl, groupPaths, pathExclusions?)`; the decoder picks the shape by the row's
  kind, and `ResultRow.jiraSettings()` throws on any other kind. PR 6 filled in the GitLab shape and validation (below).
- **Kind-leak guards**, each pinned by a `KindLeakTest` case (a GITLAB row inserted over `sharedDatabaseForTests()`, `GitLabRowFixture`):
  the Jira scope check (`DataSourceScope.kt`, Jira rows only), `IngestWorker.onSucceeded` (DERIVE chaining only when
  `derivesMetrics`; a DERIVE job for a non-metrics kind fails), the `enabledActiveConnections()` fan-out behind
  `MetricsSettingsService.bumpRevision`, the report and Home connection scopes (`resolveConnectionScope`, `/reports/filters`,
  `fetchHomeConnections`, the report ETag stamp), the metrics-config routes and the raw-issue inspector (`409
  data-source-kind-unsupported`; the profile route decodes nothing Jira-shaped and stays per-kind-later).
- **Wire shape (as built, PR 6).** `DataSourceRequest`/`Response`/`TestRequest` carry two optional blocks, `jira` and `gitlab`; exactly
  one is present (else `400`), it sets the kind, and the kind is immutable after create (the other block on a PUT is `409`). No `oneOf` (the
  spec stays 3.0-compatible) and the Jira form's JSON is unchanged. The response's `jira` became `nullable` (so `schema.ts` types it
  `... | null`); `GitLabConnectionRequest{baseUrl, token?, groupPaths}` / `GitLabConnectionResponse{baseUrl, hasToken, groupPaths}`;
  `pathExclusions` is NOT on the wire yet (PR 7, with PROCESS, the first reader). `DataSourceService.list/read/update/readForTest` serve
  both kinds; the sync-job, status and stored-test routes work for a GITLAB row, which also takes the history-horizon backfill floor.
- **Base URL and the policy seam.** `ingest/` never imports `gitlab/`'s validator: `GitLabBaseUrlPolicy` (`ingest/GitLabDataSource.kt`)
  lives on `JobHandlerRegistry`, `configureGitLab` registers it in EVERY case (disabled GitLab answers `400 gitlab-disabled`), and it wraps
  `validateGitLabBaseUrl` (a refusal's stable code lands in the `400` detail, never the URL). The stored `baseUrl` is the canonical origin,
  so the identity compares exactly. `GET /api/v1/data-sources/kinds` is what the web picker reads.
- **Scope rules per kind (as built).** Jira keeps its rules, filtered to Jira rows. GitLab (`ingest/GitLabScope.kt`): one canonical
  `baseUrl` across the active GitLab sources (`data-source-other-instance`, create only), and no group path equal to, under or above another
  active source's (`data-source-group-overlap`), so no project is counted twice. Jira and GitLab rows never conflict with each other. The
  `data_source.*` audit events carry `kind` and `host` (renamed from `siteHost`).

Tests: `ConnectorSeamTest` (registry additive and duplicate-refusing, the booted app's registry, `ConnectionTarget` dispatch and secret
masking, the V40 CHECK, settings by kind and Jira's unchanged shape, `CONNECTOR_UNAVAILABLE` with a surviving worker loop, the PURGE
case), `KindLeakTest`, and `MigrationChecksumTest` (the V40 pin).

## Shared fetch machinery (`fetch/`)

The hardened Jira transport is lifted, not copied: `JiraHttp` → `SourceHttp`, the backoff, `JiraCallTally` → `CallTally`,
the page-token guard and the answer/run ceilings, the node ceiling (as-built in PR 2: `jira-integration.md` "The shared
transport"; the Jira-specific `SkipBudget` and the heap-fit/heap-share maths stay in `jira/` until a GitLab stream needs
them). The rate-limit signal is a strategy (`RateLimitSignal`): Jira reads `X-RateLimit-NearLimit`; GitLab will read
`RateLimit-Remaining`/`RateLimit-ResetTime` and pause near zero. `SourceHttp` sleeps the returned pause while HOLDING the
permit and outside the request deadline, so the GitLab signal must clamp its pause itself (a few seconds at most), or one
instance could hold permits the others need. GitLab's tally lands under the stored `jira*` progress keys (their strings are
persisted; a rename needs a migration of `sync_jobs.progress`). `PackageBoundaryTest`: `jira` and `gitlab` never import each other; both may import `fetch`.

## Outbound calls to a self-managed host

**As built (PR 4).** The SSRF guard (`infra/outbound/OutboundGuard.kt`, `.claude/docs/security.md` "Outbound HTTP calls" has the
full rule and the reason for each refusal) stays the only way out. A self-managed host usually lives on a private address, so the
guard gains an operator-only allowance. `configureGitLab` builds the client the connector uses (as built in PR 6, below).

- **`GITLAB_ALLOWED_HOSTS`** (`gitlab.allowedHosts`) - exact `host[:port]` DNS names (no port = 443), no wildcards, no IP literals. Empty =
  the GitLab kind is off (no client). The admin UI cannot widen it: a data source whose base URL's host is not listed is a 400.
- **`GITLAB_ALLOWED_CIDRS`** (`gitlab.allowedCidrs`) - private ranges a *listed* host may resolve to; any other host keeps the full address
  check. Boot refuses `/0`, ranges wider than /8 (IPv6: /32), any overlap with loopback, link-local (incl. 169.254/16, cloud metadata),
  any-local, multicast, 6to4, Teredo, IPv4-mapped or NAT64 space (NAT64 addresses are never admitted), and CIDRs without hosts. Parsed by `infra/outbound/SelfManagedHosts.kt` + `Cidr.kt` (`parseSelfManagedHosts`);
  `SelfManagedHosts.addressAllowed(host, address)` is the rule.
- **Guard seam.** `GuardedDns`/`guardedOkHttpClient`/`buildGuardedJiraHttpClient` take `addressAllowed(host, address)` (default
  `publicAddressesOnly`); Jira's wiring answers `true` for its dev stub host and `publicAddressesOnly` otherwise, unchanged. Resolution and
  the check happen per connection, so DNS rebinding cannot swap in an address after validation; a mixed answer fails the whole lookup.
  `guardedOkHttpClient` also takes `originAllowed` (scheme, host, port, checked per request: https to a listed `host[:port]` only, which
  also refuses an IP-literal URL OkHttp would never send to DNS; any caller-set `Host` header is refused too) and an optional
  `sslSocketFactory`/`trustManager` pair. The Jira client gets the same hook with `isAllowedJiraOrigin` (https:443 to an allowed Jira
  host, or the dev stub's exact origin; `JiraOriginGuardTest`); only the loopback-fixture test factory leaves it off.
- **Base URL:** `gitlab/GitLabBaseUrl.kt` `validateGitLabBaseUrl(raw, hosts, devStub)` - `https`, origin only (empty or `/` path, no query,
  fragment or userinfo), host[:port] on the list (443 = no port); `http` only for the dev stub's own origin. A failure is a
  `GitLabBaseUrlException` whose stable `code` (`gitlab-base-url-invalid|scheme|not-origin`, `gitlab-disabled`, `gitlab-host-not-allowed`)
  the data-source 400 maps from (`configureGitLab`'s `GitLabBaseUrlPolicy`, "The connector seam").
- **Internal CA:** `GITLAB_CA_BUNDLE` (PEM, `infra/outbound/CaBundle.kt` `loadCaBundleTrust`) is added to the GitLab client's trust store
  only, alongside the JVM defaults. There is no "skip TLS" switch (`GitLabCaBundleTest` pins the `gitlab.*` key set).
- **Kept from Jira:** no redirects, no proxy, no connection-failure retry. `Link` headers are never followed: only their page/keyset
  parameters are read and re-applied to the configured origin (as built: `HttpGitLabClient`).
- **Stub:** `gitlab.stubBaseUrl` (`GITLAB_STUB_BASE_URL`) is development-only; production refuses to start with it set, or with any
  malformed host, CIDR or CA setting (a CA bundle needs listed hosts, like the CIDRs). In development the stub host is also an allowed host and skips the address check, on its own
  origin only.
- **Memory:** `gitlab.maxResponseBytes` (`GITLAB_MAX_RESPONSE_BYTES`) defaults, when blank, to `min(8 MiB, jira.maxResponseBytes)`; an
  explicit value is bounds-checked (1 KiB..128 MiB) and refused above `jira.maxResponseBytes` (even while GitLab is disabled; the
  message carries both values). `JiraHeapFit` sizes the per-slot response tree from the Jira ceiling, so a GitLab ceiling at or below it
  stays inside that figure. **In-flight GitLab bodies share Jira's permits** (resolved in PR 6): `configureJira` publishes
  `OutboundPermits(jira.maxConcurrentRequests)` (`fetch/OutboundPermits.kt`, `OutboundPermitsKey`) and GitLab's `SourceHttp` draws on that pool,
  so the fit's `jira.maxConcurrentRequests x jira.maxResponseBytes` in-flight term already covers GitLab and no GitLab term is added. Why not a
  term of its own: at the defaults the fit sits at 439.3 of 448 MiB, so ANY added in-flight term (even one 8 MiB body) would refuse the default
  `-Xmx512m` worker. The cost is that a slow GitLab host can hold permits Jira needs, and the starvation is bounded: a GitLab job holds at most ONE permit at a time
  (its calls are sequential), so Jira is starved only if `ingest.workerSlots` >= `jira.maxConcurrentRequests` and every slot runs a GitLab job. The hold
  is bounded by the per-attempt timeout
  (`gitlab.requestTimeoutSeconds`, 1..120, default 30) and by the rate-limit pause's 5 s clamp, and backoff and tenant pauses already hand
  the permit back. Also `gitlab.maxRetries` (`GITLAB_MAX_RETRIES`, 0..10, default 4), `gitlab.requestDeadlineSeconds`
  (`GITLAB_REQUEST_DEADLINE_SECONDS`, 1..1800, default 180) and `gitlab.usersRefreshHours` (`GITLAB_USERS_REFRESH_HOURS`, 0..168, default 24).
  `gitlab.commitFilesPerRun` (`GITLAB_COMMIT_FILES_PER_RUN`, 10..100000, default 5000) is the diff CALLS (pages) one SYNC run spends on the COMMIT FILES backlog
  (a run at ~20 calls/s lasts a few minutes, well inside `ingest.maxJobMinutes`); reaching it ends the run normally with `commitFilesPending` and the next
  run continues. `gitlab.mrDetailsPerRun` (`GITLAB_MR_DETAILS_PER_RUN`, 10..100000, default 3000) is the same for the MR DETAILS backlog, counting REST and
  GraphQL calls (about three per merge request: ~950 merge requests per run); reaching it ends the run normally with `mrDetailsPending`. A GitLab job holds
  ONE permit at a time, so the backlogs are sequential (a fetch-ahead is queued in `BACKLOG.md`; "Performance at scale"). **The one POST:** the diff stats are a read-only GraphQL query
  (`POST <origin>/api/graphql`, a fixed query with the project path and at most 50 iids as variables) through the same guard, permit, retries and ceilings as
  every REST call - the guard is per request and method-agnostic, so nothing in `infra/outbound/` changed.
- **The client** (`gitlab/GitLabHttp.kt`, `GitLabClient.kt`, `GitLabModels.kt`): `GITLAB_SOURCE = FetchSource("GitLab", "gitlab.maxResponseBytes",
  GitLabRateLimitSignal(), authHeaderName = "PRIVATE-TOKEN")`; `createGitLabHttp` builds the `SourceHttp` over the shared permits. The token is the
  raw `PRIVATE-TOKEN` value, never `Authorization`. `GitLabRateLimitSignal`: `RateLimit-Remaining` at 5 or less pauses until `RateLimit-Reset`
  (epoch seconds), clamped to 1..5 s (2 s when the reset is absent or unreadable), else no pause - it sleeps holding the permit, so the clamp
  is hard. `HttpGitLabClient` builds every URL as `<baseUrl>/api/v4<path>` with the query in the `query` map; the next page comes from
  `X-Next-Page` (a value that does not advance is `INVALID_RESPONSE`) and is re-applied to the configured origin - `Link` is never read, so a
  server cannot steer a call to another host. Ids spliced into paths are validated numeric; a group path travels as ONE percent-encoded segment
  (`platform%2Ftooling`; `GitLabClientTest` pins that Ktor keeps the `%2F`). The rate-limit pause key is the canonical base URL.
- **Wiring:** `gitlab/GitLab.kt` `configureGitLab()` runs in every role, right after `configureJira`. It publishes `GitLabSettingsKey`,
  `GitLabHostsKey` (always), registers the base-URL policy (always) and, only when enabled (hosts listed or a dev stub), `GitLabHttpClientKey`
  (closed on `ApplicationStopped`), `GitLabRawStoreKey` and the `GITLAB` connector (`GitLabConnectorKey`, also in the connector registry).
  `buildGuardedGitLabHttpClient` is the factory tests reuse. `gitlab/GitLabConfig.kt` reads and validates `gitlab.*`.
- **Tests:** `SelfManagedHostsTest`, `GitLabOutboundGuardTest`, `GitLabBaseUrlTest`, `GitLabCaBundleTest` (test-only CA
  `server/src/test/resources/gitlab-test-ca.pem`, no private key), `GitLabBootTest`, `PackageBoundaryTest` (`jira` and `gitlab` never
  reference each other).
- **k8s:** the egress NetworkPolicy excludes private ranges; a private GitLab needs its own additional egress rule.

Example (neutral): `GITLAB_ALLOWED_HOSTS=gitlab.example.com`, `GITLAB_ALLOWED_CIDRS=10.20.0.0/16`.

## Test connection

`GitLabConnector.testConnection(ConnectionTarget.GitLab)` runs the shared probe harness (`ingest/ConnectionProbes.kt` `ProbeRun`: at most 10 s
per probe and 30 s in all, every outcome a `ConnectionTestRow`, never a thrown failure). Rows, in order: `version` (`/version`); `user`
(`/user`); `user_admin` (OPTIONAL; `NOT_ADMIN` when `is_admin` is false: user emails will be hidden, so identity proposals degrade);
`token_scopes` (`/personal_access_tokens/self`, REQUIRED; `TOKEN_SCOPE_TOO_BROAD` when any scope does not start with `read_`,
`TOKEN_INACTIVE` when the token is not active; scope hint `read_api`); per configured path `group:<path>` and `group_projects:<path>`
(REQUIRED; the group body's `full_path` must equal the configured path case-insensitively, else `INVALID_RESPONSE`; a group row that failed
leaves its projects row `NOT_FOUND`); `users` (REQUIRED); `user_emails` (OPTIONAL: the token owner's `/users/:id/emails`, which tells whether
emails are visible). The result carries no `cloudId`. Both Test connection routes build the target per kind and hold the web `JiraHeapShare`
like Jira (one response tree at a time is what the web heap fit assumes). The token is stored in the encrypted `source_connections.secret`
like the Jira token and never echoed or logged.

## Streams

All REST except MR diff stats, which come from one batched GraphQL query (`diffStatsSummary {additions deletions fileCount}`
for up to 50 MRs) instead of a paged `/diffs` call per MR. A GraphQL error leaves a null gap; it never fails the job.

- **PROJECTS** (as built, PR 6; `gitlab/GitLabProjectsStream.kt`) - per configured group, in order: resolve it by its encoded path
  (`/groups/:path`; a group the token cannot see fails the stream `NOT_FOUND`), then page `/groups/:id/projects?include_subgroups=true&
  archived=false&with_shared=false&per_page=100` into `raw.gitlab_projects`, keyed by project id and stamped with the CONFIGURED group it was
  listed under. Defensive filters for an older server: an archived project, or one whose namespace is not the group or under it, is skipped
  (`projectsSkipped`, one WARN with ids per run). Cursor `{passStartedAt, groupIndex, page}`; the pass ends by marking the live projects not seen
  during it `moved_out_at` (rows stay; seen again, resurrected). Budget: `RunCeiling(100 pages, 10,000 projects)` per group. The cursor also carries the configured
  group list; a cursor written for another list (an edit mid-pass) is dropped and the pass restarts, because its index would name another
  group. A project listed under another configured group than the stored one, or whose `default_branch` is wider than 255 characters (skipped,
  counted in `projectsSkipped`, never truncated: PR 7 puts the branch in a URL path), is handled explicitly: the first is a CHANGE although
  `group_path`/`group_id` are outside the hash. **Offset paging can miss an item deleted mid-pass** (every later item shifts one place), so
  that item is marked moved out/deleted until the next pass sees it again (rows stay, and the next pass resurrects them). An ABSENT
  `X-Next-Page` header is `INVALID_RESPONSE` (GitLab sends it, empty, on the last page): a proxy stripping it must not end a pass after page 1.
- **USERS** (as built; `GitLabUsersStream.kt`) - `/users?per_page=100` into `raw.gitlab_users`; with an ADMIN token each user's confirmed
  secondary emails from `/users/:id/emails` (first 100; a 403 turns the rest of the run to "no emails", a 404 skips that user), merged with the
  list's own `email`/`commit_email`/`public_email` into the sorted, lowercased `emails` that is part of the hashed payload. Not an admin: only the
  list's own fields, `usersEmailsSkipped`, one WARN. Skipped whole (`usersSkipped`) while every live user was seen within `gitlab.usersRefreshHours`
  ("Sync now" never skips). The pass ends by marking the users not seen deleted. Budget: `RunCeiling(500 pages, 50,000 users)`. **One response tree at a time** (the heap fit's one-tree-per-slot): each `/users` page is slimmed to small values
  (`GitLabUserSummary`) INSIDE the response transform, and each `/users/:id/emails` answer is reduced to its address strings inside its own, so the
  page's tree is garbage before the first emails call is parsed.
- **SYNC order and guards** - the job first re-probes `/personal_access_tokens/self` and `/user`: a token with a scope beyond `read_*` fails
  the job `TOKEN_SCOPE_TOO_BROAD`, an inactive one `TOKEN_INACTIVE` (`JobRefusedException`), before anything is stored. REPROCESS re-flags both raw tables and rebuilds the code layer ("PROCESS / PROFILE"); RECONCILE is below;
  PURGE drains all six raw tables, then the six `norm.code_*` tables (`code_mr_issue_keys` first). The streams run PROJECTS -> USERS -> COMMITS -> COMMIT FILES. The job runs under the shared `CallTally` (GitLab's throttling lands under the stored `jira*` progress keys).
- **COMMITS** (as built, PR 7; `GitLabCommitsStream.kt` + `GitLabCommitsSweep.kt`, cursor `{passStartedAt, lastCompletedProjectId, walk}`) - per live project in id order
  (`moved_out_at`/`deleted_at` null), the commits of the DEFAULT branch into `raw.gitlab_commits` (no hash, a commit is immutable by SHA; the stored
  whitelist is `title`, author name/email, authored/committed time, parent count and `stats`: never the body, the committer or a diff). What a
  project needs is decided once per run, with no write: no stored head -> **FULL** (`ref_name=<branch>&since=<backfillFrom>&with_stats=true`);
  `relist_requested` -> **RELIST**; else **CHECK**: page 1 of the branch, whose first commit is the tip - equal to the stored head: done, one call;
  else `merge_base(head, branch)`: the head itself means the history only grew -> **RANGE** (`ref_name=<head>..<branch>`, which also catches commits
  merged in with old dates that a `since` cursor would miss); another commit, or `REF_NOT_FOUND`, is a force-push -> `relist_requested` is set and
  the project is RELISTED. A RANGE listing that answers `NOT_FOUND` (the head vanished) turns into a RELIST in the same run. A renamed default
  branch needs no case: the head is a SHA. A project without a default branch, or whose branch or project answers `NOT_FOUND` (on the CHECK, or on the listing of a FULL or RELIST;
  only a RANGE turns a `NOT_FOUND` into a RELIST), is skipped (`commitsProjectsSkipped`, one WARN naming ids, any re-list request left as it was);
  PROJECTS will rename it or RECONCILE delete it. Every listing page commits its rows and the cursor in one transaction; the head moves (and
  the request clears) with the LAST page of a FULL/RANGE walk and with the sweep of a RELIST, never before the commits are stored. A saved walk for
  another branch than the stored `default_branch` is dropped. Budget: `RunCeiling(500 pages, 50,000 commits)` per project per run (a trip is
  `LIMIT_EXCEEDED` before the page's transaction).
  **RELIST and the `since` caveat (L3).** A RELIST lists the branch from `since` = the oldest LIVE stored commit's date, never before the backfill
  floor (the floor alone moves forward daily and would sweep the commits between the old and the new floor; an ancient commit merged in through a
  RANGE must not widen the window past it). GitLab's `since` is git's `--since`: it does NOT filter, it STOPS walking once everything queued is older
  than the date, and the parents of an older-dated commit are then never listed. With clock skew (the stub has 15 commits committed before their
  parent) a listing can miss commits that are still on the branch, so "not listed" is only a CANDIDATE.
  *The epoch.* When a RELIST begins (`relist_requested` turns true) `raw.gitlab_projects.relist_started_at` is set, and kept until the flag clears.
  The candidates are the project's live commits with `committed_at >= since` that nothing has seen since the epoch (`last_seen_at < relist_started_at`):
  the listing's pages and the sweep's touches both count as seeing. A per-run pass start would re-qualify every kept candidate in every run; the epoch
  makes each handled candidate (kept, unconfirmed or tombstoned) leave the set for good, so repeated runs converge.
  *Continuing a deferred re-list.* A project whose request is still set (the sweep recorded its tip as the head and left candidates over) first reads the
  branch tip (page 1, no `since`). Tip == head: nothing to list again, straight to the sweep under the same epoch. The head no longer an ancestor of
  the tip (`merge_base(head, tip) != head`, or `REF_NOT_FOUND`): the branch was force-pushed AGAIN, so the epoch RESTARTS (a new `relist_started_at`; the
  commits the first sweep touched are candidates again, which is what finds what the second push removed). Otherwise the history only grew and the
  re-list continues under its epoch. A branch that cannot be read skips the project with its request left as it was.
  *The confirmation* (after the listing, outside any transaction; at most `MAX_SWEEP_CONFIRMATIONS` = 100 per project per run): each candidate is
  asked against the SNAPSHOT TIP, `merge_base(candidate, tip)`. The tip is the listing's first commit; for an EMPTY listing (every commit older than
  `since`) it is the branch's own tip, read once (page 1, no `since`), and that tip is recorded as the head too, so the next run is a plain CHECK
  (tip == head) and not a re-list. The commit itself means it is still an ancestor (kept, `commitsSweepKept`); another commit, or `REF_NOT_FOUND`, means
  it left the branch (tombstoned, `needs_processing` set - a rewritten-away commit usually still exists as an unreachable object, so its merge base is
  the fork point). Nothing is believed unless the tip is still on the branch: ONE probe, `merge_base(tip, branch) == tip`, before the batch (a branch
  renamed, a project deleted or a second force-push in between makes it fail) and, if any verdict was a `REF_NOT_FOUND`, ONE more after it (a project
  lost mid-batch answers `REF_NOT_FOUND` for the rest of it; a 200 with a different merge base needs no re-probe); a failed probe makes the affected
  candidates UNCONFIRMED. A probe that fails for any OTHER reason (rate limit, authentication) fails the run, and the saved `listingDone` cursor
  resumes straight at the sweep next run. Unconfirmed (a tip that cannot be read, any other candidate failure, or a failed probe) means KEPT and
  TOUCHED and counted (`commitsSweepUnconfirmed`): keeping a gone commit live is the safe error (RECONCILE re-checks), and a persistently failing
  candidate must not pin the project in RELIST. Only when MORE candidates remain than the cap does `relist_requested` (and the epoch) stay set for the
  next run (`commitsSweepDeferred`); otherwise the request and the epoch clear, and a missing tip keeps the stored head. The same early stop can leave a
  gap in a FULL listing from the backfill floor; RECONCILE's `since`-less walk (below) is the catch-up. Progress: `pages`, `commits` (inserted), `commitsRelisted`,
  `commitsTombstoned`, `commitsSweepKept`, `commitsSweepUnconfirmed`, `commitsSweepDeferred`, `commitsProjectsSkipped`.
- **COMMIT FILES** (as built, PR 7; `GitLabCommitFilesStream.kt`, cursor-less: the backlog IS its state) - the live NON-MERGE commits (`parent_count <= 1`;
  a merge's diff is against its first parent, a double count) of LIVE projects (not moved out or deleted: a moved-out project's commits cost no diff call and
  are not counted in `commitFilesPending`, in this stream or the status view) whose diff was never read, newest first, one `/commits/:sha/diff?per_page=100` chain at a
  time (sequential: a GitLab job holds one permit), at most `gitlab.commitFilesPerRun` diff CALLS per run. The client transform counts each
  file's `+`/`-` lines (after the first `@@`; `---`/`+++` inside a hunk are content; not `\ No newline at end of file`) and drops the text, so the
  tree is garbage before the next call; a file with no `diff` string, or `too_large`/`collapsed`, keeps its path with NULL lines (unknown beats an
  undercount). Kept per file: `path` (`new_path`, cut to 1,024 characters and 2,000 bytes), `old_path` (renames only), additions, deletions, new/deleted/renamed flags and GitLab's
  `generated_file`; a path GitLab lists twice (delete + add at one path) is ONE row, lines summed. At most 1,000 files (10 pages; an 11th page
  makes the commit `files_capped`). A commit is written with its stamp when complete - in batches of 25 commits or 5,000 rows, at the end of each
  claimed batch of 50, and on a failure - so a run cut anywhere stores every complete commit and never half of one. Terminal outcomes are stamped so
  the backlog drains: a diff page over `gitlab.maxResponseBytes` or the node ceiling is `files_error = LIMIT_EXCEEDED` (`commitFilesOversized`), a
  commit GitLab no longer has `NOT_FOUND` (`commitFilesMissing`); both leave `files_changed` null. More than `max(10, 10 %)` oversized commits in
  one run fail the run `LIMIT_EXCEEDED` (the shared `SkipBudget`: the cap is mis-set, not isolated outliers; the same 11 oversized commits at the top of the newest-first backlog trip it on
  EVERY run until `gitlab.maxResponseBytes` is raised, which is the point: nothing is stamped until the cap is fixed) and NONE of that run's oversized
  commits is stamped, so a mis-set cap does not strand the commits it met first. Reaching the call budget is normal (checks run between commits, so one
  commit may overshoot by up to nine calls). Progress: `commitFiles`, `commitFileRows`, `commitFilesCapped`, `commitFilesOversized`,
  `commitFilesMissing`, `commitFilesPending`.
- **Stream order** (as built, PR 8): PROJECTS → USERS → COMMITS → MERGE REQUESTS → COMMIT FILES → MR DETAILS. The listings come first and the budgeted
  backlogs after them: a listing is a handful of calls a run and must not wait behind a backlog that may spend its whole budget; the two backlogs keep
  the order they were added in.
- **MERGE REQUESTS** (as built, PR 8; `GitLabMergeRequestsStream.kt`) - per configured group in order, `GET /groups/:id/merge_requests?scope=all&state=all&
  include_subgroups=true&order_by=updated_at&sort=desc&per_page=100[&updated_after]` into `raw.gitlab_merge_requests`, hash-diffed like projects
  (`upsertMergeRequests`: INSERTED / UNCHANGED / CHANGED / RESURRECTED; a change moves `changed_at`, which makes the details stale). The group id is read
  from the LIVE stored projects of the configured path (PROJECTS ran earlier in the SYNC; the most recently seen id wins); a path with no live project lists
  nothing (`mrGroupsSkipped`). **Newest first** is deliberate: with offset paging a merge request UPDATED mid-pass moves to the FRONT and the next page
  re-shows one already seen (an `UNCHANGED`), whereas `sort=asc` would skip the first item of the next page. It does NOT cover an item that DROPS OUT
  mid-pass (a merge request deleted, or leaving the group): everything after it shifts one place earlier and the first item of the next page is skipped,
  with an `updated_at` below the watermark. **That residual gap is closed by RECONCILE (below), which re-lists every group's merge requests from the floor
  with no watermark.**
  **Incremental per group:** a group's first pass asks `updated_after` from the backfill floor, every later pass from its WATERMARK minus
  `MR_LIST_OVERLAP_MILLIS` (15 min). The watermark is GitLab's OWN clock - the largest `updated_at` the group's pages showed, page 1's first item - and it
  moves only when the group's LAST page commits (never backwards, no Flow clock). The `sync_cursors.merge_requests` row PERSISTS between passes
  (`MergeRequestsCursor(watermarks, pass, projectSets)`; `watermark_at` = the smallest group watermark, `last_completed_at` = the end of the last pass); mid-pass it
  holds `MergeRequestsPass(groups, groupIndex, page, updatedAfter, seenMax, projectIds)` written in the page's own transaction, so a run cut anywhere resumes at the
  page it stopped on with the same `updated_after` and the running maximum. A pass saved for another list of groups is dropped and restarts (its
  watermarks are kept; a watermark of a group no longer configured is dropped; the saved list must EQUAL the configured one, order included).
  **A project that newly enters a group** (moved or renamed into the configured groups) has older merge requests below the watermark: `projectSets` records,
  per group, the live stored project ids the group had when its last COMPLETED pass began, and a pass that finds a live project not in that set lists from the
  backfill floor (the cost is one listing page per 100 merge requests of the group plus a `last_seen_at` touch per row, the rest coming back `UNCHANGED`; a newly CREATED project triggers it too); a shrinking set forces nothing, and a group with no recorded set (an older cursor) is unknown and forces
  nothing until its next completed pass records one. A group GitLab answers `NOT_FOUND` for (any page) is skipped for the pass -
  `mrGroupsNotFound`, one WARN per run naming the configured paths, its watermark untouched - and fails neither the SYNC nor the other groups. A group with no
  live stored project is `mrGroupsSkipped`. The heartbeat counts as progress whenever the page cursor advanced. A merge request whose project is not a stored project row
  (an archived one, an older server ignoring `non_archived`) is skipped and counted (`mrSkipped`, one WARN per run naming at most 20 ids); any stored
  project, moved out included, qualifies. **Nothing here tombstones:** a merge request an incremental listing omits is no evidence it is gone;
  `deleted_at` is RECONCILE's, on a positive check only. Ceiling: `GitLabCeilings.mergeRequests` (1,000 pages / 100,000 items per group per run;
  `LIMIT_EXCEEDED` before the page's transaction, the cursor resumes). Stated assumptions (unconfirmed until the real-instance smoke run, item 5): `updated_after` is inclusive, filters by
  `updated_at` and is unaffected by deletions; `non_archived` defaults to true. A merge request in a project created after PROJECTS ran but listed before
  the next PROJECTS pass is skipped (`mrSkipped`); the project's later arrival re-lists the group from the floor (above). Progress:
  `pages`, `mergeRequests` (inserted), `mergeRequestsChanged`, `mrSkipped`, `mrGroupsSkipped`, `mrGroupsNotFound`.
- **MR DETAILS** (as built, PR 8; `GitLabMrDetailsStream.kt`, cursor-less: the backlog IS its state) - the live merge requests of LIVE projects whose
  `details_synced_at` is null or older than `changed_at`, newest `updated_at` first, in claimed batches of 50, one merge request at a time (sequential: a
  GitLab job holds one permit and one parsed tree). Per merge request: its notes (`/notes?sort=asc&order_by=created_at&per_page=100`, at most 1,000 = 10
  pages; an 11th makes it `notes_capped`) reduced INSIDE the transform to action codes (`GitLabMrNotes.kt`; no body is ever kept), `/approvals` (the legacy
  object, one APPROVAL row per approver, no time) and `/reviewers` (one REVIEWER row per entry, the state upper-cased), and its diff stats from ONE
  batched GraphQL `POST <origin>/api/graphql` per project and claimed batch (`diffStatsSummary` for up to 50 iids, memoised for the batch and counted as one
  call; asked BEFORE the merge request's REST reads, so an outage wastes none). The events and the stamp land in ONE transaction per merge request (`settleMrDetails`: delete the old set, insert the new, stats, cap, stamp
  `GREATEST(now, changed_at)`), so a run cut anywhere - lease, clock, failure - has committed every merge request it finished and nothing of the one it was on.
  Budget: `gitlab.mrDetailsPerRun` REST + GraphQL CALLS per run, checked between merge requests (one may overshoot by up to twelve); reaching it is a NORMAL
  stop - no ceiling trip, no failed job - and reports `mrDetailsPending`. Outcomes that are not failures, each stamping the merge request so nothing repeats
  every run: the notes (any page) answer `NOT_FOUND` -> `details_error = NOT_FOUND`, no events, `mrDetailsMissing`, NO tombstone (a re-listed, changed merge
  request clears the stamp); `/approvals` or `/reviewers` alone answer `NOT_FOUND` -> none for that merge request (`mrApprovalsUnavailable`,
  `mrReviewersUnavailable`; an instance without the endpoint must not mark every merge request gone); a REST response over `gitlab.maxResponseBytes` or the
  node ceiling -> `details_error = LIMIT_EXCEEDED` (`mrDetailsOversized`); one of the three failing `UPSTREAM_UNAVAILABLE`, `TIMEOUT` or `INVALID_RESPONSE` after the
  transport's retries -> `details_error = FETCH_FAILED` (`mrDetailsFailed`; a merge request that fails every time must not block the backlog behind it). Both
  are stamped at the END of the run through ONE shared `SkipBudget` (more than `max(10, 10 %)` of them fail the run and NONE is stamped - the last failed read's own code when failed reads are at least as many as oversized ones, else `LIMIT_EXCEEDED`, so an outage backs off like an outage and is never a ceiling trip; on any ABNORMAL end - a failure, the wall clock - only the oversized ones are stamped and the failed reads stay stale for the next run), are
  excluded from this run's re-claims until then, and are read again when the merge request changes (the listing sheds a `NOT_FOUND`/`FETCH_FAILED` stamp);
  the GraphQL call answering `errors[]`, a null project or lacking a merge request, or failing with `INVALID_RESPONSE`/`NOT_FOUND`/`LIMIT_EXCEEDED`/`REDIRECT`/`FORBIDDEN_SCOPE` (answer problems, or fast and usually permanent: GraphQL disabled,
  redirected or refused) -> a NULL
  GAP for those merge requests (`mrDiffStatsGaps`, counted when the merge request is stamped; one WARN per run with the count and the first error code),
  stamped complete and NOT asked again until the merge request changes (RECONCILE re-asks the gaps and un-stamps failed reads, below). **The GraphQL circuit breaker:** the first failure of a
  TRANSIENT-outage kind (`TIMEOUT`, `UPSTREAM_UNAVAILABLE`) makes NO further GraphQL call for the rest of the run (`mrGraphqlBreaker` 1, one WARN) but the stream
  KEEPS DRAINING: every merge request settled from then on, the failing batch included, is stamped with its stats gapped and its REST reads go on, so an
  outage costs one GraphQL call a run and never stalls the events backlog (a disabled, redirected or refused endpoint is a gap per batch instead). Every other failure - a rate limit, a rejected token, a
  blocked host - propagates after the deferred stamps are written. Progress: `mrDetails`, `mrEvents`, `mrNotesCapped`, `mrDetailsMissing`,
  `mrDetailsOversized`, `mrDetailsFailed`, `mrDiffStatsGaps`, `mrGraphqlBreaker`, `mrApprovalsUnavailable`, `mrReviewersUnavailable`, `mrDetailsPending`.
- **RECONCILE** (as built, PR 9; `GitLabConnector.runReconcile`) - once a day past `reconcile_hour_utc` and on "Reconcile now" (the queue, schedule, back-off, V38 pause and button
  are the existing ones). The token is re-probed first, then three streams run in this order under the shared tally, each with its own `current_stream`: the cheap, high-value
  merge-request work lands before the ~800-call commits walk, so a wall-clock stop lands in the commits. Nothing SYNC's cursors hold is read or written; every cursor is cleared at the
  pass end. Durable changes (tombstones, marks, refilled stats, un-stamps, an upsert that changed a row, a confirmation batch that kept candidates) heartbeat `changed = true`; a listing
  page of unchanged rows and a probe that wrote nothing heartbeat `false`, so a RECONCILE that trips a ceiling every day without changing anything is paused like a SYNC.
  1. **`reconcile_projects`** (`GitLabReconcileProjectsStream`; a keyset cursor `{lastProbedId}` exists only while the moved-out set outgrows one run) - `GET /projects/:id` for the moved-out, not yet deleted projects, by id from the keyset and wrapping (at most `MAX_PROJECT_PROBES` = 500 a run, so a bigger set is probed within a few runs): a
     **404** sets `deleted_at` (`projectsDeleted`; guarded, so a repeat writes nothing), a 200 with `archived` sets `archived = true` and nothing else (`projectsArchived`), any other
     200 writes nothing (`projectsProbed`); a failure one project can cause on its own is counted and skipped (`projectProbesFailed`). The 200 body is NEVER upserted (`changeProject` would resurrect the row). A deletion cascades nothing: the project's commits and merge
     requests keep their rows. A deleted LIVE project becomes moved out at the next SYNC and deleted at the next RECONCILE.
  2. **`reconcile_merge_requests`** (`GitLabMergeRequestsStream` in `reconcile` mode, cursor `MergeRequestsCursor` with `reconcile {passStartedAt, completedGroups, listingDone}` and
     empty watermarks) - every group re-listed from the backfill floor, same page transaction and ceiling. The **candidates** are the live merge requests of LIVE projects in a group
     the pass listed to its last page, updated at or after the floor, not seen since the pass began - stalest-checked first, so a stable unlisted set larger than the cap rotates.
     Each is **confirmed** before it is tombstoned (the commits sweep's discipline): `GET /projects/:id` must answer 200 first (404: every candidate unconfirmed; another failure
     propagates), then `GET .../merge_requests/:iid` - 200 keeps it (the body is stored: unchanged rows are touched, a changed one is refreshed and its details go stale; `mrReconcileKept`),
     404 is tentative, any other failure leaves it live and touched (`mrReconcileUnconfirmed`); if any verdict was 404 the project is probed again and only a 200 tombstones them
     (`deleted_at`, PROCESS flagged, events kept; `mergeRequestsDeleted`). A project-scoped failure of either project probe (`FORBIDDEN_SCOPE`, `UPSTREAM_UNAVAILABLE`, `TIMEOUT`, `INVALID_RESPONSE`, `REDIRECT`) leaves that project's candidates unconfirmed and the pass goes on, in all three streams; `RECONCILE_CONSECUTIVE_FAILURES` = 3 of them in a row inside one stream rethrow the last (an instance-wide outage fails and backs off), and every other failure (`RATE_LIMITED`, `AUTHENTICATION_FAILED`, `TOKEN_*`, `BLOCKED_HOST`) propagates at once. At most `MAX_MR_CONFIRMATIONS` = 200 per run (`mrReconcileDeferred`). What prevents a wrong tombstone: a failing
     listing propagates before any sweep; a skipped page makes a candidate that answers 200; a group that was `NOT_FOUND` or skipped is not in `completedGroups`; an invisible project
     fails the pre-probe, one lost mid-batch the post-probe; a project that moved out of the group minutes ago answers 200 by id and is kept (the next SYNC marks it moved out and the
     next RECONCILE excludes it); a merge request exactly at the floor is probed and kept; a project with merge requests switched off answers 200 while its merge requests 404 - they ARE
     tombstoned (rows and events stay; a listing that shows them again resurrects them), as are those of an internal or public project whose merge requests are limited to its members once the token's user loses membership (recoverable the same way). A project the SYNC has just found archived keeps costing probes for its merge requests until the next SYNC marks it moved out. After the sweep the **stats re-ask** (settled live merge requests of live projects with no
     diff stats, grouped by project, 50 per GraphQL call, at most `RECONCILE_STATS_CALLS` = 100 calls; an outage code ends the pass, a gap code or a null project moves on; the stamp is
     untouched; `mrStatsRefilled`, `mrGraphqlBreaker`) and the **details retries** (ONE un-stamp of at most `RECONCILE_DETAILS_RETRIES` = 5 merge requests (half the details skip budget's floor of 10, which counts them with the same run's fresh failures: five that fail again plus five new ones never trip it) stamped `FETCH_FAILED` or
     `NOT_FOUND`, the stalest first, so the next SYNC's MR DETAILS reads them; `LIMIT_EXCEEDED` is included only on "Reconcile now", because an oversized merge request stays
     oversized until `gitlab.maxResponseBytes` is raised; `mrDetailsRetried`). RECONCILE never runs MR DETAILS itself.
  3. **`reconcile_commits`** (`GitLabCommitsStream` in `reconcile` mode, a `CommitsCursor` whose walk has `reconcile = true`) - a project without a head is left to SYNC's FULL walk; every
     other synced project gets a RELIST under a FRESH epoch (so the candidates earlier kept UNCONFIRMED are asked again) with NO `since` on the wire: GitLab's `since` cuts a
     skew-dated commit AND its parents, so only a walk from the tip lists them. Rows older than the floor are passed through, never stored, and the listing ends after a page entirely
     older than the floor minus `RECONCILE_COMMITS_SLACK_MILLIS` (14 days). The sweep is the unchanged `CommitsSweep`. The fresh epoch lives in the walk's cursor and the walk raises no `relist_requested`: a branch or project answering `NOT_FOUND` (the renamed default branch), a project-scoped failure, or a cut run leaves the row exactly as it was and the next SYNC is a plain CHECK that follows it; only a sweep that defers candidates writes the request and its epoch, for SYNC's `continueRelist`. The cap `MAX_SWEEP_CONFIRMATIONS` is per project, so a run's real bound is projects x about 103 `merge_base` calls. A project whose FULL walk listed nothing has no head and is never reconciled.
  Daily cost, measured (200 projects, 15k commits and 3k merge requests on the stub, `build-times.md` WHY 59): 234 listing pages, about 236 calls, 3.8-4.6 s (about 16 ms a call). Derived to 60k commits / 20k merge requests: about 720 calls and about 12 s, far inside `ingest.maxJobMinutes`; a real instance's per-call latency scales it (720 calls at 100 ms is about 72 s), and a sweep with candidates adds up to about 103 `merge_base` calls per project. Progress keys:
  `projectsDeleted`, `projectsArchived`, `projectsProbed`, `projectProbesFailed`, `mrReconcileCandidates`, `mrReconcileKept`, `mrReconcileUnconfirmed`, `mrReconcileDeferred`, `mergeRequestsDeleted`,
  `mrStatsRefilled`, `mrGraphqlBreaker`, `mrDetailsRetried`, plus the listings' own (`pages`, `commits`, `commitsRelisted`, `commitsTombstoned`, `commitsSweep*`, `commitsProjectsSkipped`,
  `mergeRequestsChanged`, `mrSkipped`, `mrGroupsSkipped`, `mrGroupsNotFound`). The two new outbound GETs go through the same guarded client as every call.
- **PROCESS / PROFILE (as built, PR 10)** — into `norm.code_*` (V44) under `CODE_PROCESSING_VERSION`, separate from Jira's `PROCESSING_VERSION`, so
  neither side reprocesses the other (a bump claims every raw row again). Local SQL, no GitLab call, deadline-exempt (`GITLAB_LOCAL_STREAMS`).
  **Job orders:** SYNC ends `code_process` → `code_profile`; RECONCILE ends `code_process` (a tombstone or refilled stat is mirrored in the same job,
  never PROFILE); REPROCESS re-flags everything then runs both under a plain `StreamContext` (no tally, no token probe). **`code_process`**: (1) the
  *fingerprints* - one `sync_cursors` row holds `{exclusionsSha256, identityMapSha256, usernameMapSha256}`; a different exclusion list or
  address map re-flags every COMMIT, a different username map the merge requests too, stored in the same transaction (an absent or undecodable row
  re-flags all), so an operator's change, a release that changes the defaults and an address or user added later all take effect at the next run
  with no refetch, and **Reprocess** applies one at once; (2) the *reference rows* are rebuilt wholesale (projects; identities: one USER row per raw
  user, a ghost USER row per user id the facts name without a users row, one EMAIL row per live author address that matches no user, named by its newest
  commit); (3) *commits* then *merge requests* in keyset pages of 50, ONE transaction per page, rows read `FOR NO KEY UPDATE`. A failing page is
  bounded (a row fault - the normalizer throws, a stored value cannot be decoded, SQLSTATE 22/23 - is counted, its rows stay flagged and the
  pass continues; more than 3 failed pages in a run fails the job; any other failure propagates at once) and every failure leaves as stage +
  raw keys + the cause's class, never a bound value; (4) when the run wrote rows into `norm.code_commits` / `code_merge_requests` / `code_mr_events`, a bounded `ANALYZE` of those three in its own transaction when a table was never analyzed, the accumulated changes since the last ANALYZE reach 10 % of the rows, or this connection is absent from `connection_id`'s most common values (`CodeActivityStore.analyzeAfterProcess`: `lock_timeout` 5 s, 30 s statement budget, a failure is one WARN; progress `normAnalyzed` / `normAnalyzeMs` / `normAnalyzeReason`, absent when it did not run; `code_projects` / `code_identities` are small and left to autovacuum) - without it a second source's PROFILE event count planned its freshly written rows at one row (build-times WHY 60, `ingestion.md` "The closing `ANALYZE`"). **Counted lines** (`countLines`): `files_counted` is the stored files no glob matches
  (**distinct stored paths, not `/diff` entries** - a delete plus a re-add of one path is one stored file, so the stub oracle's `counted.files` can
  exceed it by those pairs while the lines agree), the additions/deletions of the included files with known lines plus the `too_large` remainder (above);
  a merge commit, a pending or unavailable diff leaves them null (unknown, never zero); a `files_capped` commit counts its 1,000 stored files and
  gets no remainder. **Identity:** see "Identity". **Events:** NOTE actions COMMENT / DIFF_COMMENT / APPROVAL / CHANGES_REQUESTED / REVIEW_REQUESTED
  map to the same-named kind (targets resolved to `u<id>`, unresolved dropped), a legacy APPROVAL row only when no NOTE approval by the same actor
  exists, a `/reviewers` REVIEWED entry becomes REVIEWED at the entry's `created_at` (when the reviewer was added: approximate), REQUESTED_CHANGES
  becomes CHANGES_REQUESTED unless a note already says so, everything else is no event; ordered by time (nulls last), kind, actor, raw source;
  only a READ merge request carries events (`details_state` PENDING / UNREADABLE = none). **Issue keys** (V46, version 2): a merge request's page write also
  REPLACES its `norm.code_mr_issue_keys` rows from its title and source branch whatever its details state or tombstone ("Issue keys"); progress
  `issueKeysStored` (the key rows the committed merge-request pages wrote). **PROFILE** stores the `gitlab` section of the data
  profile (`ingestion.md` "Data profile"). **PURGE** drains the raw tables, then the code layer (issue keys, events, merge requests, commits in batches; identities
  and projects outright); the profile column stays. Progress keys: `ingestion.md` "Progress counters".

## Path exclusions

A per-data-source glob list, `gitlab.pathExclusions` in the settings JSONB, edited on the data source (on the wire since PR 7, applied by PROCESS since PR 10). **Wire and storage:** the request's `pathExclusions` is nullable;
omitted on create stores null = "follow the built-in defaults" (so an untouched source follows a release that changes the list), omitted on
update keeps the stored value, a list replaces it (an empty list = exclude nothing, not the defaults). The response always carries the
EFFECTIVE list (`effectivePathExclusions`), and `GET /api/v1/data-sources/kinds` carries `defaultPathExclusions` per kind (GitLab: the 14
below in this order, Jira: empty) for the editor's prefill and "Reset to defaults". **Validation** (a `400`, after the ADMIN guard): at most
100 patterns, each 1-255 characters after `sanitizeSingleLine`, de-duplicated with the order kept, never lowercased (git paths are
case-sensitive), each compiling with `infra/validation/PathGlob`. **Glob semantics** (`PathGlob`): gitignore-like - a pattern without `/`
matches the file name at any depth, one with `/` the whole path; `**` crosses `/`, `*` and `?` stay inside a segment, `[...]` is a class. The edit
rides `data_source.updated` (host only; the patterns are never logged). Defaults:
`package-lock.json`, `yarn.lock`, `pnpm-lock.yaml`, `go.sum`, `Cargo.lock`, `Gemfile.lock`, `poetry.lock`, `composer.lock`,
`*.min.js`, `*.min.css`, `**/vendor/**`, `**/node_modules/**`, `**/dist/**`, `**/generated/**`. Applied at PROCESS, never at
fetch, so a change never refetches: PROCESS compares the list's fingerprint with the stored one and re-flags every commit at the next run (Reprocess applies it at once). Each `norm` commit row carries `files_counted`,
`additions_counted` and `deletions_counted` beside the raw totals; for a `too_large` file its lines are the commit's `stats`
minus the RAW lines of its other files (excluded ones included, so a lock file beside it is not credited to it), and the
file then counts unless it is itself excluded (the stub's `special.tooLarge` pins both figures). A `files_capped` commit gets NO such remainder (its `stats` include
files that were never stored): an included unknown-lines file leaves its counted lines null, otherwise they are the sum of the known included lines over
the stored files (U2). MR line counts (GraphQL totals) are not exclusion-aware.

## Identity

- **Keys** in `norm.code_identities`: `u<gitlabUserId>`, or `e<sha256(canonical email)[0:32]>` for a commit email that
  matches no GitLab user. A key holds no email, but the `e` key is an UNSALTED sha256 prefix of the canonical address: not an address, yet an address can be CONFIRMED by guessing it; keys stay out of logs anyway.
  **Resolution** of a commit's `author_email`: the canonical address is one of a user's `emails` (a LIVE user wins over a deleted one that held the
  same address, then the lowest id), else the noreply pattern (`<id>-<username>@users.noreply.<host>`, the id is in the local part), else the `e` key. A
  review-request note's `@username` resolves the same way (live first). PROCESS fingerprints both maps (and the exclusion list) in its cursor row and
  re-flags what depends on them when one changes.
- **Links** (V45, `public.code_identity_links`; `persistence.md` "Code identity links (V45)", `identities/CodeIdentityLinkStore.kt`): primary key
  `(source_host, identity_key)`; `decision` LINKED / IGNORED / BOT; `jira_account_id` non-null exactly when LINKED (SQL CHECKs also pin the key and the host
  shapes, so a malformed row is impossible whatever writes it); `decided_by_user_id` (an id only: `identities/` never imports `flowusers/`) and `decided_at`.
  Undated (team attribution stays dated through `metrics.team_membership`), HARD-deleted (the deletable action is "this decision was wrong"; the audit trail
  carries the removal). **Host-keyed, no connection column:** `source_host` is `sourceHostOf(canonical base URL)` - the lowercase authority, plus `:port`
  when the URL names one (`gitlab.example.com`; `127.0.0.1:<port>` for the in-JVM stub, `gitlab-stub:8080` in compose) - read per request from the path's
  data source, never from a body. So a decision survives a PROCESS rebuild, a PURGE, a data-source delete and a re-created source; no PURGE step is registered
  (`JobHandlerRegistry` untouched). A decision whose identity no ACTIVE data source on the host still has is an **orphan**: it stays listed (`seen: false`) on
  the decided tabs of the host's sources and is removable there; an orphan is per INSTANCE, so a key another active source on the host still has belongs to
  that source's page (`CodeActivityStore.identityKeysOf`) and is not listed on this one.
- **Proposals** are computed at read time and never stored (`CodeActivityStore.proposalMatches` + the pure `proposalFor`). One statement with only the
  connection id in its text matches an identity's canonical `emails` against `norm.people.email`, which is stored RAW and so folded in SQL
  (`LOWER(BTRIM(...))`); the people are those of ACTIVE connections, the person's own `active` flag ignored (a departed person keeps their history), and the
  same account id on two connections is one match. The rule: a `bot: true` USER is proposed as BOT (the flag wins over any address); otherwise exactly ONE
  distinct matched account is proposed as LINKED to it; several accounts (a mailbox two Jira accounts share: ambiguous, never "the first") or none is no
  proposal. Ghosts and deleted users follow the same rule. Only admin decisions are stored, and only a confirmed LINKED decision attributes anything.
- **Status** is a read-time join, never a column: a stored LINKED decision is `LINKED`; a stored IGNORED or BOT one is `IGNORED_OR_BOT`; no decision and a
  proposal is `PROPOSED`; anything else is `UNMAPPED`. A stored decision beats any proposal (the row keeps its `proposal` for the page to show).
- **Routes** (`identities/CodeIdentityRoutes.kt`, ADMIN only: `requireAdmin` is the first statement of every handler, so a non-admin gets one uniform `403`;
  then `404` data source, `409` not a GitLab source, `400` key / query / body, `404` identity or decision, `400` unknown Jira account; `authorization.md`):
  - `GET /api/v1/data-sources/{id}/code-identities?status=&q=&page=&pageSize=&sort=` - the connection's identities, filtered, sorted (`displayName` by default,
    `username`, `id` = the identity key; absent names last) and paged **in memory** behind the standard parser and envelope (`list-endpoints.md`: `status` is a
    join of three stores over one bounded set). `q` (at most 100 characters) is case- and accent-insensitive over the name, username and every address,
    never the key and never an orphan. **Full GitLab addresses are returned (`emails`), to ADMIN only** (a masked address cannot be judged); the server
    never places a stored address in a URL, a log line or an audit field.
  - `PUT …/{identityKey}` `{decision, jiraAccountId?}` - LINKED needs an account, IGNORED / BOT forbid one (`400`). The identity must exist in THIS connection
    (`404`), unless a decision is already stored for the key (an orphan may be changed). A LINKED account must be a person of an active connection (`400`
    "Unknown Jira account id"), unless the stored link already names it (a re-PUT is accepted after the person left Jira). One transaction: read the row
    `FOR UPDATE`, else `insertIfAbsent`, and on a lost race read the winner under lock and `update`. An identical re-PUT is a `200` that writes and audits
    nothing; the answer is the row as the list would now show it.
  - `DELETE …/{identityKey}` - `204`; `404` when no decision is stored (the row the audit names comes from `DELETE … RETURNING`).
  - `POST …/confirm-proposals` (no body) - writes a LINKED link per email proposal and a BOT link per bot proposal for identities with NO stored decision,
    `insertIgnoringConflicts` in `identityRows` key order (concurrent confirms cannot deadlock); `200 {linked, bots}` counts only the rows ACTUALLY written (a
    decision stored meanwhile wins and is not counted), and a second call writes nothing.
- **Audit** (`observability.md`): `code_identity.decided` (`via` `PUT` or `CONFIRM_PROPOSALS`, ONE event per link written; a change names `decisionFrom` /
  `jiraAccountIdFrom`, the values actually overwritten) and `code_identity.decision_removed`. Ids, keys, the host and decisions only - never an address, a
  display name or a username.
- **`identities/IdentityResolver`** (`IdentityResolverKey`, published in EVERY role): `accountsByKey(host, keys)` and `keysByAccount(host, accountIds)`, one
  `IN` statement each, **LINKED decisions only** - IGNORED, BOT and unconfirmed proposals attribute nothing; `decidedKeys(host, keys)` answers every stored decision (LINKED, IGNORED or BOT) so Report 21 can tell a BOT or IGNORED actor from an undecided human reviewer. It answers Jira account ids, not teams: the team
  at an instant is the one rule in `metrics/` (`teamAt`), which `identities/` must not import (`identities` imports `infra`, `norm`, `ingest`, `authz`,
  `audit`, `plugins`; nothing but `reports/` references it: `PackageBoundaryTest` "only reports references identities"). It is the one entry point for the reports.
- **Tests:** `CodeIdentityProposalsTest` (the pure kernel: proposal, status, search, sort, page), `CodeIdentityProposalMatchesTest` (the SQL matches),
  `CodeIdentityLinkStoreTest` (the store's outcomes and the CHECKs), `CodeIdentityServiceTest` (direct rows: idempotence, the unknown-account exemption, the
  bulk counts, orphans, and the two insert races pinned with a raw transaction that holds a decision INSERT uncommitted), `IdentityResolverTest`, and
  `CodeIdentityRoutesTest` (the 403-first matrix, 404 / 409 on all four operations, the alignment oracle against `expected.json`: nine identities = 7 proposed
  (6 linked proposals and the bot) + 2 unmapped, the PUT / DELETE / confirm semantics and audits, per-instance orphans, a re-created source, the worker role).

## Issue keys

The first step of linking merge requests to Jira tasks (the ask: a merge request names the task it implements in its title or branch). **Stored at PROCESS,
scoped at read time.** `norm/IssueKeys.kt` (`extractIssueKeyCandidates(title, sourceBranch)`, pure, connector-agnostic; `IssueKeysTest`) reads each merge
request's TITLE and SOURCE branch (the target branch names where code goes, never what it is for; commits are linked through their merge request, never
by their own title) and `GitLabNormalizer.codeMergeRequest` hands the result to the writer, which stores one `norm.code_mr_issue_keys` row per candidate
(V46, `persistence.md` "Merge request issue keys (V46)").

- **The token rule.** A project part `[A-Za-z][A-Za-z0-9_]{1,9}`, a hyphen, a number `[1-9][0-9]{0,6}`, bounded on both sides by a non-alphanumeric character
  or the text's end. So `[FLO-12]`, `FLO-12:`, `(FLO-12)` and `feature/flo-12-fix` name `FLO-12`; `FLO-12a`, `FLO-012` and `2FLO-12` name nothing. Only ASCII
  letters and digits bind (a non-ASCII neighbour is a boundary). Folded to UPPERCASE (branches are lowercased by tooling), at most 20 characters (the
  `issue_key` width; the rule's longest key is 18), distinct per merge request, sorted. **The underscore is a boundary:** `flo_12` is no key, `FLO-12_fix`
  names `FLO-12`, and a key after an underscore is found. A Jira project key may itself contain `_`, so the token keeps its inner underscores AND every
  underscore-delimited suffix that is a valid project part is a candidate of its own (`fix_FLO-12` stores `FIX_FLO-12` and `FLO-12`; `MY_PROJ-1` stores
  `MY_PROJ-1` and `PROJ-1`); the read-time scope keeps whichever is a known project.
- **Every candidate is stored, junk included.** `UTF-8`, `SHA-256`, `RELEASE-2024` and a `v2-3` branch slug are candidates by design: PROCESS has no knowledge of
  Jira ("facts only, no interpretation"), a newly configured Jira project links retroactively with no re-flag, and the junk is one small row that never reaches a
  surface. `in_title` / `in_branch` keep where the key was found (a key in both is ONE row with both flags).
- **Read-time scope (the drill, 1.46.0 - `reports/IssueKeyScope.kt`; the task-side resolution is Report 22's, 1.47.0 - `reports/TaskCodeReads.kt`, `reports.md` "Report 22").** A candidate becomes a link only when its project part is a KNOWN Jira project key (the `projectKeys` of every
  active Jira source, plus the prefixes of the `issuekey` changes of those sources: the old projects of moved issues) and it resolves to an issue by
  `norm.work_items.issue_key` or an old key in a `norm.work_item_field_changes` `issuekey` row (`idx_norm_work_items_issue_key`, V46). A key that resolves to
  several issues across connections is ambiguous and linked to none. Only LIVE merge requests link (`deleted_at IS NULL`); a tombstoned one keeps its rows. **Report 21's drill (1.46.0) applies the project scope only:** it lists a key of a known project even when no issue has that key (a link; Jira answers 404), and resolution (existing, moved, ambiguous) is the task-side report's. **Report 22 (1.47.0) resolves**: a candidate links to a done task only when it names exactly ONE live issue (current key or an `issuekey` old key) among every active Jira source, reading task -> merge request by the V46 `(connection_id, issue_key)` index; `quality.tasksWithAmbiguousKey` counts the done tasks whose key is ambiguous.
- **Data profile (1.45.0).** The profile's `mergeRequests` carries `withIssueKeyCandidates` (live merge requests naming at least one candidate) and `issueKeyCandidates` (their distinct rows; a tombstoned merge request keeps its rows but is not counted). They are CANDIDATES, not links - the page labels say "not yet matched to tasks" - and `GitLabProfileTest` pins them to the stub oracle.
- **The stub.** `sample-data/gitlab/lib/keycases.mjs` hand-writes ten edge cases (two keys, title and branch, lowercase branch only, `FLO-366`, `UTF-8` and `V2-3` junk, an underscore branch, `ABC-12`, `SEC-5`, `FLO-900`); `expected.json` `mergeRequests.issueKeyCandidates` is the oracle, computed by an independent scanner, that `GitLabProcessStreamTest` compares the persisted rows with (`sample-data/README.md` "Issue keys").
- **Reprocess.** `CODE_PROCESSING_VERSION = 2` re-claims every commit and merge request once; the keys are REPLACED with the merge request on every later
  PROCESS of it (a renamed title or branch is followed at the next sync). A Jira project KEY RENAME writes no `issuekey` changelog, so an old key stays
  unresolved until the old prefix is seen in a change.

## Real-instance smoke run (the user's checklist)

The first run against the real self-managed instance. **Share only figures, headers and shapes, never names or hosts** (item 8). `<host>`, `<pid>`, `<iid>`,
`<id>` below are placeholders for the user's own values (`gitlab.example.internal` in the examples). Each item gives what to run and what to share back; the
main session turns every mismatch into a `[new]` BACKLOG item or a fix. The design the items test is in the sections above; the scaled-world figures to compare
against are in "Performance at scale" and `build-times.md` WHY 59.

1. **Operator setup (the compose stack on OrbStack).**
   - Turn both stubs off and allow the host: `JIRA_STUB_BASE_URL= GITLAB_STUB_BASE_URL= GITLAB_ALLOWED_HOSTS=<host[:port]> docker compose up -d --build app` (an
     explicitly EMPTY stub value, not unset), then stop both stub containers. `GITLAB_ALLOWED_CIDRS=<range>` only when `dig +short <host>` answers a private address
     (the refused ranges: README "Configuration").
   - An internal CA: `openssl s_client -connect <host>:443 -servername <host> </dev/null 2>/dev/null | openssl x509 -noout -issuer`. If the issuer is not a public CA,
     set `GITLAB_CA_BUNDLE=/etc/flow/gitlab-ca.pem` and mount the PEM read-only through a compose override (no skip-TLS switch exists).
   - A malformed host, CIDR or CA refuses startup: `docker logs flow-app`. Check `GET /api/v1/data-sources/kinds` lists GITLAB enabled with the origin. On
     Kubernetes later: an egress NetworkPolicy rule for the private range.
2. **The bot user and its token.**
   - A dedicated user (for example `flow-sync`), **Admin** (so `/users/:id/emails` and `email` are visible; with Admin Mode on, the token also needs `admin_mode`), and
     a personal access token with **exactly `read_api`** (any other scope is `TOKEN_SCOPE_TOO_BROAD`), an expiry per policy.
   - Verify before Flow sees it: `curl -s -H "PRIVATE-TOKEN: $T" https://<host>/api/v4/personal_access_tokens/self | jq '{scopes,active}'`; `.../api/v4/user | jq .is_admin`;
     `.../api/v4/users/<own id>/emails` answers 200.
   - In Flow: Data sources, New, GitLab, base URL `https://<host>`, the full group paths. **Test connection**: every REQUIRED row ok, and the OPTIONAL `user_admin`
     and `user_emails` rows too (a failure there leaves every human Unmapped). Share: which rows passed.
3. **One small group first** (2-5 projects, a few hundred commits), the default 24-month `backfillFrom`.
   - Sync now; follow `GET /api/v1/data-sources/{id}/status` (`currentJob.currentStream`, `progress`) or the page. The first run ends with `commitFilesPending` /
     `mrDetailsPending` (5,000 / 3,000 calls a run): the next scheduled runs drain them, or press Sync now again.
   - Watch for `TOKEN_SCOPE_TOO_BROAD`, `commitsProjectsSkipped`, `mrSkipped`, `mrGroupsNotFound`, `mrDiffStatsGaps`, `mrGraphqlBreaker`, `usersEmailsSkipped`, and a FAILED
     job with `INVALID_RESPONSE` (one malformed merge request fails its listing page: the known risk; share the `error_detail` with every path and title redacted).
   - Share: the job's wall time, its call volume (the `pages` and per-stream counters), `counts.gitlab`, the `gitlab` section of the data profile. The per-call cost
     is the figure to compare with WHY 59's ~12 ms (the stub's) - a real instance's latency decides the backfill.
4. **Compare two or three projects** (counts only).
   - Commits, on an up-to-date clone: `git fetch origin && git rev-list --count --no-merges --since=<backfillFrom>T00:00:00Z origin/<default>` (and `--merges`) against
     `docker compose exec postgres psql -U flow -d flow -c "select p.path_with_namespace, count(*) filter (where not c.is_merge) as non_merge, count(*) filter (where c.is_merge) as merges from norm.code_commits c join norm.code_projects p using (connection_id, project_id) where c.connection_id=<id> and c.deleted_at is null group by 1 order by 1"`
     (both filter by COMMITTER date: GitLab's `since` is git's `--since`). A difference of a few commits is the `since` early-stop caveat: press Reconcile now (the
     `since`-less walk) and compare again; a gap that stays is a bug (also the residual of RECONCILE's 14-day stop: a commit skewed by more than that and last on a page). Share the two counts and the project's size.
   - Merge requests: `curl -sI -H "PRIVATE-TOKEN: $T" "https://<host>/api/v4/projects/<pid>/merge_requests?state=all&updated_after=<backfillFrom>T00:00:00Z&per_page=1" | grep -i x-total`
     against `select state, count(*) from raw.gitlab_merge_requests where connection_id=<id> and project_id=<pid> and deleted_at is null group by 1`. The UI's Open / Merged /
     Closed tabs count all time, so use them only to spot-check two or three merge requests by iid.
   - Approvals and reviews, for two or three merged merge requests: the UI's "Approved by" list and thread count against
     `select kind, count(*) from norm.code_mr_events e join norm.code_merge_requests m using (connection_id, mr_id) where m.connection_id=<id> and m.project_id=<pid> and m.iid=<iid> group by 1`.
     An approve / unapprove / approve is two APPROVAL events by design. `REVIEWED`'s time is the reviewer entry's `created_at` (when the reviewer was added: approximate): note whether the UI agrees.
5. **Confirm the API shapes on their version** (one `curl -s -H "PRIVATE-TOKEN: $T"` each; share the shape or header, never ids or paths). The stub models these from knowledge of the API, not from a capture:
   - `GET /projects/<id>/repository/commits?ref_name=<sha>..<branch>&with_stats=true&per_page=100` answers 200 with `X-Next-Page` present (empty on the last page) and `X-Total`; commit dates print with the committer's own offset (`+02:00`, `+00:00` for UTC); `Link` carries route params (`id`, `sha`; Flow never reads it);
   - `.../repository/commits?ref_name=<unknown>` answers 404 or `[]` (the stub 404s);
   - `.../repository/merge_base?refs[]=<sha of a force-pushed-away commit>&refs[]=<branch>` answers 200 with the fork point, not an error;
   - `.../repository/commits/<big sha>/diff?per_page=100&page=2` pages; a huge or generated file carries `too_large` / `collapsed` / `generated_file`;
   - `.../merge_requests?updated_after=<a merge request's exact updated_at>` includes that merge request (inclusive); `non_archived` defaults to true (an archived project's merge requests are absent from the group listing); `/groups/<path>` answers the configured path's case;
   - `.../merge_requests/<iid>/approvals` (the legacy object) and `.../reviewers` (the `state` values seen: `unreviewed` / `reviewed` / `requested_changes` / `approved`; anything else is stored `OTHER_STATE`);
   - `POST /api/graphql` with the 50-iid `mergeRequests(iids:[String!]) { nodes { iid diffStatsSummary { additions deletions fileCount } } }` query: `iid` as a string and no complexity error; the shape of an `errors[]` entry if one appears;
   - `curl -sI .../version`: `RateLimit-Remaining` and `RateLimit-Reset` (epoch seconds), or their absence when rate limiting is off;
   - `GET /users?per_page=1` shows `email` (visible emails) with this token;
   - RECONCILE's assumptions: `GET /projects/<an id that does not exist>` answers 404 (a deletion mark relies on it); for a project with merge requests switched off, `GET /projects/<id>` answers 200 while `.../merge_requests/<iid>` answers 404 (those merge requests ARE tombstoned, recoverable);
   - the distinct system-note phrasings over a busy merge request's `/notes`: `jq -r '.[] | select(.system) | .body' | sed 's/@[A-Za-z0-9_.-]*/@user/g' | sort -u`. Everything `GitLabMrNotes.kt` does not match lands as `OTHER_SYSTEM`: share the stripped phrasings;
   - which review actions bump a merge request's `updated_at`: approve one, then `GET` it. If `updated_at` did not move, approvals are invisible to the incremental listing until RECONCILE re-reads the merge request.
6. **Identity mapping review.** Data source, Identities. Proposed = humans whose GitLab address equals a Jira address (secondary emails need the admin token); confirm the
   proposals. Look at Unmapped for people with a different address (link by hand) and for a shared mailbox (never proposed); bots on the Ignored / bots tab. Share: the
   count per tab, `identities.withEmailPercent`, `ghosts`, `emailOnly`, `commitsUnmatched`.
7. **The next day.** RECONCILE's job: wall time, `projectsProbed`, `mergeRequestsDeleted` and `commitsTombstoned` (0 unless a deletion is real), and the call volume against WHY 59's
   (~236 calls and ~4 s at 15k commits / 3k merge requests on the stub); the daily SYNC's `pages` (the incremental cost).
8. **Share only figures, headers and shapes** - never the host, group or project paths, names, usernames, addresses, titles or `error_detail` texts (they can embed paths).
   Strip `@names` and ids from anything pasted.

## Schema (V40–V46)

- **V40** — `source_connections.kind` CHECK widened to `('JIRA_CLOUD','GITLAB')` (V16's constraint-swap pattern).
- **V41** (landed, PR 6; `persistence.md` "The GitLab raw store (V41)") — `raw.gitlab_projects` (`commits_head_sha`, relist flag, seen/moved/deleted markers, slim payload + sha256),
  `raw.gitlab_users` (`emails` JSONB); `GitLabRawStore` is the facade over `GitLabRawWrites`/`Reads`/`Purge`.
- **V42** (landed, PR 7; `persistence.md` "The GitLab commits raw store (V42)") — `raw.gitlab_commits` (author name/email, authored/committed time,
  parent count, title, stats, the three-state files model: `files_fetched_at IS NULL` = pending, terminal `files_error` (`LIMIT_EXCEEDED`/`NOT_FOUND`)
  with `files_changed` null, complete otherwise; `files_capped`, tombstone, processing columns, partial indexes for the files backlog and PROCESS),
  `raw.gitlab_commit_files` (cascades with its commit; paths cut to 1,024 characters and 2,000 bytes). The ADMIN status view
  (`GET /api/v1/data-sources/{id}/status`, `counts.gitlab`) carries `commits`, `commitsMerge`, `commitsDeleted` and `commitFilesPending`, and (V43) `mergeRequests`, `mergeRequestsMerged`, `mrEvents`, `mrDetailsPending`, `mrDiffStatsMissing` and (PR 9) `mergeRequestsDeleted` and `mrDetailsUnread` (live merge requests of live projects with a `details_error` stamp) (counts only; the pending figure reuses the stream's backlog predicate).
- **V43** — `raw.gitlab_merge_requests` (details watermark, tombstone `deleted_at` set by RECONCILE only), `raw.gitlab_mr_events` (NOTE / APPROVAL / REVIEWER).
- **V44** (landed, PR 10; `persistence.md` "The normalized code layer (V44)") — `norm.code_projects`, `code_identities`, `code_commits`, `code_merge_requests`, `code_mr_events` (COMMENT /
  DIFF_COMMENT / APPROVAL / REVIEW_REQUESTED / REVIEWED / CHANGES_REQUESTED); `CodeActivityStore` is the facade, FK-free between norm tables, `_counted` null = unknown.
- **V45** (landed, PR 11; `persistence.md` "Code identity links (V45)") — `public.code_identity_links`: the admin decisions, keyed `(source_host, identity_key)`, no connection column, hard-deleted.
- **V46** (landed, linking PR 1; `persistence.md` "Merge request issue keys (V46)") — `norm.code_mr_issue_keys` `(connection_id, mr_id, issue_key, in_title, in_branch)`, FK-free to the merge request,
  replaced with it and drained first by PURGE ("Issue keys"); plus `idx_norm_work_items_issue_key (connection_id, issue_key)` on `norm.work_items` for the key -> issue resolution.

Each lands with its `MigrationChecksumTest` pin and its `persistence.md` section; new cross-feature reads go on that doc's
permission list.

## Stub (as built, PR 5)

`sample-data/gitlab/generate.mjs` (zero dependencies, seeded, byte-identical; `--users/--projects/--months/--out`) writes
`sample-data/gitlab-stub/{mappings,__files}` (1,300 mappings, ~11 MiB) and `sample-data/gitlab/expected.json`. It is served by the
compose `gitlab-stub` (WireMock, `127.0.0.1:8095`, read-only mount; `GITLAB_STUB_BASE_URL` points the app at it) and the in-JVM
`GitLabStubServer` (`server/src/test/kotlin/`, `JiraStubServer`'s twin); `GitLabStubServerTest` pins the contract over plain HTTP and
`node --test sample-data/gitlab/generate.test.mjs` (in `scripts/gates.sh e2e` and the CI `e2e-static` job) guards the generator.
The full contract (every endpoint and its match rule, auth, pagination headers, 404 shapes, the day-2 deltas, the scale flags) is
`sample-data/README.md` "GitLab stub"; the figures tests assert are in `expected.json`. PR 9 added `GET /projects/:id/merge_requests/:iid` (the listing's object; a deleted or not-yet-existing merge request falls to the 404 catch-all): RECONCILE's confirmation probe.

The world: host `gitlab.flow-sample.example`, GitLab `19.3.1`; groups `platform` (7) with subgroup `platform/tooling` (8), `apps` (9),
and an out-of-scope `sandbox` (12); 4 in-scope projects (`gateway`, `build-kit`, `storefront`, `ledger` with default branch `master`),
1 archived, 1 out of scope; the configured groups are 7 and 9. 8 users: an admin (the token's owner), 6 humans whose emails are the
Jira stub's `user<n>@flow-sample.example` (one through a confirmed secondary email, one deactivated, two also committing with a
GitLab noreply address), and a project bot (`bot: true`); one commit author matches no user. 300 default-branch commits (19 merge
commits, rebased and clock-skewed dates, `stats`, lock/vendored/generated/minified/binary/renamed files, one 1,040-file commit, one
`too_large` and one `collapsed` file), 60 merge requests (34 merged, 16 opened, 8 closed, 2 locked) with notes, `DiffNote`s, system
notes, approvals and reviewer states. The `gitlab-day2` scenario: a force-push, `master` to `main`, a deleted MR, a project moved
out, a new project, new commits and MR updates.

The shapes the stub models from knowledge of the API rather than a capture (diff flags, the GraphQL `iid` and `errors[]`, `ref_name=<unknown>`, `X-Total`, commit-date offsets, the legacy `/approvals` object, reviewer `state`, `updated_after`, `non_archived`, which actions bump `updated_at`, the GraphQL complexity limit, the system-note phrasings) are confirmed one by one by the real-instance smoke run, item 5.

**The `too_large` fallback.** The design formula is the commit's `stats` minus the RAW lines of its other files (see "Path exclusions"),
so a lock file changed beside the too-large file is not credited to it. The stub's too-large commit changes a lock file on purpose;
`expected.json` `special.tooLarge.fallbackByDesign` is the design figure (the true lines of the file) and
`fallbackSubtractingCountedOthersSuperseded` the earlier "minus the other files' *counted* lines" figure, which over-attributes the
excluded files' lines and is kept only for comparison.

`GitLabReconcileTest` (fake client) and `GitLabReconcileStreamsTest` (the stub, own connections only, no shared fixture: the Started state is PROJECTS + COMMITS + MERGE REQUESTS through `runStream`) cover RECONCILE. PR 6's `GitLabConnectorTest` runs its own SYNC against the in-JVM stub (`GitLabFixtures`, `GitLabTestSupport.kt`: a disabled connection per test,
`stubClient()`, `run(...)`, `freshContext(...)`/`runStream(...)`). **`SyncedGitLabFixture` (PR 7, extended in PR 8)** is the one full SYNC per fork (all six streams, in order;
printed as `fixture-cost gitlab-sync`, `build-times.md` WHY 55) that `GitLabCommitsStreamTest` and `GitLabMrStreamsTest` read: a class warms it in a `@BeforeClass` through its own
`warmGitLabFixture()` (`StubFixtureWarmupGuardTest` enforces the pair, so the Jira-fixture classes do not pay for it), its tripwire is 300 live commits, 60 live merge requests and no pending
commit files or merge-request details, and it is soft-deleted after the SYNC so that its connection does not hold the ONE-instance scope rule against `GitLabScopeTest`'s other-instance creates. A test
that mutates (a second SYNC, the day-2 scenario, a head or budget change) makes its own connection. The sweep's `merge_base(<commit>, <branch>)` answers for the
force-pushed project's old commits and its divergence commit are in the stub too (`p1-merge-base-candidate-*`). The stub's `RateLimit-*` headers are static (`Remaining: 1990`), so no test run pauses. The day-2-only merge request (project 7) carries id 41060: the generator once left it without an `id`, which the first consumer of the group listing (PR 8's client) rightly refused.

**PR 10 tests.** The fixture's SYNC now ends with PROCESS and PROFILE, so it also carries `norm.code_*` rows, the `code_process` cursor and a stored profile, and its tripwire adds 300 live norm commits, 60 live norm merge requests and no flagged rows. `GitLabProcessStreamTest` pins the code layer against the stub's independent oracle (every commit's counted lines and the identity counts per key; events; the REPROCESS = incremental digest, `GitLabFixtures.codeDigest`; tombstones and refills; the exclusion, address and username fingerprints; poisoned pages; the page lock; progress and heartbeats), `GitLabProfileTest` the profile section (computed and over HTTP), `GitLabPurgeTest` the PURGE drains, `GitLabRawPayloadWhitelistTest` the privacy whitelist of all twelve GitLab tables (exact columns, slim payload keys, no stub description / note body / commit message body / URL stored), `GitLabNormalizerTest` and `CodeNormalizationTest` the pure rules. Every mutating test clones its own connection (`SyncedGitLabFixture.cloneRawData` / `cloneProcessedData`, over `CloneSupport`).

### Performance at scale (PR 12)

Measured on the isolated `flow-perf` stack (`docker-compose.perf.yaml`: the production role split, a `gitlab-stub` override) against `generate.mjs --users 70 --projects 200 --months 26`: **15,077 commits,
3,015 merge requests, 31,356 raw events, 72 identities**, 48.7k WireMock mappings (ready after about 45 s, 0.5-1.1 GiB). The rig, the per-stream table and the caveats are `build-times.md` WHY 59; the headline figures:

- **A full backfill** (one SYNC, both per-run budgets lifted): **283 s**, about 23.6k calls at **about 12 ms each, the stub's own time included**. COMMIT FILES (157 s, about 14.2k diff calls) and MR DETAILS (115 s, about 9.1k calls) are 96 % of it. PROCESS + PROFILE
  about 4.7 s; REPROCESS 4.3 s; **RECONCILE 3.8-4.6 s, about 236 calls**.
- **Latency-bound by design.** A GitLab job holds ONE permit and its calls are sequential, so a backlog stream's wall time is its call count times the per-call latency. A fetch-ahead inside a claimed batch (WORKLOGS' shape, `jira.maxConcurrentRequests` calls in flight) could cut the
  two backlogs several times over (an estimate, not measured), but adds response trees in flight that the heap fit's one-tree-per-slot assumption does not cover; it is queued, not built (`BACKLOG.md`).
- **The worker beside a Jira job** (a scale-20 Jira SYNC + DERIVE and a second GitLab SYNC at once, `-Xmx512m`): old generation peak **86 MiB of 448**, container peak 312 of 768 MiB, nothing killed; the Jira SYNC +14 %, the GitLab SYNC +15 %. The GitLab side adds almost nothing to the heap.
- **Storage:** about 2.7 kB a commit and 9.1 kB a merge request (events included); about 68 MB for this world, about 38 MB compacted.
- **The identities page** (PR 11's in-memory paging, 72 identities): p50 4-5 ms, p95 4-8 ms in every case.
- **Derived, not measured, to 60k commits / 20k merge requests:** about 118k calls, about 24 min at 12 ms a call and about 20 min more per extra 10 ms of latency; RECONCILE about 720 calls and 12 s; about 340 MB a source.
- **Open:** two slow statements, a Jira DERIVE worklog statement (35.8 s for the second of two connections against 477 ms for the first) and the GitLab PROFILE's event count (2.4 s), both with an unconfirmed stale-statistics hypothesis; each is a `[new]` item in `BACKLOG.md`.
- **The stub ignores `since` and `updated_after`**, so the 24-month floor cut nothing in this run.

## PR sequence

| # | PR | Version | Review |
|---|---|---|---|
| 1 | this doc | — | — |
| 2 | `fetch/` lift, no behaviour change | — | Opus |
| 3 | connector seam + V40 + kind-leak guards | — | Opus |
| 4 | self-managed outbound guard | — | security-auditor + Opus |
| 5 | GitLab stub | — | Sonnet |
| 6 | data source + PROJECTS/USERS (V41), web kind picker (landed) | 1.34.0 | Opus (outbound use) |
| 7 | commits + per-file counting + exclusions (V42) | 1.35.0 | Opus (migration) |
| 8 | merge requests + details (V43) | 1.36.0 | Opus (migration) |
| 9 | RECONCILE (landed) | 1.36.1 | Opus |
| 10 | `norm.code_*` + PURGE + profile (V44) (landed) | 1.37.0 | Opus (migration) |
| 11 | identity mapping (V45, `identities/`) (landed) | 1.38.0 | Opus + authorization |
| 12 | scaled-world performance run (`build-times.md` WHY 59) + real-instance smoke checklist (landed) | — | Sonnet |

## Risks and open points

- **A malformed merge request fails its listing page:** one item without a numeric `id`/`iid`/`project_id`, a `state` or readable timestamps makes the whole
  page `INVALID_RESPONSE`, which fails the MERGE REQUESTS stream and the streams after it (COMMIT FILES, MR DETAILS) for that run. Deliberate (a silent skip
  would hide a contract change), but a single bad item on a real instance would stall the connection until fixed.

- **First backfill:** measured at 15k commits / 3k merge requests: about 23.6k calls (about 14.2k diffs, about 9.1k merge-request reads) in 283 s, about 12 ms a call with the stub's own time, one permit and sequential
  (`build-times.md` WHY 59). Derived to 60k / 20k: about 118k calls, about 24 min at that cost and about 20 min more per extra 10 ms of per-call latency, spread over about 12 (files) and about 21 (details) runs at the
  default budgets. Longer with instance rate limits on.
- **Commit ↔ MR linking:** only merge and squash SHAs are stored for now (Jira linking is through the merge request alone: "Issue keys").
- **Issue keys are candidates, not links (V46):** the junk a strict token rule still admits (`UTF-8`, a `v2-3` slug) is stored, so a surface that read `norm.code_mr_issue_keys` without the
  read-time scope would show it. A key past the 255-character title cut is lost; a key written without its project (`#12`) is not found.
- **Code layer, open points (PR 10):** MR line counts (GraphQL totals) stay exclusion-blind and `generated_file` is stored raw and not applied. `REVIEWED`'s
  time is the reviewer entry's `created_at` (approximate; the smoke run, item 4, checks it). **Report 21 counts approvals as distinct merge requests per
  person** (A50, planned): an approve / unapprove / approve sequence leaves two NOTE `APPROVAL` events by one actor (kept as history; only the legacy endpoint row is
  de-duplicated against the notes). The same report treats `REVIEWED` as an approximate first-review signal (above) and sizes a merge request exclusion-blind. **Identity rows come from LIVE commits only**: a tombstoned commit under an address no live commit uses has an `e`
  key without an identity row. A user added to GitLab re-flags every merge request (the username map is fingerprinted) and any address change every commit:
  one first-run-sized local pass, deadline-exempt. Ghost USER rows (an id with no users row, e.g. deleted before the first sync) are counted separately
  (`identities.ghosts`).
- **Identity links outlive their identity and their account (V45):** a decision whose identity no active data source on the host still has stays listed
  (as not seen) on the decided tabs of those sources and is removable there (an orphan is per INSTANCE: a key another active source on the host still has
  belongs to that source's page, not this one; `CodeIdentityService.list` reads the other sources' keys with `CodeActivityStore.identityKeysOf`); with no
  active source on the host it waits, inert, until one is created. A LINKED account that vanishes
  from Jira keeps its link, which still resolves; the list shows it with no display name, and a re-PUT of the same account is accepted.
- **Identity proposals, open points (PR 11):** a mailbox shared by two Jira accounts gets NO proposal (ambiguity is never resolved by "the first"), so that
  identity stays Unmapped until an admin links it by hand. The `e<hash>` key in a URL or an audit field is an unsalted sha256 prefix of an address: not an
  address, but confirmable by guessing one. `decided_by_user_id` names an id only (no name lookup). The list pages in memory, bounded by one connection's
  identities (the USERS stream's ceiling): a very large instance would make it slow, not wrong. On a real instance an admin token that cannot see user
  emails leaves every human Unmapped (the smoke run, item 2).
- **First-sync ordering:** `since` with out-of-order dates may stop early (see COMMITS, "RELIST and the `since` caveat"); RECONCILE's `since`-less re-list catches up (the 14-day stop is a residual, below).
- **Real-instance confirmation** is one checklist, "Real-instance smoke run" above: setup, the token, a small group, counts against git and the UI, the API shapes (including RECONCILE's assumptions and
  the residual of its 14-day stop), identity mapping and the next day. Only figures are shared back, never names or hosts.
