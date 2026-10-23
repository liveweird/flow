# Jira Cloud integration

The `jira/` package (v0.2.0): the guarded HTTP client and the Test connection endpoints, the raw
store, the ISSUES/CHANGELOGS/WORKLOGS/RECONCILE/reference/profile streams and the normalizer. This doc covers
auth, the gateway/`cloudId` shape, rate limits/backoff, the stub, the stream endpoints, timestamps and
error codes; the queue, worker and `norm` tiling around them are in `.claude/docs/ingestion.md`.

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
precedence over the exponential formula). The IOException retry covers a DNS failure too (an allow-listed host the resolver
cannot resolve is a plain `UnknownHostException`, not a guard rejection - `security.md` "Outbound HTTP calls"): with the default
`jira.maxRetries` of 4 that is five attempts and about 30s of nominal backoff (2+4+8+16s, each x U(0.7,1.3)) before
`UPSTREAM_UNAVAILABLE`; every retry goes back through `GuardedDns` (the JVM caches a failed lookup for 10s,
`networkaddress.cache.negative.ttl`, so the first fresh DNS query is the retry after ~14s). An `X-RateLimit-NearLimit: true` response header makes the call sleep
1s before it reads that response body, holding its permit and its connection (the one wait that holds a permit). A `Semaphore(jira.maxConcurrentRequests)` caps concurrent in-flight
calls per process (default 4; one `JiraHttp` serves every connection and job). It is THE concurrency limit: a stream that fetches several
items at once (today only the WORKLOGS per-issue backfill, "Batched raw writes" below) just launches the calls and lets the semaphore
admit them, sizing its look-ahead from `JiraClient.maxConcurrentRequests` - it adds no limit of its own. Each call retries, backs off and
honours `Retry-After` on its own (an HTTP-date `Retry-After` parses as no header, so the computed backoff applies), AND a 429 (or a 5xx
that carries `Retry-After`) sets a **per-tenant shared pause**: `JiraHttp` records, for the call's tenant, `notBefore = max(current, now +
the wait that call is about to take)` - `Retry-After` clamped to 120s, else the computed backoff - even when that call itself gives up
(retries or deadline exhausted), and every other call of the SAME tenant waits until that instant before it STARTS a request. The key is
the cloud id (`HttpJiraClient` passes its gateway base), never the host: Atlassian limits per tenant, `api.atlassian.com` is shared by all
of them, and one `JiraHttp` serves every connection, so tenant A's 429 must not stall tenant B's sync or a "Test connection" probe. A call
without a key (the unauthenticated `tenant_info` lookup) neither sets nor obeys a pause. **A call holds a semaphore permit only while a
request runs** (the permit is process-wide): it takes one when its tenant is not paused (a fresh call waits for the pause first; a call
that queued and then meets a pause hands the permit back and waits), and it hands the permit back during its own retry backoff and
re-takes it, again outside a pause, for the next attempt - so a rate-limited tenant never parks permits (only `X-RateLimit-NearLimit`'s
1s sleep still holds one). The wait is the injectable `sleeper` (a cancelled job leaves it at once). After the backoff and any pause,
BEFORE asking for a permit again, the call's deadline is checked once more (`RATE_LIMITED` when a `Retry-After` was involved, else the call's
own terminal code), so time spent queueing for a permit - everyone wakes together when a pause ends - is never charged to the retry, as it is
not for a first attempt. Requests already on the wire are not interrupted; a network error (IOException) or a plain 5xx pauses nobody. So the
concurrent WORKLOGS backfill never keeps firing siblings into a rate limit (`JiraHttpSharedPauseTest`). A response body is read bounded at `jira.maxResponseBytes` (default 32 MiB,
Covenant's `ToadieGraphqlClient` shape) — `LIMIT_EXCEEDED` past the cap (the batch streams' answers: "Oversized issue page (`LIMIT_EXCEEDED`)" and "Oversized changelog/worklog batch" below).

## Test connection

`jira/JiraConnector.testConnection` (used by `POST /api/v1/data-sources/test` — ad-hoc, carries
`apiToken` — and `POST /api/v1/data-sources/{id}/test` — the stored, decrypted token; the latter
persists `cloudId` on success) probes each endpoint below in sequence, at most 10s each and 30s
total, NEVER throwing — every outcome is a `ConnectionTestRow {name, path, required, ok, status,
code, scopeHint}`. Only `bulkfetch` and `project_fields` are `required = false`: CHANGELOGS really does fall back
from `bulkfetch` to the per-issue endpoint on 404/405/410/501, and a real tenant's GA/scope status for it differs;
`project_fields` is an experimental endpoint whose absence only costs the metrics-config editor its default field
filter (see "Project field schemes" below). A failure of either doesn't fail the *Test connection*. The three board probes are required, because a SYNC's
REFERENCE stream fetches boards, board configurations and sprints for every connection, and a 401/403
there fails the SYNC job. Recorded decision (2026-10-08): the sync stays strict and the board probes
are required. `scopeHint` is one plain string; several scopes are comma+space separated.

On a scoped API token, a missing scope comes back from the gateway as **401**
(`AUTHENTICATION_FAILED`), not 403. So one failing probe while `myself` passes means a missing scope,
not bad credentials.

| Probe | Endpoint | Required | Scope hint |
|---|---|---|---|
| `tenant_info` | `GET /_edge/tenant_info` | yes | — (unauthenticated) |
| `myself` | `GET /rest/api/3/myself` | yes | `read:jira-user` |
| `search` | `GET /rest/api/3/search/jql` | yes | `read:jql:jira` |
| `field` | `GET /rest/api/3/field` | yes | `read:field:jira` |
| `statuses` | `GET /rest/api/3/statuses/search` | yes | `read:workflow:jira` |
| `projects` | `GET /rest/api/3/project/search` | yes | `read:project:jira` |
| `project_statuses:<KEY>` (one per configured project key) | `GET /rest/api/3/project/{key}/statuses` | yes | `read:status:jira, read:issue-status:jira, read:issue-type:jira` |
| `bulkfetch` | `POST /rest/api/3/changelog/bulkfetch` (single-id probe, not a real 50-id chunk) | **no** | `read:issue.changelog:jira` |
| `issue_changelog` | `GET /rest/api/3/issue/{id}/changelog` | yes | `read:issue-details:jira, read:issue.changelog:jira` |
| `issue_worklog` (A1) | `GET /rest/api/3/issue/{id}/worklog` | yes | `read:issue:jira, read:issue-worklog:jira` |
| `worklog_updated` | `GET /rest/api/3/worklog/updated` | yes | `read:issue-worklog:jira` |
| `users` | `GET /rest/api/3/users/search` | yes | `read:jira-user` |
| `boards` | `GET /rest/agile/1.0/board` | yes | `read:board-scope:jira-software, read:project:jira` |
| `board_configuration` | `GET /rest/agile/1.0/board/{id}/configuration` | yes | `read:board-scope.admin:jira-software, read:project:jira` |
| `board_sprints` | `GET /rest/agile/1.0/board/{id}/sprint` | yes | `read:sprint:jira-software` |
| `project_fields` | `GET /rest/api/3/projects/fields` (one row asked for; seeded with the first configured key's project id and first issue-type id) | **no** (runs last) | `read:field-configuration:jira` |

The rule behind the hints: keep the scope that real-tenant evidence shows works, and add the
endpoint-specific granular scope from Atlassian's spec (pinned by `JiraConnectorTest`).

The `project_fields` probe runs LAST (after the required issue and board probes, so it can never eat their shared 30s
budget). It takes the first configured key's project id from the `projects` probe's first page and its
first issue-type id from that key's `project_statuses` probe; it is skipped when either is unavailable (the key is
not on `project/search`'s first page, or the key's own probe failed). The seeding helpers (`JiraProjectFields.projectIdsByKey`/
`issueTypeIds`) are total — a malformed entry is skipped, so reading the seeds can never fail the REQUIRED probe they ride on. The `issue_*`/`bulkfetch` probes reuse the first in-scope issue id the `search` probe returns; the
`board_*` probes follow the REFERENCE stream's own board rules (`isInScopeBoard`/`isScrumBoard` in
`JiraReferenceStream.kt`): `board_configuration` uses the first board of `boards`' first page whose
`location.projectKey` is in the connection's project keys, `board_sprints` the first such SCRUM board (a Kanban
board's `/sprint` answers 400, so a Kanban-first tenant would otherwise see a false red required row). If
`search`/`boards` return nothing in scope (or fail), their dependent probes are skipped entirely rather than
guessing an id.

`POST /api/v1/data-sources/test` and `.../{id}/test` are ADMIN-only (guard before body decode),
rate-limited at 10/min per IP (`RateLimits.DATA_SOURCE_TEST`), and audited as `data_source.tested`
(siteHost, ok, failedEndpoints — never a token). Never `502`: a probe failure is a row, not a
failing HTTP status.

### Scopes per endpoint

Source: Atlassian's two OpenAPI specs, Jira platform
(`https://developer.atlassian.com/cloud/jira/platform/swagger-v3.v3.json`) and Jira Software / agile
(`https://developer.atlassian.com/cloud/jira/software/swagger.v3.json`). A platform operation's
`x-atlassian-oauth2-scopes` gives a classic scope (`state: Current`) and granular scopes
(`state: Beta`); agile operations only have `security`. Read them without saving the spec into the repo:

