# Jira Cloud integration

Started in v0.2.0 plan commit 4 (`feat(jira): outbound guard + Jira HTTP client + Test connection
endpoints`). Covers auth, the gateway/`cloudId` shape, rate limits/backoff, the stub, and error
codes; the raw store, streams and normalization arrive in later commits (see
`.claude/docs/ingestion.md`).

## Auth and the gateway

Every authenticated call goes through the Atlassian gateway,
`https://api.atlassian.com/ex/jira/{cloudId}/rest/...` (site-URL auth is 401 for a scoped
service-account token). `cloudId` is resolved from the ONE unauthenticated endpoint,
`GET https://<site>/_edge/tenant_info` (`HttpJiraClient.resolveCloudId`, `jira/JiraClient.kt`) —
it must run before any other call; `HttpJiraClient` throws `IllegalStateException` if it hasn't
(`JiraConnector.testConnection` treats that as every downstream probe being unreachable, not a
distinct failure of its own).

`authScheme` (`BASIC` — `Authorization: Basic base64(email:apiToken)` — or `BEARER` —
`Authorization: Bearer apiToken`) is a per-connection setting (`jiraAuthHeader`,
`jira/JiraHttp.kt`) covering the spike's uncertainty over which the real tenant needs, without a
redeploy. The token is never logged; neither is a URL with a query string (host+path only).

