# GitLab integration (self-managed)

The `gitlab/` package and the code-activity layer around it: a self-managed GitLab instance read through its REST API (plus
one GraphQL query), stored raw in `raw.gitlab_*`, processed into the connector-agnostic `norm.code_*` tables, and tied to
the Jira people through admin-confirmed identity links. The queue, worker, cursors and PROCESS/PROFILE mechanics it plugs
into are in `.claude/docs/ingestion.md`; the Jira connector it sits beside is `.claude/docs/jira-integration.md`.

**Status: being built (1.34.0 → 1.38.0).** Until a section's PR lands, the section is the design that PR implements; when it
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
  visible to the identity proposals. The token is refused if it carries `api`, any `write_*` or `sudo` scope, at Test
  connection and again at every SYNC (`TOKEN_SCOPE_TOO_BROAD`, the job fails closed).
- **No git mirror and no repository content.** Flow never clones and stores no code, diffs, commit bodies, MR descriptions
  or note bodies. Kept: the commit's first line and the MR title (≤255 chars, for the later Jira-key linking), branch names,
  file paths and per-file line counts. System notes become action codes; their bodies are dropped.
- **Identity = proposed by email, confirmed by an admin.** Unmapped activity is kept and becomes attributable once linked.
- **The connector does not feed DERIVE.** `DataSourceKind.derivesMetrics` is false for GITLAB; every Jira-only path is
  gated on it (below).

## The connector seam (BACKLOG D6, done here)

Before GitLab, the seam was Jira-shaped: `Connector.testConnection(siteUrl, email, apiToken…)`, `DataSourceRequest.jira`, the
V8 `kind` CHECK allowing only `JIRA_CLOUD`, and `jira/Jira.kt` *replacing* the connector registry attribute.

- `Connector.testConnection(target: ConnectionTarget)`: a sealed type in `ingest/` with a Jira and a GitLab case.
- Each connector adds itself through a `connectorRegistry()` helper (the `jobHandlerRegistry()` pattern); `configureGitLab`
  runs after `configureJira` in `application.yaml`.
- `DataSourceKind(derivesMetrics)`: `JIRA_CLOUD(true)`, `GITLAB(false)`. The settings decoder picks the shape by kind.
- **Kind-leak guards.** Without them a GITLAB row would reach Jira paths: the scope check decoding every row as Jira
  (`ingest/DataSourceScope.kt`), the DERIVE chaining in `IngestWorker.onSucceeded` and the `MetricsSettingsService` fan-out,
  the report and Home connection scopes (`reports/ReportSupport.resolveConnectionScope`, `HomeReads`), the metrics-config
  routes (409 `data-source-kind-unsupported`) and the raw-issue inspector. `KindLeakTest` pins each.
- **Wire shape.** `DataSourceRequest`/`Response`/`TestRequest` carry two optional blocks, `jira` and `gitlab`; exactly one is
  present, it sets the kind, and the kind is immutable after create. No `oneOf` (the spec stays 3.0-compatible) and the Jira
  form's JSON is unchanged.
- **Scope rules per kind.** Jira keeps its rules, filtered to Jira rows. GitLab: one GitLab host across the active sources
  (`data-source-other-instance`), and no group equal to or nested in another source's group (`data-source-group-overlap`),
  so no project is counted twice.

## Shared fetch machinery (`fetch/`)

The hardened Jira transport is lifted, not copied: `JiraHttp` → `SourceHttp`, the backoff, `JiraCallTally` → `CallTally`,
the page-token guard and ceilings, the node ceiling, the oversized-response handling and the heap-fit maths. The rate-limit
signal is a strategy: Jira reads `X-RateLimit-NearLimit`; GitLab reads `RateLimit-Remaining`/`RateLimit-ResetTime` and
pauses near zero (clamped). `PackageBoundaryTest`: `jira` and `gitlab` never import each other; both may import `fetch`.

## Outbound calls to a self-managed host

The SSRF guard (`infra/outbound/OutboundGuard.kt`, `.claude/docs/security.md` "Outbound HTTP calls") stays the only way
out. A self-managed host usually lives on a private address, so the guard gains an operator-only allowance:

- **`GITLAB_ALLOWED_HOSTS`** — exact `host[:port]` entries, no wildcards, no IP literals. Empty = the GitLab kind is off.
  The admin UI cannot widen it: a data source whose base URL's host is not listed is a 400.
- **`GITLAB_ALLOWED_CIDRS`** — private ranges a *listed* host may resolve to; any other host keeps the full address check.
  Boot refuses `/0`, ranges wider than /8 (IPv6: /32), and any overlap with loopback, link-local (incl. 169.254/16, cloud
  metadata), any-local or multicast.
- **Guard seam.** `GuardedDns.skipAddressCheck` becomes `addressAllowed(host, addr)`; the Jira default is unchanged.
  Resolution and the check happen per connection, so DNS rebinding cannot swap in an address after validation.
- **Base URL:** `https`, origin only (no path, query or fragment).
- **Internal CA:** `GITLAB_CA_BUNDLE` (PEM), added to the GitLab client's trust store only. There is no "skip TLS" switch.
- **Kept from Jira:** no redirects, no proxy. `Link` headers are never followed: only their page/keyset parameters are read
  and re-applied to the configured origin.
- **Stub:** `gitlab.stubBaseUrl` is development-only; production refuses to start with it set, or with any malformed host,
  CIDR or CA setting.
- **Memory:** `gitlab.maxResponseBytes ≤ jira.maxResponseBytes`, so the worker's existing heap-fit check covers GitLab.

Example (neutral): `GITLAB_ALLOWED_HOSTS=gitlab.example.com`, `GITLAB_ALLOWED_CIDRS=10.20.0.0/16`.