```sh
curl -sSfL https://developer.atlassian.com/cloud/jira/platform/swagger-v3.v3.json \
  | jq -c '.paths["/rest/api/3/statuses/search"].get."x-atlassian-oauth2-scopes"'
curl -sSfL https://developer.atlassian.com/cloud/jira/software/swagger.v3.json \
  | jq -c '.paths["/rest/agile/1.0/board"].get.security'
```

Every endpoint `jira/JiraClient.kt` calls, as the spec stood on 2026-10-07 (verified 2026-10-08;
every granular scope without a suffix ends in `:jira`):

| Endpoint (Flow caller) | Granular scopes (spec) | Classic |
|---|---|---|
| `GET /rest/api/3/myself` (probe) | read:application-role, read:group, read:user, read:avatar | read:jira-user |
| `GET /rest/api/3/search/jql` (probe, ISSUES) | read:issue-details, read:audit-log, read:avatar, read:field-configuration, read:issue-meta | read:jira-work |
| `POST /rest/api/3/search/approximate-count` (ISSUES) | read:issue-details, read:field.default-value, read:field.option, read:field, read:group | read:jira-work |
| `GET /rest/api/3/issue/{id}` (RECONCILE) | read:issue-meta, read:issue-security-level, read:issue.vote, read:issue.changelog, read:avatar, read:issue, read:status, read:user, read:field-configuration | read:jira-work |
| `POST /rest/api/3/changelog/bulkfetch` (probe, CHANGELOGS) | read:issue-meta, read:avatar, read:issue.changelog | read:jira-work |
| `GET /rest/api/3/issue/{id}/changelog` (probe, CHANGELOGS) | read:issue-meta, read:avatar, read:issue.changelog | read:jira-work |
| `GET /rest/api/3/issue/{id}/worklog` (probe, WORKLOGS) | read:group, read:issue-worklog, read:issue-worklog.property, read:project-role, read:user, read:avatar | read:jira-work |
| `GET /rest/api/3/worklog/updated` (probe, WORKLOGS) | read:issue-worklog, read:issue-worklog.property | read:jira-work |
| `GET /rest/api/3/worklog/deleted` (WORKLOGS) | read:issue-worklog, read:issue-worklog.property | read:jira-work |
| `POST /rest/api/3/worklog/list` (WORKLOGS) | read:comment, read:group, read:issue-worklog, read:issue-worklog.property, read:project-role, read:user, read:avatar | read:jira-work |
| `GET /rest/api/3/field` (probe, REFERENCE) | read:field, read:avatar, read:project-category, read:project, read:field-configuration | read:jira-work |
| `GET /rest/api/3/statuses/search` (probe, REFERENCE) | read:workflow | manage:jira-configuration |
| `GET /rest/api/3/statuscategory` (REFERENCE) | read:status | read:jira-work |
| `GET /rest/api/3/project/search` (probe, REFERENCE) | read:issue-type, read:project, read:project.property, read:user, read:application-role, read:avatar, read:group, read:issue-type-hierarchy, read:project-category, read:project-version, read:project.component | read:jira-work |
| `GET /rest/api/3/project/{key}/statuses` (probe, REFERENCE) | read:issue-status, read:issue-type, read:status | read:jira-work |
| `GET /rest/api/3/projects/fields` (probe, REFERENCE `PROJECT_FIELDS`, optional, experimental) | read:field, read:field-configuration (as documented; verified working on the real tenant 2026-10-08 with Flow's token, no new scope needed there) | not documented |
| `GET /rest/api/3/issuetype` (REFERENCE) | read:issue-type, read:avatar, read:project-category, read:project | read:jira-work |
| `GET /rest/api/3/resolution/search` (REFERENCE) | read:resolution | read:jira-work |
| `GET /rest/api/3/issueLinkType` (REFERENCE) | read:issue-link-type | read:jira-work |
| `GET /rest/api/3/users/search` (probe, REFERENCE) | read:user, read:application-role, read:avatar, read:group | read:jira-user |
| `GET /rest/agile/1.0/board` (probe, REFERENCE) | read:board-scope:jira-software + read:project:jira | — |
| `GET /rest/agile/1.0/board/{id}/configuration` (probe, REFERENCE) | read:board-scope.admin:jira-software + read:project:jira | — |
| `GET /rest/agile/1.0/board/{id}/sprint` (probe, REFERENCE) | read:sprint:jira-software | — |

Caveat: the spec's granular lists are evidently not all enforced (a token missing several of them
passed most probes), so the real failures are the ground truth and the spec is the best guide we
have. Open question: the observed `boards` 401 is unexplained, because it is unknown whether that
token carried `read:board-scope:jira-software`.

### Project field schemes: `GET /rest/api/3/projects/fields` (optional, experimental)

The metrics-config Fields tab used to list every custom field on the site (1,344 on the first real tenant). The REFERENCE
stream's optional `PROJECT_FIELDS` step (after `PROJECT_STATUSES` and `ISSUE_TYPE`) records which fields each configured
project's field scheme carries, so the editor lists those by default.

