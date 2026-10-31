# GitLab integration (self-managed)

The `gitlab/` package and the code-activity layer around it: a self-managed GitLab instance read through its REST API (plus
one GraphQL query), stored raw in `raw.gitlab_*`, processed into the connector-agnostic `norm.code_*` tables, and tied to
the Jira people through admin-confirmed identity links. The queue, worker, cursors and PROCESS/PROFILE mechanics it plugs
into are in `.claude/docs/ingestion.md`; the Jira connector it sits beside is `.claude/docs/jira-integration.md`.

**Status: being built (1.34.0 → 1.38.0; 1.34.0 landed: data sources, PROJECTS and USERS).** Until a section's PR lands, the section is the design that PR implements; when it
lands, the section is rewritten as the as-built reference (file names, tests). The PR sequence is at the end. Reports over
`norm.code_*` are a separate, later plan: nothing here derives metrics.

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
  or note bodies. Kept: the commit's first line and the MR title (≤255 chars, for the later Jira-key linking), branch names,
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
  run continues. A GitLab job holds ONE permit at a time, so the backlog is sequential (a fetch-ahead is a PR 12 question).
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
  the job `TOKEN_SCOPE_TOO_BROAD`, an inactive one `TOKEN_INACTIVE` (`JobRefusedException`), before anything is stored. RECONCILE and REPROCESS are accepted no-ops until PR 9/10;
  PURGE drains all four raw tables. The streams run PROJECTS -> USERS -> COMMITS -> COMMIT FILES. The job runs under the shared `CallTally` (GitLab's throttling lands under the stored `jira*` progress keys).
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
  PROJECTS will rename it or RECONCILE (PR 9) delete it. Every listing page commits its rows and the cursor in one transaction; the head moves (and
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
  gap in a FULL listing from the backfill floor; RECONCILE (PR 9) is the catch-up. Progress: `pages`, `commits` (inserted), `commitsRelisted`,
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
- **MERGE REQUESTS** — group-level `/groups/:id/merge_requests?updated_after&include_subgroups`, cursor in `sync_cursors`.
- **MR DETAILS** — notes, approvals, reviewers (REST) and the GraphQL diff stats, under budgets.
- **RECONCILE** — a SHA re-list per project tombstones vanished commits; a 404 marks a project deleted; a moved project is
  marked out of scope; an archived project keeps its data; an MR id re-list tombstones deleted MRs.
- **PROCESS / PROFILE** — into `norm.code_*` under `CODE_PROCESSING_VERSION`, separate from Jira's `PROCESSING_VERSION`, so
  neither side reprocesses the other.

## Path exclusions

A per-data-source glob list, `gitlab.pathExclusions` in the settings JSONB, edited on the data source (on the wire since PR 7, **applied only
from PR 10**: until then it is stored and echoed, nothing reads it). **Wire and storage:** the request's `pathExclusions` is nullable;
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
fetch, so a change is a code-layer REPROCESS without refetching. Each `norm` commit row carries `files_counted`,
`additions_counted` and `deletions_counted` beside the raw totals; for a `too_large` file its lines are the commit's `stats`
minus the RAW lines of its other files (excluded ones included, so a lock file beside it is not credited to it), and the
file then counts unless it is itself excluded (the stub's `special.tooLarge` pins both figures). MR line counts (GraphQL totals) are not exclusion-aware.

## Identity

- **Keys** in `norm.code_identities`: `u<gitlabUserId>`, or `e<sha256(canonical email)[0:32]>` for a commit email that
  matches no GitLab user. Keys hold no email, so no PII lands in URLs or logs.
- **Links** in `public.code_identity_links (source_host, identity_key)`: `decision` LINKED / IGNORED / BOT,
  `jira_account_id` non-null exactly when LINKED, decided-by/at. Undated (team attribution stays dated through
  `metrics.team_membership`) and keyed by host, so they survive a re-created data source.
- **Proposals** are computed at read time: canonical email equal to `norm.people.email` (the `flowusers` canonical form);
  GitLab `bot: true` users are proposed as BOT. Only admin decisions are stored.
- **Routes** (ADMIN only): `GET /api/v1/data-sources/{id}/code-identities?status=&q=` (paged, `list-endpoints.md`),
  `PUT …/{identityKey}` `{decision, jiraAccountId?}`, `DELETE …/{identityKey}`, `POST …/confirm-proposals`. Audits
  `code_identity.*`, no emails.
- **`identities/IdentityResolver`** maps `(host, key)` to a Jira `account_id`: the one entry point for the later reports.

## Schema (V40–V45)

- **V40** — `source_connections.kind` CHECK widened to `('JIRA_CLOUD','GITLAB')` (V16's constraint-swap pattern).
- **V41** (landed, PR 6; `persistence.md` "The GitLab raw store (V41)") — `raw.gitlab_projects` (`commits_head_sha`, relist flag, seen/moved/deleted markers, slim payload + sha256),
  `raw.gitlab_users` (`emails` JSONB); `GitLabRawStore` is the facade over `GitLabRawWrites`/`Reads`/`Purge`.
- **V42** (landed, PR 7; `persistence.md` "The GitLab commits raw store (V42)") — `raw.gitlab_commits` (author name/email, authored/committed time,
  parent count, title, stats, the three-state files model: `files_fetched_at IS NULL` = pending, terminal `files_error` (`LIMIT_EXCEEDED`/`NOT_FOUND`)
  with `files_changed` null, complete otherwise; `files_capped`, tombstone, processing columns, partial indexes for the files backlog and PROCESS),
  `raw.gitlab_commit_files` (cascades with its commit; paths cut to 1,024 characters and 2,000 bytes). The ADMIN status view
  (`GET /api/v1/data-sources/{id}/status`, `counts.gitlab`) carries `commits`, `commitsMerge`, `commitsDeleted` and `commitFilesPending`.
- **V43** — `raw.gitlab_merge_requests` (details watermark, tombstone), `raw.gitlab_mr_events` (NOTE / APPROVAL / REVIEWER).
- **V44** — `norm.code_projects`, `code_identities`, `code_commits`, `code_merge_requests`, `code_mr_events` (COMMENT /
  DIFF_COMMENT / APPROVAL / REVIEW_REQUESTED / REVIEWED / CHANGES_REQUESTED).
- **V45** — `public.code_identity_links`.

Each lands with its `MigrationChecksumTest` pin and its `persistence.md` section; new cross-feature reads go on that doc's
permission list.

## Stub (as built, PR 5)

`sample-data/gitlab/generate.mjs` (zero dependencies, seeded, byte-identical; `--users/--projects/--months/--out`) writes
`sample-data/gitlab-stub/{mappings,__files}` (1,220 mappings, ~11 MiB) and `sample-data/gitlab/expected.json`. It is served by the
compose `gitlab-stub` (WireMock, `127.0.0.1:8095`, read-only mount; `GITLAB_STUB_BASE_URL` points the app at it) and the in-JVM
`GitLabStubServer` (`server/src/test/kotlin/`, `JiraStubServer`'s twin); `GitLabStubServerTest` pins the contract over plain HTTP and
`node --test sample-data/gitlab/generate.test.mjs` (in `scripts/gates.sh e2e` and the CI `e2e-static` job) guards the generator.
The full contract (every endpoint and its match rule, auth, pagination headers, 404 shapes, the day-2 deltas, the scale flags) is
`sample-data/README.md` "GitLab stub"; the figures tests assert are in `expected.json`.

The world: host `gitlab.flow-sample.example`, GitLab `19.3.1`; groups `platform` (7) with subgroup `platform/tooling` (8), `apps` (9),
and an out-of-scope `sandbox` (12); 4 in-scope projects (`gateway`, `build-kit`, `storefront`, `ledger` with default branch `master`),
1 archived, 1 out of scope; the configured groups are 7 and 9. 8 users: an admin (the token's owner), 6 humans whose emails are the
Jira stub's `user<n>@flow-sample.example` (one through a confirmed secondary email, one deactivated, two also committing with a
GitLab noreply address), and a project bot (`bot: true`); one commit author matches no user. 300 default-branch commits (19 merge
commits, rebased and clock-skewed dates, `stats`, lock/vendored/generated/minified/binary/renamed files, one 1,040-file commit, one
`too_large` and one `collapsed` file), 60 merge requests (34 merged, 16 opened, 8 closed, 2 locked) with notes, `DiffNote`s, system
notes, approvals and reviewer states. The `gitlab-day2` scenario: a force-push, `master` to `main`, a deleted MR, a project moved
out, a new project, new commits and MR updates.

Shapes the real-instance smoke run (PR 12) must confirm, because the stub models them from knowledge of the API, not from a capture:
`too_large`/`collapsed`/`generated_file` on commit diff entries (the stub emits the first two only where they apply); the GraphQL
`iid` as a string and the shape of a partial-failure `errors` entry; `ref_name=<unknown>` on the commit list (the stub 404s, a real
server may answer `[]`); `X-Total` on every list; commit dates printed with the committer's own offset (`+02:00`, `+00:00` for
UTC); the legacy `/approvals` object; reviewer `state` values; the `Link` header carrying route params (`id`, `sha`).

**The `too_large` fallback.** The design formula is the commit's `stats` minus the RAW lines of its other files (see "Path exclusions"),
so a lock file changed beside the too-large file is not credited to it. The stub's too-large commit changes a lock file on purpose;
`expected.json` `special.tooLarge.fallbackByDesign` is the design figure (the true lines of the file) and
`fallbackSubtractingCountedOthersSuperseded` the earlier "minus the other files' *counted* lines" figure, which over-attributes the
excluded files' lines and is kept only for comparison.

PR 6's `GitLabConnectorTest` runs its own SYNC against the in-JVM stub (`GitLabFixtures`, `GitLabTestSupport.kt`: a disabled connection per test,
`stubClient()`, `run(...)`, `freshContext(...)`/`runStream(...)`). **`SyncedGitLabFixture` (PR 7)** is the one full SYNC per fork (PROJECTS, USERS, COMMITS, COMMIT FILES;
~1.6 s warm after Postgres, printed as `fixture-cost gitlab-sync`) that `GitLabCommitsStreamTest` reads: a class warms it in a `@BeforeClass` through its own
`warmGitLabFixture()` (`StubFixtureWarmupGuardTest` enforces the pair, so the Jira-fixture classes do not pay for it), its tripwire is 300 live commits and no pending
files, and it is soft-deleted after the SYNC so that its connection does not hold the ONE-instance scope rule against `GitLabScopeTest`'s other-instance creates. A test
that mutates (a second SYNC, the day-2 scenario, a head or budget change) makes its own connection. The sweep's `merge_base(<commit>, <branch>)` answers for the
force-pushed project's old commits and its divergence commit are in the stub too (`p1-merge-base-candidate-*`). The stub's `RateLimit-*` headers are static (`Remaining: 1990`), so no test run pauses.

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
| 9 | RECONCILE | 1.36.1 | Opus |
| 10 | `norm.code_*` + PURGE + profile (V44) | 1.37.0 | Opus (migration) |
| 11 | identity mapping (V45, `identities/`) | 1.38.0 | Opus + authorization |
| 12 | scaled-world performance run (`build-times.md` WHY) + real-instance smoke checklist | — | — |

## Risks and open points

- **First backfill:** ~110k calls (commit files + MR details), spread over several runs by the budgets — about 45 min at
  ~40 req/s, longer with instance rate limits on.
- **Commit ↔ MR linking:** only merge and squash SHAs are stored for now.
- **First-sync ordering:** `since` with out-of-order dates may stop early (see COMMITS, "RELIST and the `since` caveat"); RECONCILE's full re-list catches up.
- **Real-instance confirmation** (the user's smoke run, PR 12): `a..b` ranges, `diffStatsSummary`, commit `/diff` paging, that `since` early-stops
  the commit list (and does not filter) on the real instance, that the commits listing sends `X-Next-Page`, that `merge_base(<unreachable commit>, <branch>)` answers the fork point rather than an error,
  `RateLimit-*` headers and visible emails on that version. Only figures are shared back, never names or hosts.