## Test connection

Probes, in order: `GET /api/v4/version`; `GET /user` (`is_admin` false is a warning: emails will be hidden, so proposals
degrade); `GET /personal_access_tokens/self` (refuses `api`, `write_*`, `sudo`); each configured group. The token is stored
in the encrypted `source_connections.secret` like the Jira token and never echoed or logged.

## Streams

All REST except MR diff stats, which come from one batched GraphQL query (`diffStatsSummary {additions deletions fileCount}`
for up to 50 MRs) instead of a paged `/diffs` call per MR. A GraphQL error leaves a null gap; it never fails the job.

- **PROJECTS** — the groups' projects with `include_subgroups`, archived excluded: path, group, default branch.
- **USERS** — `/users` (admin token): username, name, state, bot flag; emails from `/users/:id/emails` (primary, commit,
  public, secondary) plus the `noreply` pattern.
- **COMMITS** — first sync: `ref_name=<default>&since=<backfillFrom>&with_stats=true`. Then a range walk
  `<storedHead>..<default>` per project (a `since` cursor would miss merged commits with older dates). When the head moves,
  `merge_base` detects a force-push, which re-lists the window and tombstones commits that are gone. A renamed default branch
  is handled the same way, because the stored head is a SHA.
- **COMMIT FILES** — `/commits/:sha/diff`, one call per commit, newest first under a per-run budget. While streaming, the
  transform counts each file's `+`/`-` lines (lines after the first `@@`, not the `+++`/`---` headers, not
  `\ No newline at end of file`) and drops the text. Kept: `path` (≤1,024 chars), additions, deletions, new/deleted/renamed
  flags, `old_path`. A `too_large` or `collapsed` file keeps its path with null lines. Capped at 1,000 files per commit
  (`files_capped`).
- **MERGE REQUESTS** — group-level `/groups/:id/merge_requests?updated_after&include_subgroups`, cursor in `sync_cursors`.
- **MR DETAILS** — notes, approvals, reviewers (REST) and the GraphQL diff stats, under budgets.
- **RECONCILE** — a SHA re-list per project tombstones vanished commits; a 404 marks a project deleted; a moved project is
  marked out of scope; an archived project keeps its data; an MR id re-list tombstones deleted MRs.
- **PROCESS / PROFILE** — into `norm.code_*` under `CODE_PROCESSING_VERSION`, separate from Jira's `PROCESSING_VERSION`, so
  neither side reprocesses the other.

## Path exclusions

A per-data-source glob list, `gitlab.pathExclusions` in the settings JSONB, edited on the data source. Defaults:
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
- **V41** — `raw.gitlab_projects` (`commits_head_sha`, relist flag, seen/moved/deleted markers, slim payload + sha256),
  `raw.gitlab_users` (`emails` JSONB).
- **V42** — `raw.gitlab_commits` (author name/email, authored/committed time, parent count, title, stats, `files_changed`
  null = pending, `files_capped`, tombstone, processing columns, partial indexes for the files backlog and PROCESS),
  `raw.gitlab_commit_files` (cascades with its commit).
- **V43** — `raw.gitlab_merge_requests` (details watermark, tombstone), `raw.gitlab_mr_events` (NOTE / APPROVAL / REVIEWER).
- **V44** — `norm.code_projects`, `code_identities`, `code_commits`, `code_merge_requests`, `code_mr_events` (COMMENT /
  DIFF_COMMENT / APPROVAL / REVIEW_REQUESTED / REVIEWED / CHANGES_REQUESTED).
- **V45** — `public.code_identity_links`.

Each lands with its `MigrationChecksumTest` pin and its `persistence.md` section; new cross-feature reads go on that doc's
permission list.

## Stub (as built, PR 5)

`sample-data/gitlab/generate.mjs` (zero dependencies, seeded, byte-identical; `--users/--projects/--months/--out`) writes
`sample-data/gitlab-stub/{mappings,__files}` (727 mappings, ~9 MiB) and `sample-data/gitlab/expected.json`. It is served by the
compose `gitlab-stub` (WireMock, `127.0.0.1:8095`, read-only mount, nothing points the app at it until PR 6) and the in-JVM
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

**A note for the COMMIT FILES design.** The `too_large` fallback ("the commit's `stats` minus its other files' counted lines") also
absorbs the lines of the commit's *excluded* files (a lock file changed beside the too-large file is not "counted"), so it
over-attributes; `expected.json` `special.tooLarge` records both that figure and the true one (commit `stats` minus the other
files' *raw* lines). The stub's too-large commit changes a lock file on purpose, so PR 7 sees the difference.

`SyncedGitLabFixture` (one SYNC per fork, warmed in `@BeforeClass` like the Jira fixtures) lands with the COMMITS PR (7), when a
sync exists to run.

## PR sequence

| # | PR | Version | Review |
|---|---|---|---|
| 1 | this doc | — | — |
| 2 | `fetch/` lift, no behaviour change | — | Opus |
| 3 | connector seam + V40 + kind-leak guards | — | Opus |
| 4 | self-managed outbound guard | — | security-auditor + Opus |
| 5 | GitLab stub | — | Sonnet |
| 6 | data source + PROJECTS/USERS (V41), web kind picker | 1.34.0 | Opus (outbound use) |
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
- **First-sync ordering:** `since` with out-of-order dates may stop early; RECONCILE's full re-list catches up.
- **Real-instance confirmation** (the user's smoke run, PR 12): `a..b` ranges, `diffStatsSummary`, commit `/diff` paging,
  `RateLimit-*` headers and visible emails on that version. Only figures are shared back, never names or hosts.