**Development stub.** `jira.stubBaseUrl` reroutes BOTH the tenant_info host and the gateway HOST to
`sample-data/jira-stub` (compose service or the in-JVM `JiraStubServer` test fixture) — the gateway
PATH SHAPE (`/ex/jira/{cloudId}/...`) is unchanged, only the host is substituted; there's no
separate fake `*.atlassian.net` origin. Development mode only: production startup refuses a
non-blank value (`jira/Jira.kt`'s fail-closed check, `OutboundGuardTest` pins it).

## Outbound guard

See `.claude/docs/security.md` "Outbound HTTP calls" for the full guard shape
(`infra/outbound/OutboundGuard.kt`): a host allow-list as the primary boundary
(`api.atlassian.com`, a genuine `*.atlassian.net` tenant, or — dev only — the stub host) plus the
address-range check ported from Toadie as defense in depth.

## Rate limits and backoff

`jira/JiraHttp.kt` retries 429/5xx/IOException up to `jira.maxRetries`, backing off
`min(30s, 2s·2^n) × U(0.7,1.3)` (`JiraBackoff`, pure and clock/random-injectable —
`JiraBackoffTest`), honouring a `Retry-After` header when present (clamped to 120s, taking
precedence over the exponential formula). An `X-RateLimit-NearLimit: true` response header adds a
1s pause before the NEXT call. A `Semaphore(jira.maxConcurrentRequests)` caps concurrent in-flight
calls per process. A response body is read bounded at `jira.maxResponseBytes` (default 32 MiB,
Covenant's `ToadieGraphqlClient` shape) — `LIMIT_EXCEEDED` past the cap.

## Test connection

`jira/JiraConnector.testConnection` (used by `POST /api/v1/data-sources/test` — ad-hoc, carries
`apiToken` — and `POST /api/v1/data-sources/{id}/test` — the stored, decrypted token; the latter
persists `cloudId` on success) probes each endpoint below in sequence, at most 10s each and 30s
total, NEVER throwing — every outcome is a `ConnectionTestRow {name, path, required, ok, status,
code, scopeHint}`. `required = false` rows (bulkfetch, boards + its children) may legitimately fail
on a real tenant whose scopes/GA status differ; a failure there doesn't fail the connection.

| Probe | Endpoint | Required | Likely scope |
|---|---|---|---|
| `tenant_info` | `GET /_edge/tenant_info` | yes | — (unauthenticated) |
| `myself` | `GET /rest/api/3/myself` | yes | `read:jira-user` |
| `search` | `GET /rest/api/3/search/jql` | yes | `read:jql:jira` |
| `field` | `GET /rest/api/3/field` | yes | `read:field:jira` |
| `statuses` | `GET /rest/api/3/statuses/search` | yes | `read:status:jira` |
| `projects` | `GET /rest/api/3/project/search` | yes | `read:project:jira` |
| `project_statuses:<KEY>` (one per configured project key) | `GET /rest/api/3/project/{key}/statuses` | yes | `read:project:jira` |
| `bulkfetch` | `POST /rest/api/3/changelog/bulkfetch` (single-id probe, not a real 50-id chunk) | **no** | uncertain GA/scope (spike fact sheet) |
| `issue_changelog` | `GET /rest/api/3/issue/{id}/changelog` | yes | `read:issue-details:jira` |
| `issue_worklog` (A1) | `GET /rest/api/3/issue/{id}/worklog` | yes | `read:issue:jira` |
| `worklog_updated` | `GET /rest/api/3/worklog/updated` | yes | — |
| `users` | `GET /rest/api/3/users/search` | yes | `read:jira-user` |
| `boards` | `GET /rest/agile/1.0/board` | **no** | `read:board-scope:jira-software` |
| `board_configuration` | `GET /rest/agile/1.0/board/{id}/configuration` | **no** | `read:board-scope.admin:jira-software` |
| `board_sprints` | `GET /rest/agile/1.0/board/{id}/sprint` | **no** | `read:sprint:jira-software` |

The `issue_*`/`bulkfetch` probes reuse the first in-scope issue id the `search` probe returns; the
`board_*` probes reuse the first board id `boards` returns. If `search`/`boards` return nothing (or
fail), their dependent probes are skipped entirely rather than guessing an id.

`POST /api/v1/data-sources/test` and `.../{id}/test` are ADMIN-only (guard before body decode),
rate-limited at 10/min per IP (`RateLimits.DATA_SOURCE_TEST`), and audited as `data_source.tested`
(siteHost, ok, failedEndpoints — never a token). Never `502`: a probe failure is a row, not a
failing HTTP status.

## ISSUES stream: fields and JQL

`jira/JiraIssuesStream.kt` (v0.2.0 plan §7, plan commit 6) pages `GET /rest/api/3/search/jql` with
`fields = null` — the `fields` query parameter is omitted entirely rather than sent empty, matching
Jira's OWN default of returning every field on the issue document (the stub mirrors this: a request
with no `fields` param gets the full document, `sample-data/README.md`). Normalization (arriving
plan commit 8) needs both every system field and every discovered custom field
(`schema.custom`-tagged, e.g. Sprint/Story Points/Flagged/Team), and Jira offers no cheaper "all
system + all discovered custom" shape than asking for everything — so `fields=null` is intentional,
not a placeholder.

**JQL shapes** (`jira/JiraJql.kt`, project keys pre-validated by `PROJECT_KEY_PATTERN`
`^[A-Z][A-Z0-9_]{1,9}$`, so no quoting/escaping is ever needed):

- `scope(projectKeys)` — `project in ("A","B")`, the base every other builder starts from.
- `incremental(projectKeys, sinceMinutes)` — the ISSUES stream's own query:
  `<scope> AND updated >= "-Nm" ORDER BY updated ASC` (a TZ-free RELATIVE bound — Jira's absolute
  JQL dates are TZ-sensitive, a spike-identified risk this sidesteps entirely).
- `reconcile(projectKeys)` — `<scope> ORDER BY id ASC`; defined now, not yet called anywhere — a
  future RECONCILE stream is its first consumer.

Two more `jira.*` config keys are consumed for the first time by this stream (both already declared
in `application.yaml` since an earlier commit, unread until now): `jira.pageSize` (default 100,
1..500 — the `search/jql` `maxResults` per page; the stub ignores it and returns its own fixed-size
pages regardless) and `jira.incrementalOverlapMinutes` (default 10, 0..1440 — the re-widening
window past the last watermark described above).

## CHANGELOGS and WORKLOGS streams: endpoints

Landed with the CHANGELOGS/WORKLOGS streams (plan §7, plan commit 7, V11 — see
`.claude/docs/ingestion.md` "CHANGELOGS stream"/"WORKLOGS stream (A1)" for the full behavior):

- **`POST /rest/api/3/changelog/bulkfetch`** (`JiraClient.changelogBulk`) — the CHANGELOGS stream's
  primary path, a chunk of `jira.changelogBulkSize` issue ids per call, paged by `nextPageToken`;
  the response envelope is `{issueChangeLogs: [{issueId, changeHistories: [...]}], nextPageToken}`.
  A 404/405/410/501 response means the endpoint itself is unavailable for that batch (GA/scope gap,
  not a transient failure) — the CHANGELOGS stream falls back to the per-issue endpoint below for
  exactly that batch, not a retry of bulkfetch itself.
- **`GET /rest/api/3/issue/{id}/changelog`** (`JiraClient.issueChangelogPage`, `JiraChangelogPage`) —
  the per-issue fallback, `startAt`-paged like most Jira list endpoints, but its array key is
  **`histories`, NOT `values`** — the one envelope shape in this client that diverges from the
  common `JiraStartAtPage` shape every OTHER `startAt`-paged endpoint uses (see "Test connection"'s
  `issue_changelog` probe above, and `jira/JiraModels.kt`'s `JiraChangelogPage`). Reusing
  `JiraStartAtPage` for this endpoint would silently deserialize an always-empty array (kotlinx
  ignores an unknown `histories` key and defaults `values` to empty) rather than fail loudly, so it
  gets its own data class.
- **`GET /rest/api/3/issue/{id}/worklog`** (`JiraClient.issueWorklogPage`, `JiraWorklogStartAtPage`)
  — the WORKLOGS stream's per-issue backfill path (A1); same envelope shape as most `startAt`-paged
  endpoints, but the array key is `worklogs`, not `values` (its own data class for that reason,
  `JiraWorklogStartAtPage`).
- **`GET /rest/api/3/worklog/updated`** / **`GET /rest/api/3/worklog/deleted`**
  (`JiraClient.worklogUpdated`/`worklogDeleted`, `JiraWorklogIdsPage`) — the WORKLOGS stream's
  instance-wide incremental feed (A1): bare `{worklogId, updatedTime}` pairs, no `issueId`, with
  their own `since`/`until`/`nextPage`/`lastPage` cursor shape (queried by `since`, never by
  following `nextPage` — see "cursors are rebuilt from `since`/`until`" in
  `.claude/docs/ingestion.md`).
- **`POST /rest/api/3/worklog/list`** (`JiraClient.worklogList`, ≤1000 ids per call) — resolves the
  bare ids `worklog/updated` returns into full worklog bodies (the only one of these calls whose
  response carries `issueId`), which the WORKLOGS stream's scope filter needs before it can decide
  what to keep.

**`jira.changelogBulkSize`** (default 50, `JiraSyncDependencies.changelogBulkSize`,
`JIRA_CHANGELOG_BULK_SIZE` env override) is the CHANGELOGS stream's `changelog/bulkfetch` chunk size
— it **MUST match `sample-data/jira-stub`'s own fixed 50-id chunking** (`sample-data/README.md`):
the stub's `changelog/bulkfetch` WireMock mappings only match requests shaped as its own generator's
50-id chunks (ascending, exact-order), so changing this value without regenerating the stub breaks
every bulk chunk after the first mismatch, not just the one deliberately-omitted chunk the stub
already exercises.

## `CURSOR_EXPIRED` detection

Landed with the ISSUES stream (plan commit 6): `HttpJiraClient.searchJql` passes
`statusCodeOverrides = mapOf(400 to "CURSOR_EXPIRED", 410 to "CURSOR_EXPIRED")` to `JiraHttp`, but
ONLY when the request carries a `nextPageToken` — a bare first page's own 400/410 is a genuine
`INVALID_RESPONSE` (there was no token to have expired). `JiraIssuesStream` catches exactly this
code and restarts the run from the last completed watermark (`freshRun`), up to
`MAX_CURSOR_RESTARTS` (5) restarts per stream invocation before letting the exception propagate.
The error-codes table below is updated accordingly; `CURSOR_EXPIRED` is no longer a reserved,
undetected code.

## Error codes (`JiraFetchException`)

`AUTHENTICATION_FAILED` (401) · `FORBIDDEN_SCOPE` (403) · `NOT_FOUND` (404) · `RATE_LIMITED` (429,
retries exhausted, OR a `Retry-After` whose wait alone would cross the remaining
`jira.requestDeadlineSeconds` budget — reported immediately rather than waited out) ·
`UPSTREAM_UNAVAILABLE` (5xx or IOException, retries exhausted, OR the next backoff would cross that
same total-request deadline) · `TIMEOUT` (request/probe timeout) · `INVALID_RESPONSE` (malformed
JSON, an unexpected shape — every `.jsonObject`/`.jsonArray`/`.jsonPrimitive` cast is routed through
one decode helper so a mismatch is always this code, never an uncaught exception — an invalid
`cloudId` shape, or a Jira-supplied issue id/key that doesn't match the expected path shape) ·
`LIMIT_EXCEEDED` (response over `jira.maxResponseBytes`) · `BLOCKED_HOST` (the outbound guard
refused the resolved host/address — `BlockedHostException` extends `UnknownHostException`, the
`Dns` contract's own checked type, so OkHttp's async call path delivers it to Ktor unwrapped instead
of re-wrapping it as a generic `IOException`; a single attempt, never retried) · `REDIRECT` (a 3xx —
never followed) · `CURSOR_EXPIRED` (a `search/jql` `nextPageToken` page answering 400/410 — see
"`CURSOR_EXPIRED` detection" above; the ISSUES stream restarts from the last watermark rather than
propagating it, up to a bounded number of restarts).

Every attempt (across every retry) is additionally bounded by a TOTAL per-request deadline
(`jira.requestDeadlineSeconds`, default 180s, config-validated via `requireConfigInt`) — separate
from `jira.requestTimeoutSeconds`, which only bounds a SINGLE attempt — so unbounded retries can
never hold the `jira.maxConcurrentRequests` `Semaphore` permit indefinitely.

## Testing

- `JiraBackoffTest` — pure backoff math, injected clock/random.
- `JiraJqlTest` — `JiraJql`'s scope/incremental/reconcile builders.
- `OutboundGuardTest` — the allow-list, every blocked address range (injected resolver), the
  stub-host-only-in-dev rule, a boot test pinning production's `jira.stubBaseUrl` refusal, and a
  set of production-wiring integration tests (`buildGuardedJiraHttpClient`, the SAME builder
  `jira/Jira.kt` uses) against the real, loopback `JiraStubServer` over the real OkHttp engine:
  `BLOCKED_HOST` in one attempt/no retry, a disallowed host never reaching the resolver, `Proxy.
  NO_PROXY`, and a real 3xx never followed.
- `JiraConnectorTest` — `testConnection`'s probe loop against a hand-written `JiraClient` fake: a
  raw-cast `IllegalArgumentException` anywhere becomes an `INVALID_RESPONSE` row rather than an
  uncaught crash, a genuine coroutine cancellation is never swallowed as a row, and a probe honours
  the REMAINING 30s total budget, not just its own fixed 10s cap.
- `JiraClientTest` — `JiraHttp`/`HttpJiraClient` against a Ktor `MockEngine`: status→code mapping,
  429-then-`RATE_LIMITED`, the byte cap, malformed JSON, a refused redirect, `nextPageToken`
  paging, and both auth header shapes.
- `JiraStubServer` — the shared in-JVM WireMock fixture over `sample-data/jira-stub` (the SAME
  mappings the compose stack serves); `DataSourceTestConnectionTest` drives the real Test-connection
  endpoints against it, including per-test WireMock override mappings for the `FORBIDDEN_SCOPE`/
  `AUTHENTICATION_FAILED` cases and the rate-limit bucket.
- `JiraSyncPipelineTest` — the full `JiraConnector.run(SYNC)` (REFERENCE → ISSUES → CHANGELOGS →
  WORKLOGS) against `JiraStubServer`, asserting in-scope-reachable counts (never the whole-dataset
  `expected.json` figures, see `.claude/docs/ingestion.md` "Jira stub"): the omitted bulkfetch
  chunk's per-issue fallback setting the `changelogs` cursor's `bulkUnavailableUntil`, a second
  no-op SYNC changing no changelog/worklog row, `PURGE` draining all four raw tables, and the
  `jira-day2` scenario's out-of-scope worklog drop/count and tombstone. Also drives `JiraIssuesStream`
  directly for the ISSUES cursor's own fault-injection/`CURSOR_EXPIRED`/lease-loss mechanics (unrelated
  to CHANGELOGS/WORKLOGS, so run without the full connector's REFERENCE pass).
- `JiraRawStoreTest` — `JiraRawStore`'s own write paths in isolation: the append-only changelog
  dedup (`ON CONFLICT DO NOTHING` on a re-inserted history id), the worklog sha256 diff/tombstone/
  resurrection cycle, `staleChangelogIssueIds`/`staleWorklogIssueIds`'s claim-scan predicates, and
  `knownInScopeIssueIds`'s scope filter.