- **Endpoint.** `JiraClient.projectFields(projectId, workTypeIds, startAt, maxResults)`; `projectId` and `workTypeId`
  (an issue-type id) are REQUIRED and the latter REPEATS (`?projectId=11562&workTypeId=11434&workTypeId=10000`), so
  `JiraHttp.request` has a `repeatedQuery: List<Pair<String, String>>` beside its `Map` query (values are appended
  through Ktor's parameter builder and kept across retries; nothing is concatenated into the URL, so errors and logs
  still carry host+path only). Observed shape (real tenant, 2026-10-08): the common `startAt` envelope
  (`JiraStartAtPage`: `self`, `nextPage`, `maxResults`, `startAt`, `total`, `isLast`, `values`), one row per
  (field, work type), system fields included: `{"fieldId":"aggregateprogress","projectId":11562,"workTypeId":11434,"isRequired":false}`.
  Flow asks for 100 per page, honours `isLast`/`total` and the page size it actually gets, and sends the work types in
  groups of 25. EXPERIMENTAL: Atlassian may change or withdraw it. Scopes as documented: `read:field:jira` +
  `read:field-configuration:jira`; the real tenant answered 200 with the token Flow already used.
- **Stored entity.** One `PROJECT_FIELDS` entity per configured project key (`raw.jira_entities`, entity id = the key),
  payload `{"projectKey":…,"projectId":…,"fieldIds":[sorted, distinct],"epicFieldIds":[…],"taskFieldIds":[…]}` — the (field,
  work type) rows collapsed to field-id sets: the union (`fieldIds`, unchanged and always present) plus the epic/task split.
  A field is an **epic field** when any work type at hierarchy level 1 carries it, a **task field** when any at level 0 or -1
  does (a sub-task counts with its task — D2 rolls it up); a work type missing from the hierarchy counts as a task type, one
  above level 1 (a Premium "Initiative") counts for neither (it stays in the union). The level comes from the stored
  `ISSUE_TYPE` entities (`JiraNormalizer.issueTypeHierarchy`, keyed by issue-type id = the row's `workTypeId`).
  **Step order**: `ISSUE_TYPE` is stored BEFORE `PROJECT_FIELDS` (the `JiraEntityKind` declaration order is the REFERENCE
  step order; `ISSUE_TYPE` moved up one slot). A resumed cursor names a step and resume restarts there (every step is
  idempotent) — except one naming `PROJECT_FIELDS` that lacks `stepOrder` (written under the old order, when it ran before `ISSUE_TYPE`; every cursor written now carries `stepOrder: 2`): resuming
  after the moved-up `ISSUE_TYPE` would skip it and the end-of-pass sweep would tombstone every issue type, so it restarts the
  pass at step 0, like an old `PRIORITY` cursor. The split keys are OMITTED — only the union is stored — when no `ISSUE_TYPE` entity exists or
  a row has no numeric `workTypeId`; a payload stored before the split existed (or with only one of the two keys) is read
  as "unknown split": `JiraProjectFields.parse` returns the union for both scopes. Inputs are read off the entities stored
  earlier in the same pass: the project id from the `PROJECT` entity of that key, the issue-type ids from that key's
  `PROJECT_STATUSES` payload (one array entry per issue type).
- **Optional by design.** A project's step is SKIPPED, never failing the SYNC, when its inputs are missing, when the call
  fails with ANY `JiraFetchException` except `BLOCKED_HOST` (401/403 — a missing scope is a 401 on a scoped token —, 404
  endpoint withdrawn, `INVALID_RESPONSE` for any other 4xx / a body that is not the envelope / a row without a string
  `fieldId`, and also 5xx after retries, `TIMEOUT`, `RATE_LIMITED`, `REDIRECT`, `LIMIT_EXCEEDED`, `CURSOR_EXPIRED`), or when the
  response lists no field at all (any page's bad row discards the whole project: the previous entity is never merged with a
  partial answer). A skip logs ONE warn (project key, code, status; never a query), ticks the `projectFieldsSkipped`
  progress counter (one per skipped project, `ingestion.md` "Progress counters") and `touch`es the
  project's previous entity (`JiraRawStore.touchEntity`: only `last_seen_at`), so the end-of-pass tombstone sweep does NOT
  tombstone it — the last known scheme survives a transient failure; a key no longer configured is swept as usual.
  A `BLOCKED_HOST` (an SSRF-guard rejection), lease loss / cancellation and non-Jira failures (database errors) still
  propagate like any reference step. Paging trusts a non-null `isLast` over `total` (which may be absent/0).
- **Profile.** `DataProfile.schemeFieldIds` = the sorted union over the live `PROJECT_FIELDS` entities of the CURRENT
  project keys, `null` (unknown) unless EVERY current key has one (a partial union would hide a project's fields);
  `schemeEpicFieldIds`/`schemeTaskFieldIds` follow the same rule over the split sets (a project whose payload predates the
  split contributes its whole scheme to both). The options endpoint turns them into each custom field's
  `inScheme`/`inEpicScheme`/`inTaskScheme` (`.claude/docs/metrics.md`). `README.md` lists `read:field-configuration:jira` as
  the recommended optional scope.
- **Per-level workflows.** `GET /project/{key}/statuses` carries one entry per issue type, each with the issue type's `id`; the
  profile (`JiraProfile`) maps those ids through the same `ISSUE_TYPE` hierarchy (`hierarchyBucket`, `norm/Hierarchy.kt`) into
  `epicWorkflowStatusIds` / `taskWorkflowStatusIds` (`ingestion.md` "Data profile"); both stay empty while no `ISSUE_TYPE`
  entity exists. On the real tenant epics have their own workflow, distinct from every other type's.

### Decision: the REFERENCE stream no longer fetches priorities (2026-10-08)

`GET /rest/api/3/priority/search` has no granular scope in Atlassian's spec, only the admin classic
`manage:jira-configuration`, so a read-only scoped token likely cannot call it. Raw PRIORITY entities
had no downstream reader (an issue's priority comes from its own fields), so the step was dropped.
The `JiraEntityKind.PRIORITY` value stays, because old raw rows and cursor JSON carry it. If a
consumer ever appears, use `GET /rest/api/3/priority/{id}` or `GET /rest/api/3/priority`
(`read:priority:jira`).

## Status category shapes

`GET /rest/api/3/statuses/search` (the REFERENCE `STATUS` kind) returns `statusCategory` as a plain string enum
(`"TODO"`, `"IN_PROGRESS"`, `"DONE"`, e.g. `{"id":"10135","name":"Abandoned","scope":{"type":"GLOBAL"},"description":"","statusCategory":"DONE"}`),
while an issue's `fields.status`, `/rest/api/3/status` and project statuses carry the object form
(`{"id":2,"key":"new","name":"To Do",...}`). `JiraNormalizer.statusRefs` accepts both (the enum, or the object's `key`
`new`/`indeterminate`/`done`); anything else, including `UNDEFINED`, a missing or null value or another JSON type,
maps to `UNKNOWN` and never throws. The stub serves the real string form for `statuses/search` (`statusSearchJson` in
`sample-data/jira/generate.mjs`) and the object form everywhere else (`statusJson`).

## ISSUES stream: fields and JQL

`jira/JiraIssuesStream.kt` (v0.2.0 plan §7, plan commit 6) pages `GET /rest/api/3/search/jql` with
`fields=*all` (`ISSUE_SEARCH_FIELDS`). Unlike the retired `/search`, `search/jql` returns ONLY `id` per issue when
`fields` is omitted; the code first omitted it, on the old endpoint's default, and the first real tenant's SYNC failed
on issues with no `fields` at all (the stub had mirrored the wrong default). Normalization (`JiraNormalizer.kt`)
needs both every system field and every discovered custom field (`schema.custom`-tagged, e.g. Sprint/Story
Points/Flagged/Team), and Jira offers no cheaper "all system + all discovered custom" shape than asking for
everything; `*all` is also what `GET /issue/{id}` returns by default, so the index-gap path stores the same document.
The stub's full-document pages match only `fields=*all`, so a search without `fields` finds nothing there. The
Test-connection `search` probe reads only the first issue's id and asks for `fields=id`.

**JQL shapes** (`jira/JiraJql.kt`, project keys pre-validated by `PROJECT_KEY_PATTERN`
`^[A-Z][A-Z0-9_]{1,9}$`, so no quoting/escaping is ever needed):

- `scope(projectKeys)` — `project in ("A","B")`, the base every other builder starts from.
- `incremental(projectKeys, sinceMinutes)` — the ISSUES stream's own query:
  `<scope> AND updated >= "-Nm" ORDER BY updated ASC` (a TZ-free RELATIVE bound — Jira's absolute
  JQL dates are TZ-sensitive, a spike-identified risk this sidesteps entirely).
- `incremental(clauses: List<Clause>)` — the same query when projects need different windows (a
  scope catch-up, `.claude/docs/ingestion.md` "Covered scope and the catch-up clause"):
  `(<scope A> AND updated >= "-Na") OR (<scope B> AND updated >= "-Nb") ORDER BY updated ASC`, one
  parenthesised term per `Clause(projectKeys, sinceMinutes)`. A single clause renders exactly as the
  two-argument form (no parentheses), pinned by `JiraJqlTest`.
- `reconcile(projectKeys, sinceMinutes)` — `<scope> AND updated >= "-Nm" ORDER BY id ASC`; the
  RECONCILE stream's own id-sweep query (`jira/JiraReconcileStream.kt`, V12, see
  `.claude/docs/ingestion.md` "RECONCILE stream") — paged via `search/jql` with `fields=id` (only
  the id, never the full document; the sweep only needs to know WHICH issues Jira still reports in
  scope). The same relative bound as `incremental`, with `N` = WHOLE minutes (rounded down, so the
  sweep never starts before `backfillFrom`) from the connection's `backfillFrom` to the pass start:
  the sweep covers exactly the window the ISSUES stream's first run fetched, so it never lists (and
  the index-gap path never fetches one by one) issues older than anything Flow ingested. The text is
  computed once per pass and kept in the RECONCILE cursor — Jira ties a page token to its query.

Two more `jira.*` config keys are consumed for the first time by this stream (both already declared
in `application.yaml` since an earlier commit, unread until now): `jira.pageSize` (default 100,
1..500 — the `search/jql` `maxResults` per page; the stub ignores it and returns its own fixed-size
pages regardless) and `jira.incrementalOverlapMinutes` (default 10, 0..1440 — the re-widening
window past the last watermark described above).

### Oversized issue page (`LIMIT_EXCEEDED`)

A `fields=*all` page of heavy issues can exceed `jira.maxResponseBytes` (`JiraHttp.readBounded` throws
`LIMIT_EXCEEDED`, never retried). `JiraIssuesStream` answers by re-requesting the SAME page, with the same
`nextPageToken` and JQL but `maxResults` halved (`size / 2`, floor 1), as often as needed. The smaller size lasts for the
rest of that stream invocation only (it is not in the cursor; the next run starts at `jira.pageSize`), each halving logs
one WARN (data source id, old and new size), and a 1-issue page that is still too large fails the run with
`LIMIT_EXCEEDED`. `CURSOR_EXPIRED` restarts keep the reduced size.

**Assumption (unverified against a real tenant):** `nextPageToken` is an opaque position token, and Atlassian's
`search/jql` documents `maxResults` as a per-request value, so the same token may be sent with a different `maxResults`.
Nothing in the client or docs says the token binds the page size (it is documented to bind the query, which we never
change). If a tenant answers 400/410 to the re-asked token, that surfaces as `CURSOR_EXPIRED` and restarts the run from
the last watermark, at the reduced size. A `CURSOR_EXPIRED` that immediately follows a halving does NOT count against
`MAX_CURSOR_RESTARTS`: halvings are bounded on their own (log2 of the page size, at most 9), so the worst case is a
re-read, not a stall. Every other `CURSOR_EXPIRED` restart is counted as before.

**Known trade-off.** The reduced size is deliberately not persisted, so a tenant whose pages are always oversized
re-pays the halving ladder on every run: up to 6 oversized responses (sizes 100, 50, 25, 12, 6 and 3 all over the cap, then size 1), each up to
32 MiB read before it is discarded. Persisting the size would add cursor state that must be reset when the data
shrinks; that is not worth it until a real tenant shows the cost.

Pinned by `JiraIssuesPageSizeTest` (the ISSUES stream behaviour above only); the CHANGELOGS/WORKLOGS answer is next.

### Embedded users in changelog/worklog payloads

Jira embeds a full `USER` object as a history's `author` and a worklog's `author`/`updateAuthor`. The client returns it as is;
the raw writers (`JiraRawWrites.insertChangelog`/`upsertWorklog` and their batch forms) store only `{accountId, displayName, accountType}` of it
(`jira/JiraUserReferences.kt`; the reasons, the exception to "exactly as Jira returned it" and the no-backfill decision:
`persistence.md` "The Jira changelog/worklog raw store (V11)"). Every reader takes `author.accountId`, which survives.

### Batched raw writes (`jira/JiraRawBatchWrites.kt`)

The four raw writers each have a whole-page form behind the `JiraRawStore` facade: `upsertIssues`, `upsertEntities` (one kind per
call), `upsertWorklogs` and `insertChangelogs`. They leave **exactly the rows and return exactly the outcomes (in input order)** of N
calls to the single-row `upsertIssue`/`upsertEntity`/`upsertWorklog`/`insertChangelog`, which stay (the ISSUES one-by-one data-error
fallback, RECONCILE's index-gap fetch, the REFERENCE per-project steps, the WORKLOGS incremental feed, the tests). One rule serves
both: the pure `classifyUpsert(storedHash, newHash, tombstoned)` in `JiraRawWrites.kt` (no stored row inserts; a tombstoned row seen
again is resurrected whatever its hash; the same sha256 on a live row is unchanged; any other difference is a change), and the
per-row `change*Row` updates (a CHANGED/RESURRECTED row keeps its single-row `UPDATE`: a minority outside a backfill, and
`upsertRows` cannot express it - an omitted NOT NULL `first_seen_at` fails the INSERT half before the conflict is looked at).

Shape per call: ONE `SELECT key, sha256, tombstones … WHERE key IN (…) ORDER BY key FOR UPDATE` (chunks of 1,000 keys, sorted before chunking so rows are locked in
key order across chunks), then the INSERTED rows as multi-row `insertRows`, the UNCHANGED rows as ONE `UPDATE … WHERE key IN (…)` of the single
column the single-row path touches (`fetched_at`, an entity's `last_seen_at`), and the changed ones row by row. A key repeated inside
one call is written by the single-row path after the batch, so its outcome is judged against the first write, as in a sequential run.
`insertChangelogs` is `Table.insertRowsIgnoringConflicts` (`infra/db/MultiRowInsert.kt`: `INSERT … ON CONFLICT DO NOTHING`, the batch
`insertIgnore`): a history already stored, or repeated in the batch, is dropped and the first write wins. Every method joins an
enclosing transaction like the single-row ones, so a stream's `StreamContext.transaction { }` still lands the writes and the cursor
together; no new mutation kind, so no new audit event.

Who uses them: the ISSUES page (`writePage`, inside the page transaction, so a data error still aborts the page and falls back to the
single-row loop), the CHANGELOGS bulk answer and per-issue fallback (one call per chunk/issue), the REFERENCE `storeEntities` pages, and
the WORKLOGS per-issue backfill. The backfill fetches the claimed batch of 100 issues CONCURRENTLY (`fetchAheadInOrder`, `jira/JiraFetchAhead.kt`)
but settles them in claim order - heartbeat per issue, skip budget, the oversized issue skipped for the run - then writes what it fetched in ONE transaction per batch: `upsertWorklogs` for all of it plus a list
`markWorklogsSynced` (flushed early once a batch holds 5,000 worklogs, `BACKFILL_FLUSH_WORKLOGS`, so long-lived tickets stay bounded in
memory). **Failure semantics:** progress is committed per flush, not per issue. The worklogs and their synced stamps share the
transaction, so an issue is never stamped without its worklogs. A fetch failure that ends the stream (a non-oversized error on issue N,
or the skip budget) first flushes the issues fetched before it - stored and stamped as the per-issue commits did - then rethrows, so a
persistently failing issue never throws its batch's progress away on every run (`JiraOversizedBatchTest`). A failing write or a lost
lease leaves the unflushed issues unstamped: the next run claims them again (`worklogs_synced_at` is still NULL) and re-fetches them -
at most one batch of GETs is repeated, never a gap or a duplicate. Why: a stub
SYNC was ~11,800 SQL statements in ~1,540 transactions (`build-times.md` WHY 21); the stub-sync fixture cost fell from ~11 s to ~5 s,
the rest being the ~1,330 HTTP calls and PROCESS.

**Concurrent per-issue fetch (`build-times.md` WHY 33).** The look-ahead window IS `JiraClient.maxConcurrentRequests` (a limit of 1 is the
strictly sequential loop); the semaphore, not the window, decides what is in flight. Only the fetch is concurrent: the heartbeat, the
skip budget, `gaveUp`, the progress counters, the `FetchedBatch` and the flushes (incl. the 5,000-worklog early flush) all run in the one
consuming coroutine in claim order, so the rows, stamps and flush points are those of the sequential loop. A fetch failure arrives at its own
position: issues before the FIRST failing one in claim order are flushed and stamped, then the failure is rethrown (the earliest in claim
order wins, not the first in time); issues after it may already have been fetched and are dropped, to be re-fetched next run - at most
the window's worth of extra issues. A page chain of one issue stays sequential. Fetches are children of the stream's scope: a cancelled job,
a lost lease or a thrown failure cancels the calls in flight and waits for them before it leaves (no `GlobalScope`; `catchingFailures`
rethrows `CancellationException`).

*Memory.* The window bounds issues, not their size, so a second bound counts worklogs: no fetch starts while the finished-but-unconsumed
results already hold more than `BACKFILL_FLUSH_WORKLOGS` (5,000) worklogs (`fetchAheadInOrder`'s `budget`). The worst case held at once is
therefore the `FetchedBatch` (< 5,000 worklogs plus the issue that crossed the line) + the unconsumed results (<= 5,000 worklogs, plus the
results of the fetches that were already running when the budget was crossed: at most `limit` issues) + `limit` fetches in flight. An
issue is not bounded by a worklog count - its pages are (each response <= `jira.maxResponseBytes`, default 32 MiB, and `readBounded`
transiently holds a response ~3-4 times over: the buffer, its copy, the decoded string and the JSON tree), so the pathological ceiling is
`limit` x (one issue's worklogs) on top of the two 5,000-worklog bounds; a normal tenant (worklogs ~1-2 KB of JSON) holds a few tens of
MB at the peak. The sequential loop's worst case was the `FetchedBatch` plus one issue.

**Against a real tenant** the pattern changes from one `GET /issue/{id}/worklog` at a time to at most `jira.maxConcurrentRequests`
(default 4) at a time, process-wide, through the same guarded client, auth, `Retry-After` and backoff; Flow authenticates with an API
token, whose traffic Atlassian's 2026 points-based rate limits do not cover, so the limit that applies is the per-call 429/`Retry-After`
handling above. A 429 on any call of the tenant pauses its other calls too (the shared pause above), so the first concurrent run does
not hammer a rate limit; `JIRA_MAX_CONCURRENT_REQUESTS=1` restores the sequential backfill.

### Oversized changelog/worklog batch (`LIMIT_EXCEEDED`)

The same failure shape on the two batch calls: a `changelog/bulkfetch` answer for a batch of `jira.changelogBulkSize`
issue ids, or a `worklog/list` answer for up to 1000 worklog ids, over `jira.maxResponseBytes`. Both streams answer
like the ISSUES page, with the batch size run-local (never in the cursor; the next run starts at the configured size) and one
WARN per halving (data source id, old and new size; `JiraChangelogStream`/`JiraWorklogStream`; shared helpers
`halvedBatchSize`/`isOversized` in `JiraOversized.kt`):

- **CHANGELOGS.** The batch is claimed afresh at the halved size (`staleChangelogIssueIds(.., limit)`; nothing was marked
  synced, so the first half is exactly the ids just asked and the second half is claimed next), `size = max(1, min(size,
  batchSize) / 2)`. A `LIMIT_EXCEEDED` on any `nextPageToken` page discards the batch's partial answer and halves. This is
  not a bulk-unavailable verdict: no `bulkUnavailableUntil` is written. At a batch of ONE issue the stream falls through to
  that issue's per-issue `GET /issue/{id}/changelog` (`startAt` pages, far smaller than one bulk page), which is also how a
  single issue is read when bulk is unavailable.
- **WORKLOGS (`worklog/list`).** The page's ids go out in batches of at most the run's size (starts at 1000, the endpoint's
  ceiling, and only shrinks, across pages of the same run); the results of the batches are concatenated before the scope
  filter, so the page, its cursor advance and its counters are unchanged.

**The single-id rule: skip, count, warn; never stall.** An id that is still over the cap alone is isolated, the way PROCESS isolates a
bad issue (`issuesFailed`) rather than failing the run: it is logged (one WARN, id and data source, never a URL), counted in a
progress counter and left out of THIS run. What "left out" costs differs by path:

| Path | Counter | Consequence |
|---|---|---|
| CHANGELOGS, an issue over the cap on bulk AND on the per-issue endpoint (or on the per-issue endpoint alone, bulk unavailable) | `changelogsSkipped` | The issue stays stale (`changelog_synced_at` unchanged), is not claimed again by this run (a run-local exclusion set in the claim scan), and is retried by the next run. |
| WORKLOGS backfill, an issue whose per-issue worklog page is over the cap | `worklogsIssuesSkipped` | Same: stays unsynced, retried next run. |
| WORKLOGS incremental, one worklog over the cap on `worklog/list` | `worklogsSkipped` | The feed cursor advances past the page, so that worklog is missing until Jira lists it as updated again. |

Under the threshold the job still ends SUCCEEDED; the counters on the sync status are the operator's signal.

**Fail-safe: too many skips is a too-small cap, not an outlier.** A stream that skips MORE than `max(10, 10% of the ids it
attempted this run)` stops and fails with the existing `LIMIT_EXCEEDED` (`JiraFetchException`, so the job ends FAILED and
`sync_jobs.error_detail` reads e.g. `Jira fetch failed: LIMIT_EXCEEDED: CHANGELOGS skipped 37 of 120 issues over
jira.maxResponseBytes - is the cap too small?`). Without it a mis-set `jira.maxResponseBytes` (where every response is
oversized) would skip everything and the job would SUCCEED. "Attempted" is the ids that finished plus the ones skipped, so a
halving's repeated asks never count twice, and each counted unit has its own budget (`SkipBudget` in `JiraOversized.kt`,
run-local): CHANGELOGS issues; WORKLOGS backfilled issues; WORKLOGS listed worklogs (across the feed's pages of one run).
The run-local exclusion set of the claim scans can therefore never hold more ids than that threshold. A skip is checked as it
happens, so a run that opens with 11 oversized issues fails even if thousands of fine ones would follow: that is deliberate
(an operator who really has that many giant issues raises the cap).

**What a skipped changelog does downstream (PROCESS).** A skipped issue's `changelog_synced_at` stays `NULL` (never fetched) or
old, and PROCESS does not wait for it: the issue is flagged `needs_processing` by ISSUES and is normalized from whatever
`raw.jira_changelogs` rows exist. `Tiling.statusIntervals` with NO history yields ONE open interval in the issue's CURRENT
status since `created_at` (source `CREATED`) and raises no anomaly: the issue looks as if it never changed status, so its
cycle time, flow efficiency and stage times are skewed (a Done issue reads as Done since creation) until a later run fetches
its changelog (the stamp then sets `needs_processing` and the next PROCESS rebuilds it). With an OUTDATED history (the issue
changed after an earlier fetch) PROCESS tiles the old chain: `STATUS_MISMATCH_WITH_CURRENT` is flagged when the issue's current
status differs from the chain's last, and is silent when only non-status fields changed. No migration or new flag marks the
row; the signals are one WARN per skipped issue (issue id and data source id) and the job's `changelogsSkipped` counter.
The same holds for a worklog-skipped issue (no worklog time until fetched).

The per-issue endpoints have no page-size
parameter in the client (`startAt` only), so an oversized per-issue page is isolated rather than halved. The reduced size is
never regrown within the run (it only shrinks, even if the batches that follow would have fit) and is not persisted: the
next run starts at the configured size, the same trade-off as the ISSUES page (up to ~log2(size) discarded oversized
responses per run on a tenant whose batches are always oversized; not worth cursor state until a real tenant shows it).

Pinned by `JiraOversizedBatchTest` (real HTTP over `JiraStubServer` overrides on the exact id lists and a tiny `maxResponseBytes`).

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

## Parent field spellings (open real-tenant question)

`jira/JiraNormalizer.kt`'s PARENT tiling (v0.3.0 M1 commit 2, V14 — see "Normalized layer" in
`.claude/docs/ingestion.md`) detects a parent-move changelog item three ways: `fieldId == "parent"`
(the sample stub's own spelling, and Jira Cloud's current field, which replaced the older Epic
Link), the REFERENCE stream's discovered legacy Epic Link custom field id (`gh-epic-link`,
`JiraFieldIds.epicLinkFieldId`, matched by `schema.custom` the same way Sprint/Rank/Team are), or
the changelog item's `field` display name being `Parent`/`IssueParentAssociation`/`Epic Link`. Only
the first path is exercised by `sample-data/jira-stub` (`generate.mjs` emits parent-move history
items as `{field: "Parent", fieldId: "parent", ...}`) — **the other two are defensive, unconfirmed
against a real Jira Cloud tenant.** Before pointing this connector at a real site, verify with a
`GET /issue/{id}/changelog` capture on a tenant that has migrated an issue between epics (or one
still using the older Epic Link field) which spelling it actually emits, and drop whichever of the
three paths turns out unused.

## Timestamps

A real-tenant blocker found after phase 2: `java.time.Instant.parse` only accepts strict ISO-8601
(a `Z` or a colon-delimited `+HH:MM` offset) — it REJECTS the form Jira Cloud's REST API v3 actually
returns for an issue's own `created`/`updated`/`resolutiondate`, a changelog history's `created`,
and a worklog's `started`/`created`/`updated`: `yyyy-MM-dd'T'HH:mm:ss.SSSZ`, i.e. a colonless offset
(`+0000`), verified against a real tenant —
`Instant.parse("2024-01-15T10:20:30.123+0000")` throws `DateTimeParseException: ... could not be
parsed at index 23`. `sample-data/jira-stub` used to emit the more convenient `Z` form everywhere
(so every test passed while the first real SYNC against a real tenant would have failed in the
ISSUES stream), and now emits both real shapes — see `sample-data/README.md`'s "Timestamp formats".

`server/src/main/kotlin/jira/JiraTime.kt`'s `parseJiraInstant`/`parseJiraInstantEpochMillis` is the
ONE parser every Jira-sourced timestamp string goes through, accepting the REST API v3 form
(`+0000`/`-0500`, no colon), the Agile API/ordinary ISO-8601 form (`Z`, `+02:00`), and both with or
without fractional seconds — a `DateTimeFormatterBuilder` appending `DateTimeFormatter
.ISO_LOCAL_DATE_TIME` (the date/time/optional-fraction part) then the bracketed offset patterns
`[XXX][XX][X]` (colon, colonless, then bare-hour/`Z`, tried in that order), parsed to an
`OffsetDateTime` and converted with `toInstant()`. A malformed value throws
`java.time.format.DateTimeParseException` — the SAME unchecked exception type `Instant.parse`
itself always threw, so every call site's existing failure handling is unchanged: left uncaught in
a stream (ISSUES/CHANGELOGS/WORKLOGS/RECONCILE), it fails that job exactly as a bad timestamp
always would have; `jira/JiraProcessStream.kt`'s per-issue `catch (failure: Exception)` still
isolates it to that one issue during PROCESS, never aborting the rest of the batch.
One more shape, found by the first real SYNC: `POST /changelog/bulkfetch` returns a history's `created` as epoch
MILLIS (a JSON number, `1790330188061`), while the per-issue `/issue/{id}/changelog` returns the text form. Raw
histories are stored verbatim, so `parseJiraInstant` also accepts an all-digit value (12–18 digits) as epoch millis;
the stub's bulkfetch chunks emit the number, its per-issue pages the text.
**Never call `Instant.parse` directly on Jira-sourced text** — always go through `JiraTime.kt`.
Covered by `JiraTimeTest` (every accepted shape, with and without millis, plus the malformed-value
failure case).

## Data profile: reuse of raw entities

The data profile (v0.2.0 plan §8/§9/§12 item 9, `jira/JiraProfile.kt`, see
`.claude/docs/ingestion.md` "Data profile") makes no outbound Jira call of its own — it is pure
aggregation over what REFERENCE/ISSUES/CHANGELOGS/WORKLOGS and PROCESS already stored. Two raw
entity kinds get a NEW reader here, on top of the uses already documented above:

- **`PROJECT_STATUSES`** (`GET /project/{key}/statuses`, stored by REFERENCE, see "Streams" in
  `.claude/docs/ingestion.md`) — until this commit its only consumer was the REFERENCE stream's own
  upsert. The profile's `workflows` section is the first reader of its PAYLOAD: `JiraProfile`'s
  `parseProjectStatuses` reads the per-issue-type status list straight off the stored `raw.jira_entities`
  payload (`JiraRawStore.entityRowsByKind`, which — unlike `entityPayloadsByKind`, PROCESS's own
  reader — keeps the `entity_id` alongside the payload, since the profile needs to know WHICH
  project a `PROJECT_STATUSES` row belongs to) and reports it as `referenceStatusNames`, alongside
  the statuses ACTUALLY observed in that project/type's own tiled status intervals.
- **`BOARD_CONFIGURATION`** (`GET /board/{id}/configuration`) — already PROCESS's own input since
  V13 (`JiraNormalizer.boardRefs`, rebuilding `norm.boards`/`norm.board_columns` every PROCESS run,
  see "Normalized layer" in `.claude/docs/ingestion.md`); the profile's `boards` section reads only
  the already-rebuilt `norm.boards`/`norm.board_columns` rows back (`WorkItemStore.allBoardRefs`),
  never the raw `BOARD_CONFIGURATION` payload directly — no new Jira-shape parsing here, only a new
  consumer of PROCESS's existing output.

## RECONCILE stream: endpoints

Landed with the RECONCILE stream (plan §7/§12 item 7, plan commit 7, V12 — see
`.claude/docs/ingestion.md` "RECONCILE stream" for the full behavior):

- **`GET /rest/api/3/search/jql` with `fields=id`** (`JiraClient.searchJql`, the same method the
  ISSUES stream uses, `jql = JiraJql.reconcile(projectKeys, sinceMinutes)`) — the daily id-sweep, paged at 5000
  ids per page. Asking for `fields=id` only (never `fields=*all`, unlike the ISSUES stream) is
  deliberate: the sweep only needs to know WHICH issue ids Jira still reports in scope, not their
  content.
- **`GET /rest/api/3/issue/{id}` with `fields=project,key`** (`JiraClient.issue(idOrKey, fields)`) —
  the anti-join's missing-issue probe: a stored issue this pass never saw is checked individually
  with only the two fields the deleted/moved-out decision needs (`RECONCILE_ISSUE_FIELDS` in
  `jira/JiraReconcileStream.kt`), never the full issue document.
- **`GET /rest/api/3/issue/{id}`, no `fields` param** (`JiraClient.issue(idOrKey)`, `fields = null`
  — the default) — the anti-join's index-gap fetch: an id the sweep saw that `raw.jira_issues` never
  stored is fetched in FULL (same shape the ISSUES stream itself upserts) and written through
  `JiraRawStore.upsertIssue`.

`JiraClient.issue`'s `fields` parameter (added this commit) is optional and defaults to `null` (the
full document) precisely so the ISSUES/index-gap-fetch call sites need no change — only the
missing-issue probe passes a value.

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
`UPSTREAM_UNAVAILABLE` (5xx or IOException - a DNS failure included - retries exhausted, OR the next backoff would cross that
same total-request deadline. Recorded decision (2026-10-16): a typo'd tenant on Test connection, whose `tenant_info` host does not
resolve, is retried like any DNS failure, so its `tenant_info` row reads `TIMEOUT` once the 10s probe budget runs out inside the
backoff (and the later required rows `NOT_FOUND`, there being no gateway) rather than failing at once; there is no separate
`DNS_UNRESOLVED` code. On the SYNC path, with its longer request deadline, a persistent DNS failure ends as `UPSTREAM_UNAVAILABLE`) · `TIMEOUT` (request/probe timeout) · `INVALID_RESPONSE` (malformed
JSON, an unexpected shape — every `.jsonObject`/`.jsonArray`/`.jsonPrimitive` cast is routed through
one decode helper so a mismatch is always this code, never an uncaught exception — an invalid
`cloudId` shape, or a Jira-supplied issue id/key that doesn't match the expected path shape) ·
`LIMIT_EXCEEDED` (response over `jira.maxResponseBytes`; the ISSUES stream answers a `search/jql` page with a smaller one, see "Oversized issue page (`LIMIT_EXCEEDED`)" above; CHANGELOGS/WORKLOGS halve their batch, see "Oversized changelog/worklog batch") · `BLOCKED_HOST` (the outbound guard
refused the host (not allow-listed) or a resolved address (blocked range) - NEVER a DNS failure, which is retried and ends as
`UPSTREAM_UNAVAILABLE`; `BlockedHostException` extends `UnknownHostException`, the
`Dns` contract's own checked type, so OkHttp's async call path delivers it to Ktor unwrapped instead
of re-wrapping it as a generic `IOException`; a single attempt, never retried) · `REDIRECT` (a 3xx —
never followed) · `CURSOR_EXPIRED` (a `search/jql` `nextPageToken` page answering 400/410 — see
"`CURSOR_EXPIRED` detection" above; the ISSUES stream restarts from the last watermark rather than
propagating it, up to a bounded number of restarts).

Every attempt (across every retry) is additionally bounded by a TOTAL per-request deadline
(`jira.requestDeadlineSeconds`, default 180s, config-validated via `requireConfigInt`) — separate
from `jira.requestTimeoutSeconds`, which only bounds a SINGLE attempt — so retries cannot keep one
call going for as long as `jira.maxRetries` deep exponential backoff sums to (~10 minutes) (a call hands its `jira.maxConcurrentRequests` permit back while it
backs off or waits out its tenant's pause, so the deadline bounds the call's own time, not a permit).

## Testing

- `JiraBackoffTest` — pure backoff math, injected clock/random.
- `JiraTimeTest` — `parseJiraInstant`/`parseJiraInstantEpochMillis` against every accepted shape
  (`+0000`, `+02:00`, `-0500`, `Z`, with and without fractional seconds) and the malformed-value
  `DateTimeParseException`.
- `JiraJqlTest` — `JiraJql`'s scope/incremental/reconcile builders (incl. the multi-clause incremental and the windowed reconcile text).
- `JiraScopeCatchUpTest` — the ISSUES stream's scope catch-up (first run, added/re-added project, earlier/later `backfillFrom`, legacy cursor, resume vs. a changed scope) and RECONCILE's index-gap guard, over a capturing fake `JiraClient`.
- `JiraReconcileWindowTest` — the windowed RECONCILE sweep over a scripted fake `JiraClient` (the sent JQL, which
  rows are probed, resume with a stored/legacy cursor) and `JiraRawStore.markOutOfScopeProjects` (locally
  tombstoned, idempotent, resurrected by a re-upsert; called at the start of the ISSUES stream).
- `OutboundGuardTest` — the allow-list, every blocked address range (injected resolver), the
  stub-host-only-in-dev rule, a boot test pinning production's `jira.stubBaseUrl` refusal, and a
  set of production-wiring integration tests (`buildGuardedJiraHttpClient`, the SAME builder
  `jira/Jira.kt` uses) against the real, loopback `JiraStubServer` over the real OkHttp engine:
  `BLOCKED_HOST` in one attempt/no retry, a disallowed host never reaching the resolver, `Proxy.
  NO_PROXY`, and a real 3xx never followed; plus the DNS-failure cases: a plain `UnknownHostException` (exact class, no
  `outbound.blocked` audit, one host-only WARN) for a failing resolver and for an empty answer, a resolver that fails once
  and then answers (the retry succeeds), and one that always fails (`UPSTREAM_UNAVAILABLE` after `maxRetries` retries, and
  `skipsProjectFields` true).
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
- `JiraIssuesPageSizeTest` — the oversized-page halving over real HTTP against `JiraStubServer` overrides and a tiny
  `maxResponseBytes`: 8 to 4 to 2 on the same token, the size kept for the run and reset for the next, a first page
  halved with no size in the persisted cursor, the 1-issue failure leaving the cursor at the last good page, and a
  `CURSOR_EXPIRED` right after a halving (restart at the smaller size, not counted against `MAX_CURSOR_RESTARTS`).
- `JiraIssuesSkipTest` — the ISSUES stream's "skip, loudly" rule over real HTTP against `JiraStubServer` overrides: a page with
  an issue the database rejects (an over-long `issue_key`) lands its other issues and the next page, advances the cursor and counts
  `issuesSkipped` (WARN names key and id, never the payload); the skipped issue is stored by a later run once Jira serves it fine;
  an issue with a NUL in its description is stored (U+FFFD), not skipped; a non-data failure (an unparseable `updated`) still fails
  the run with the cursor untouched; an outage-class (SQLSTATE 08006) and a surviving class-23 failure are rethrown rather than skipped; a
  page of several issues that are ALL rejected fails the run; a control character in a skipped key never reaches the log; and RECONCILE's
  index-gap fetch skips the same still-bad issue (`indexGapSkipped`) and completes. An already-stored issue whose update fails keeps
  its old row (stale) until a later edit or a full resync.
- `JiraOversizedBatchTest` — the CHANGELOGS/WORKLOGS halving over real HTTP against `JiraStubServer` overrides keyed on the
  exact id list: a batch over the cap halved and the size kept for the run, a normal batch untouched, a single issue
  through the per-issue endpoint, an issue over the cap on both endpoints skipped (stays stale, `changelogsSkipped`), the
  same under bulk-unavailable, `worklog/list` halved and a single oversized worklog skipped, an oversized per-issue
  worklog page skipped (`worklogsIssuesSkipped`), a `LIMIT_EXCEEDED` on page 2 of a bulk answer discarding page 1, a
  non-oversized failure (500/401) propagating from every call unchanged, `halvedBatchSize` (short tail, floor 1) and the
  skip threshold (`SkipBudget`; a tiny cap where everything is oversized fails `LIMIT_EXCEEDED` for CHANGELOGS, the WORKLOGS
  backfill and the `worklog/list` feed).
- `JiraSyncPipelineTest` — the full `JiraConnector.run(SYNC)` (REFERENCE → ISSUES → CHANGELOGS →
  WORKLOGS) against `JiraStubServer`, asserting in-scope-reachable counts (never the whole-dataset
  `expected.json` figures, see `.claude/docs/ingestion.md` "Jira stub"): the omitted bulkfetch
  chunk's per-issue fallback setting the `changelogs` cursor's `bulkUnavailableUntil`, a second
  no-op SYNC changing no changelog/worklog row, `PURGE` draining all four raw tables, and the
  `jira-day2` scenario's out-of-scope worklog drop/count and tombstone. Also drives `JiraIssuesStream`
  directly for the ISSUES cursor's own fault-injection/`CURSOR_EXPIRED`/lease-loss mechanics (unrelated
  to CHANGELOGS/WORKLOGS, so run without the full connector's REFERENCE pass). Separately drives
  `JiraConnector.run(RECONCILE)` against the `jira-day2` scenario: the day2 deleted/moved issue ids
  tombstone correctly, flag `needs_processing`, and a second RECONCILE pass over the same drift is a
  no-op (idempotence); plus the index-gap case (an id the sweep sees that `raw.jira_issues` never
  stored gets fetched and upserted).
- `JiraHttpSharedPauseTest` - the per-tenant shared pause on a `MockEngine` with a virtual clock and a test-controlled sleeper: with a limit of 3,
  one call's 429 + `Retry-After: 5` leaves the tenant's queued calls without a request until the deadline (then they start at or after it);
  a 5xx with `Retry-After`, a 429 without it and an HTTP-date `Retry-After` pause the next call for the header / the computed backoff, a plain 5xx
  pauses nobody; a paused tenant's waiting call holds no permit (another tenant, and a keyless call, proceed at once with a limit of 1) and
  cancelling it sends no request; `HttpJiraClient` keys by cloud id (tenant A's 429 never delays tenant B on the same `JiraHttp`); a retry
  whose backoff plus extended pause outlast the call's deadline is not sent.
- `JiraFetchAheadTest` and `JiraWorklogConcurrencyTest` — the concurrent per-issue worklog fetch: `fetchAheadInOrder` (consumption in list order whatever order
  the fetches finish in, the window bound, the worklog budget holding the window back, a failure at its own position, the earlier failure in list order winning, cancellation reaching
  the in-flight fetches and never becoming an "outcome"); and the whole stream over the real `HttpJiraClient`/`JiraHttp` on a Ktor `MockEngine`
  that counts calls in flight at the wire (never more than `maxConcurrentRequests`, a limit of 1 strictly sequential, a mid-batch failure leaving
  the sequential loop's rows and stamps and the claim-order error, job cancellation and a lost lease cancelling the calls in flight).
- `JiraRawStoreTest` — `JiraRawStore`'s own write paths in isolation (incl. a NUL in an issue, entity, worklog or changelog payload: stored with U+FFFD, a re-write
  `UNCHANGED`): the append-only changelog
  dedup (`ON CONFLICT DO NOTHING` on a re-inserted history id), the worklog sha256 diff/tombstone/
  resurrection cycle, `staleChangelogIssueIds`/`staleWorklogIssueIds`'s claim-scan predicates, and
  `knownInScopeIssueIds`'s scope filter, and the batch writers (`upsertIssues`/`upsertEntities`/`upsertWorklogs`/`insertChangelogs`): a
  page mixing new/unchanged/changed/resurrected/repeated rows leaves the rows and returns the outcomes the per-row path does (two
  connections, same inputs, compared row for row), a 2,500-issue call crosses the `IN`-list chunk, the changelog dedup (already stored
  and repeated in the batch) and the list `markWorklogsSynced`.
