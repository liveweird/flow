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
concurrent WORKLOGS backfill never keeps firing siblings into a rate limit (`JiraHttpSharedPauseTest`). A response body is read bounded at `jira.maxResponseBytes` (default 8 MiB, "Response size cap" below;
Covenant's `ToadieGraphqlClient` shape) and refused before it is parsed when it carries too many structural tokens ("Node and depth ceiling") — `LIMIT_EXCEEDED` past either (the batch streams' answers: "Oversized issue page (`LIMIT_EXCEEDED`)" and "Oversized changelog/worklog batch" below).

**The rate-limit tally (`jira/JiraCallTally.kt`, `JiraHttpTallyTest`, `JiraTallyProgressTest`).** What Jira's throttling cost one sync job is counted by a
`JiraCallTally`, a coroutine-context element `JiraHttp.request` reads once per call (no parameter threads through the clients or the streams).
`JiraConnector.runSync`/`runReconcile` wrap the run, `resolveCloudId` included, in `withContext(JiraCallTally())`, so the streams' fetch-ahead children (`async`
inherits the context) count into the same tally; REPROCESS never calls Jira and takes none, and a call outside any tally (the Test-connection probes) counts
nothing. Four counters: `jiraRateLimited` (every 429 response, the final one that gives up included), `jiraBackoffMs` (the back-off a call is about to sleep,
`Retry-After` or the computed one), `jiraTenantPausedMs` (the tenant pause a call waits out that another call set) and `jiraNearLimitPauses` (each
`X-RateLimit-NearLimit` 1s pause). The times are what the sleeper was asked for, summed over concurrent calls, so they can exceed the job's wall time. They reach
`sync_jobs.progress` on every stream heartbeat through `StreamContext.extraProgress` ("Progress counters" in `ingestion.md`), only while non-zero. An attempt that
FAILS (a `RATE_LIMITED` give-up, say; a `StackOverflowError` too, the one `Error` handled) makes one last best-effort heartbeat under `NonCancellable` before the failure propagates, so the throttling that caused it is on the
job row, and a RETRIED attempt (attempt > 1) starts its tally from the counters the previous attempt flushed (`SyncJobClaim.priorProgress`), so a retry's first heartbeat
does not erase them (`JiraTallyProgressTest`).

## Test connection

`jira/JiraConnector.testConnection` (used by `POST /api/v1/data-sources/test` — ad-hoc, carries
`apiToken` — and `POST /api/v1/data-sources/{id}/test` — the stored, decrypted token; the latter
persists `cloudId` on success) probes each endpoint below in sequence, at most 10s each and 30s
total, NEVER throwing — every outcome is a `ConnectionTestRow {name, path, required, ok, status,
code, scopeHint}`. Only `field_search`, `bulkfetch` and `project_fields` are `required = false`: CHANGELOGS really does fall back
from `bulkfetch` to the per-issue endpoint on 404/405/410/501, and a real tenant's GA/scope status for it differs;
`project_fields` is an experimental endpoint whose absence only costs the metrics-config editor its default field
filter (see "Project field schemes" below); `field_search` is purely informational (no stream reads `GET /field/search`; "`GET /field`
stays one shot" below). A failure of any of them doesn't fail the *Test connection*. The three board probes are required, because a SYNC's
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
| `field_search` | `GET /rest/api/3/field/search` (one row asked for: `startAt=0&maxResults=1`; logs ONE INFO line `field/search total=N, values=V, isLast=B, field count=M`, M from the `field` probe) | **no** (runs after the board probes, just before `project_fields`) | `read:field:jira, read:field-configuration:jira` |
| `project_fields` | `GET /rest/api/3/projects/fields` (one row asked for; seeded with the first configured key's project id and first issue-type id) | **no** (runs last) | `read:field-configuration:jira` |

The rule behind the hints: keep the scope that real-tenant evidence shows works, and add the
endpoint-specific granular scope from Atlassian's spec (pinned by `JiraConnectorTest`).

The optional `field_search` and `project_fields` probes run LAST, in that order (after the required issue and board probes, so they can never eat their shared 30s
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
| `GET /rest/api/3/field/search` (probe `field_search`, optional, informational) | read:field, read:field-configuration | read:jira-work |
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
`fields=*all,-comment,-worklog,-description,-attachment` (`ISSUE_SEARCH_FIELDS`). Unlike the retired `/search`, `search/jql` returns ONLY `id` per issue when
`fields` is omitted; the code first omitted it, on the old endpoint's default, and the first real tenant's SYNC failed
on issues with no `fields` at all (the stub had mirrored the wrong default). Normalization (`JiraNormalizer.kt`)
needs both every system field and every discovered custom field (`schema.custom`-tagged, e.g. Sprint/Story
Points/Flagged/Team), and Jira offers no cheaper "all system + all discovered custom" shape than asking for
everything; `GET /issue/{id}` takes the same `fields` syntax, and the index-gap path asks for the same string, so both paths store the same document.
**The four exclusions** are Atlassian's own syntax - the `fields` parameter of `search/jql` and `GET /issue/{id}` takes "any issue field,
prefixed with a minus to exclude", e.g. `*all,-comment`: `comment`, `worklog`, `description` and `attachment` are the keys
`JiraPayloadTrim.kt` never stores (`persistence.md` "The Jira raw store (V10)"), so asking for them would cost the bulk of a page
on the wire and in `readBounded`'s heap (and halve the page more often, "Oversized issue page" below) only to throw it away. They
are kept in step in three places - the constant, the stub generator (`sample-data/jira/generate.mjs`) and this section. **Honest
hash note:** Jira ignoring an exclusion (a tenant that still sends a description) changes nothing stored, because every write trims
again before it hashes; and the first SYNC after the change finds every stored issue already trimmed by TRIM (`ingestion.md` "TRIM"), so the
page it fetches hashes the same and is `UNCHANGED`. A page token issued under the OLD `fields` and resumed under the new one may answer
400/410 once, which is the existing `CURSOR_EXPIRED` path (a fresh run from the last watermark). The stub's full-document pages match only
that exact `fields` string, so a search without `fields` finds nothing there. The Test-connection `search` probe reads only the first
issue's id and asks for `fields=id`.

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
pages regardless; confirmed against the real tenant on 2026-10-24: project A's two full backfills each took
64 issue pages for 6,368 / 6,372 issues with `fields=*all` (before the four exclusions), i.e. Jira returned the full 100 per page, no page halved) and
`jira.incrementalOverlapMinutes` (default 10, 0..1440 — the re-widening
window past the last watermark described above).

### Oversized issue page (`LIMIT_EXCEEDED`)

A page of heavy issues (`ISSUE_SEARCH_FIELDS`) can exceed `jira.maxResponseBytes` (`JiraHttp.readBounded` throws
`LIMIT_EXCEEDED`, never retried; so does a body past the node or depth ceiling, "Node and depth ceiling", which a page of 100 issues
answers the same way). `JiraIssuesStream` answers by re-requesting the SAME page, with the same
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
8 MiB read before it is discarded. Persisting the size would add cursor state that must be reset when the data
shrinks; that is not worth it until a real tenant shows the cost.

Pinned by `JiraIssuesPageSizeTest` (the ISSUES stream behaviour above only); the CHANGELOGS/WORKLOGS answer is next.

### Embedded users and dropped keys in the stored payloads

Jira embeds a full `USER` object as a history's `author`, a worklog's `author`/`updateAuthor` and an issue's `assignee`/`reporter`/`creator`. The
client returns it as is; the raw writers (`JiraRawWrites.upsertIssue`/`insertChangelog`/`upsertWorklog` and their batch forms) store only
`{accountId, displayName, accountType}` of it, and drop the keys Flow never reads: an issue's `comment`, `worklog`, `description` and `attachment`,
a worklog's `self` and `comment`, a history's description-edit and remote-link `items` (`jira/JiraPayloadTrim.kt`, with `jira/JiraUserReferences.kt` as
the user primitive; the reasons, the exception to "exactly as Jira returned it" and the equivalence property: `persistence.md` "The Jira raw
store (V10)" and "(V11)"). Every reader takes `author.accountId` (the assignee's `displayName` too), which survives.

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
`markWorklogsSynced` (flushed early once a batch holds 5,000 worklogs, `BACKFILL_FLUSH_WORKLOGS`, or 4 Mi characters of stored text,
`BACKFILL_FLUSH_CHARS`, so long-lived tickets and giant worklogs stay bounded in memory). **Failure semantics:** progress is committed per flush, not per issue. The worklogs and their synced stamps share the
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
results already hold more than `BACKFILL_FLUSH_WORKLOGS` (5,000) worklogs (`fetchAheadInOrder`'s `budget`). A worklog is held as the row
it will store (`RawWorklogInput`, its payload already trimmed: `trimWorklog` is idempotent, so the writers' own trim leaves the same
canonical text), and an issue's whole chain is bounded by `BACKFILL_ANSWER_CEILING` ("Worklog answer ceilings"), so the worst case held at
once is bounded in characters too: `limit` fetches in flight or finished-but-unconsumed (the window is the most `fetchAheadInOrder` ever
holds) x the per-issue ceiling (4 Mi characters) + the `FetchedBatch` (flushed at 5,000 worklogs or 4 Mi characters, plus the issue that
crossed the line) = ~24 Mi characters at the default `limit` of 4, up to ~48 MiB as UTF-16 (it was 8 Mi per issue and 8 Mi flush: 48 Mi characters, 96 MiB); a normal tenant (trimmed worklogs ~0.5 KB)
holds a few MB at the peak. Each response is bounded by `jira.maxResponseBytes` (default 8 MiB; `readBounded` transiently holds it ~3-4
times over: the buffer, its copy, the decoded string and the JSON tree), which is the transient on top.

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
  **The whole answer is bounded too** (since the security review of #356): `jira.maxResponseBytes` caps each PAGE, and
  `fetchBulk` held every page of a batch until its one transaction, following `nextPageToken` for as long as the host sent one.
  Each page is now kept only as the rows it will store (`RawChangelogInput`), and one batch's answer, or one issue's per-issue
  answer, stops at the first of `MAX_ANSWER_CHARS` (16 Mi characters of history text), `MAX_ANSWER_HISTORIES` (50,000 rows: a
  host can pack ~500k minimal histories into 16 Mi characters, and each row costs ~700 B of objects on its way into Postgres) and
  `MAX_ANSWER_PAGES` (200 pages, for an endless token or `total`) (`BoundedAnswer` in `JiraOversized.kt`, shared with WORKLOGS: "Worklog answer ceilings" below). The three are sized
  against the worker heap, not against `jira.maxResponseBytes` (a configured cap does not move them): 16 Mi characters is up to
  32 MiB as UTF-16 (Polish text is not Latin-1), held twice while written, plus the rows, ~100 MiB per worker slot at worst. Past
  any it is `LIMIT_EXCEEDED`: halved, then per issue, then the issue skipped, exactly as above; the WARNs and the skip-budget
  failure name which limit it was ("answer ceiling: ..."). An endless host costs at most ~5.6k calls a run (six halvings and up
  to eleven skipped issues at 200 pages each, bulk and per issue) before the skip budget ends it. A real 50-issue batch is
  ~2.5 MB in 2 pages (~37 histories per issue); an issue past the ceiling (~12k histories) is skipped every run, loudly. Pages are read into rows as they arrive, so a malformed
  history (no `id`, an unparseable `created`) now fails the stream on its page, before a later oversized page could have turned
  the batch into a halving or a skip.
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
jira.maxResponseBytes or an answer ceiling - the WARNs name which`). Without it a mis-set `jira.maxResponseBytes` (where every response is
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

Pinned by `JiraOversizedBatchTest` (real HTTP over `JiraStubServer` overrides on the exact id lists and a tiny `maxResponseBytes`;
the answer ceiling's cases script the client in-process).

### Worklog answer ceilings (since the security review of #360)

`jira.maxResponseBytes` bounds one PAGE; a loop that holds several pages of one answer, or follows a host's paging, needs a whole-answer
and a page ceiling of its own, or a compromised (or buggy) host behind the allow-list can exhaust the worker heap or keep a job and the
database growing. CHANGELOGS has had them since #360; the WORKLOGS loops adopt the same lever (`JiraOversized.kt`: `AnswerCeiling`,
`BoundedAnswer<T>`, `PageCeiling`; the WORKLOGS figures and `requirePlausible` in `JiraWorklogCeilings.kt`, injectable as
`WorklogCeilings` so a test can use small ones). A ceiling is `LIMIT_EXCEEDED` (the feed's far-future `until` and its non-advancing page
are `INVALID_RESPONSE`), a typed `JiraFetchException` whose text reads `answer ceiling: ...`, so `sync_jobs.error_detail` and the
data-source status page show which limit tripped; none is a silent truncation.

| Loop | Ceiling | Past it |
|---|---|---|
| Backfill, one issue's `GET /issue/{id}/worklog` chain | `BACKFILL_ANSWER_CEILING`: 4 Mi characters of TRIMMED worklog text (8x the largest real issue, ~514 KiB), 25,000 worklogs, 200 pages (an endless `total`) | The issue is skipped for the run exactly like an oversized page (`worklogsIssuesSkipped`, stays unsynced, retried next run, the skip budget ends a run that skips too many). A real issue is a few hundred worklogs in one or two pages. |
| Incremental, one `worklog/updated` or `worklog/deleted` page | `MAX_FEED_PAGE_ENTRIES`: 2,000 entries (Jira documents 1,000) | The job fails `LIMIT_EXCEEDED` before anything of the page is fetched or stored. |
| Incremental, one page's `worklog/list` details | `FEED_ANSWER_CEILING`: 8 Mi characters of trimmed text, at most `MAX_FEED_PAGE_ENTRIES` worklogs (its `worklog/list` calls are not counted: the ids and the few halvings bound them) | The job fails `LIMIT_EXCEEDED`. Not halved: the answer, not a response, is too big, and a page cannot be truncated without losing worklogs. |
| Incremental, pages followed per run, per feed | `MAX_FEED_PAGES_PER_RUN`: 300 (so at most 600k entries a run, ~300k in practice) | The job fails `LIMIT_EXCEEDED` (`lastPage=false` forever). Every page committed its own cursor, so nothing is lost and the next run resumes. |
| Incremental, a page's `until` | at most `FEED_CLOCK_SKEW_MILLIS` (5 min) past Flow's clock; and, when `lastPage` is false, past `since` | The job fails `INVALID_RESPONSE` BEFORE the cursor is persisted. A far-future `until` written to the cursor would make every later run ask a `since` in the future and read nothing (a silenced feed); refusing is chosen over clamping because a clamped `until` (now + skew) would still skip worklogs updated in the window it jumped over. A non-advancing `until` would re-ask the same call forever. |

**The cursor never moves backward.** A page's cursor is `max(since, until)` for both feeds (`advancedSince`): a last page that answers an
`until` of 0 (a missing field decodes to 0), a negative one or one before `since` would otherwise rewind the cursor and make the next run
re-read the instance-wide feed from there. It keeps the cursor and logs one WARN (constant text and the numbers). A worklog field the host
sends that cannot be used (`issueId` of a listed worklog; `id`/`updated` of a worklog being stored) is `INVALID_RESPONSE` with a constant
message, so no host text reaches `error_detail` or a log; a foreign out-of-scope worklog is still dropped without its `id`/`updated` read.

**The cursor never passes what was stored.** Every feed check, and the details' ceiling, runs BEFORE the page's transaction (the
details are read into a `BoundedAnswer` first, the page's writes and `putCursor` share the transaction), so a failed page leaves the cursor
at the last page that committed. The job FAILED row stays visible until a later run passes. A feed that trips a ceiling on a genuinely
huge page fails every run until the ceiling (a constant, not a config key, like CHANGELOGS') is raised.

**Sizing (worker heap 512 MiB, two slots).** The per-issue figures are in "Batched raw writes" *Memory* above (~24 Mi characters per slot at
worst). The feed reads one page at a time, sequentially: 8 Mi characters (up to 16 MiB as UTF-16) plus 2,000 rows. Characters are counted
on the TRIMMED payload (`trimWorklog`, applied when the worklog is read; Jira's untrimmed worklog is ~2 KB, trimmed ~0.5 KB), so 8 Mi
characters (4 Mi for the backfill) is ~8k real worklogs; the 25,000 cap is for tiny ones. A bucket issue past it (a long-lived "meetings" ticket with that many
worklogs) is skipped every run, loudly (`worklogsIssuesSkipped`, one WARN naming the ceiling); raise `BACKFILL_ANSWER_CEILING` if a real
tenant has one. A foreign (out-of-scope) worklog of the instance-wide feed is still dropped unread: its text counts toward the ceiling, but
it is only parsed for `id`/`updated` when its issue is in scope.

**For a new paging loop:** build a `BoundedAnswer<T>(endpoint, AnswerCeiling(...), unit)` (or a bare `PageCeiling` for a loop that
holds nothing), call `nextPage()`/`next()` before each page and `add(item, chars)` per kept item; the `LIMIT_EXCEEDED` it throws takes the
loop's existing oversized path, or ends the stream when the loop has none. Every loop that follows a host's paging has adopted it
(next section).

### Paging ceilings (REFERENCE, PROJECT_FIELDS, ISSUES, RECONCILE)

The remaining loops that trusted the host to stop (the 2026-10-25 security audit's MEDIUM findings) carry ceilings on the same lever
(`JiraOversized.kt`: `PageCeiling`, `BoundedAnswer`, plus `RunCeiling`/`RunBudget` for a run that writes every page; the figures and
`PageTokenGuard` in `JiraPagingCeilings.kt`, injectable as constructor parameters so a test uses small ones). They are constants, not
configuration: sized against the real tenant with wide headroom, never against `jira.maxResponseBytes` or a page size. Every check runs
AFTER the page is fetched and BEFORE its transaction (the page count before the fetch), so a trip leaves the cursor on the token (or
`startAt`) that asks the refused page; the failure is `LIMIT_EXCEEDED` with text `answer ceiling: ...` (`INVALID_RESPONSE` for a bad token
or a bad id/date), visible in `sync_jobs.error_detail` (control characters replaced) and on the data-source status page. Since 1.21.0 the job's `error_code` is `LIMIT_EXCEEDED` itself, and N such trips in a row WITHOUT progress pause the connection's scheduled syncing (`ingestion.md` "Ceiling trips and the pause"); the job's wall clock (`TIME_EXCEEDED`) is the same mechanism.

**Skip the unit or fail the job.** A unit that can be left out without corrupting what is read is skipped; anything whose absence would
make the pass wrong fails the job. **A failed REFERENCE step fails the whole SYNC of that connection** (ISSUES and the later streams do not
run) on every run until the scope fits, so a REFERENCE ceiling is an operational cap, not a soft limit. Which ones RESUME and which are HARD
caps matters:

| Loop | Ceiling (real tenant) | Past it |
|---|---|---|
| REFERENCE STATUS / PROJECT / RESOLUTION / BOARD (`runStartAtPaged`) | `START_AT_CEILING`: 500 pages and 100,000 entities a step (the largest real step, ~1k projects, is ~22 pages) | Fail the job. Resumable: the cursor holds the next `startAt`, the next run continues there with a fresh budget (one ceiling's worth a run). |
| REFERENCE USER (`runUsersPaged`) | `USERS_CEILING`: 4,000 pages and 250,000 users (the dev database holds ~16k, ~330 pages at 50 a page) | Fail the job, resumable the same way. |
| REFERENCE SPRINT, ALL boards of a pass (`runSprints`) | `SPRINTS_CEILING`: 10,000 requests and 50,000 sprints in all, ONE budget across boards (a per-board ceiling times 5,000 boards would be 1M requests); real: ~14 boards, ~0.9k sprints, ~30 requests | Fail the job. Resumable at board granularity: the cursor holds the index of the board being paged, so the next run restarts at that board (its sprints from 0) with a fresh budget, and a scope of many boards completes over runs. **HARD cap for ONE board** whose sprints alone pass the budget: it re-pages from 0 each run, re-storing identical sprints, which is no progress (a trip that stored no change), so N such runs pause scheduled syncing (`ingestion.md` "Ceiling trips and the pause"). |
| `fetchInScopeBoards` (BOARD_CONFIGURATION and SPRINT each hold it); BOARD_CONFIGURATION itself is one request a held board | `BOARDS_ANSWER_CEILING`: 5,000 in-scope boards, 4 Mi characters, 500 pages (real: 14 boards) | Fail the job before the first board configuration / sprint is fetched. **HARD scope cap** (re-paged from 0 each run). The 5,000-board cap also bounds BOARD_CONFIGURATION's requests. |
| PROJECT_FIELDS `fetchScheme`, one project | `ProjectFieldsCeilings`: 1,000 work types (so 40 groups of 25, checked before any request), 500 pages a group, 500,000 (field, work type) rows and 50,000 distinct field ids in all (real: ~9.7k fields site-wide, a project's scheme ~150 fields over ~20 types, ~3k rows) | Skip the PROJECT: the step is optional by design, so the project is skipped with the existing WARN and `projectFieldsSkipped`, its previous entity touched. The pages of a project are bounded by groups x pages-a-group (at most 40 x 500) and its rows by the row ceiling. |
| ISSUES, one run | `ISSUES_RUN_CEILING`: 10,000 pages, 500,000 issues and 4 GiB of response (`ISSUES_RUN_BYTES`) | Fail the job. Cursor: the token that asks the refused page. Resumable, with caveats below. |
| RECONCILE, one pass | `RECONCILE_RUN_CEILING`: 500 pages (of up to 5,000 ids), 500,000 ids and 256 MiB of response (`RECONCILE_RUN_BYTES`) | Fail the job. **HARD scope cap:** a new job never resumes a pass (the scratch ids are keyed by job id, so another job starts a fresh sweep), so a scope past it never reconciles. No anti-join runs on the incomplete set; the scratch ids stay for the next job's fresh pass to drain. |

Rows per page are bounded by `jira.maxResponseBytes` only where the table says nothing else (a page of 8 MiB is ~760k minimal entities
as JSON); the row ceilings above are what bounds the writes.

**Sizing the run ceilings.** The production target is ~10 projects x ~6.4k issues = ~64k issues. At the default `jira.pageSize` of 100
a full backfill of the whole data source is ~640 pages (one project ~64), so 10,000 pages is ~15x headroom, and 500,000 issues is ~8x the
whole scope and ~78x one project. **The page bound is only as wide as the page size:** 10,000 pages holds the 64k target down to an
effective page size of ~7; a page size halved by the response cap many times (down to 1 for the rest of an invocation) trips it, and the
issue ceiling then carries nothing. RECONCILE pages 5,000 ids (~13 pages for the target; 500 still holds 500k ids if Jira caps an id-only
page at 1,000) and shares the issue figure, so a scope too big for one is too big for both. The issue figure is also the most
`raw.jira_issues` rows one run can add. The real dev data (22k issues in 7 projects, the largest 6.4k) is ~1/3 of the target.

**Bytes a run (`RunCeiling.maxBytes`, ISSUES and RECONCILE).** The page and row figures alone let a host send 8 MiB pages for 10,000
pages (80 GB read, parsed and written) or 500,000 fat issues, so `RunBudget.add` also sums the WIRE bytes of the pages
(`JiraSearchPage.wireBytes`, set by `HttpJiraClient.searchJql` from the size `JiraHttp` read). Wire bytes, not the parsed or the
re-serialised text: whitespace padding, unused members and empty containers cost what they cost on the wire, so a page of `{}`s padded to
8 MiB cannot be read for free (`JiraPagingCeilingTest` pins a padded page being charged in full). REFERENCE's small steps leave it at
`UNBOUNDED_BYTES`. The real evidence ("Response size cap" below) is ~1.3 MB a 100-issue page on average (~13 KB an issue) and 4.3 MB for
the worst 100 (~43 KB an issue), so the ~64k-issue target is ~0.83 GB on average and ~2.8 GB at the worst density; ISSUES allows
**4 GiB** (~5x the average, ~1.5x the worst density). RECONCILE's ids are `{"id":"12345"}` in the stub (~15 bytes) but real Jira may add
`key`, `self` or `expand` per issue (~150-200 bytes), so it is sized on ~200 bytes an id: the 500,000-id ceiling is then ~95 MiB and it
allows **256 MiB** (~2.7x), so the id ceiling ("a scope too big for one is too big for both") is always the one that trips for a
realistic shape; the byte figure trips on a host answering fat or padded documents to an ids-only query. Trip semantics are each
stream's existing ones, checked after the fetch and before the page's transaction: ISSUES **resumes** (cursor on the token that asked
the refused page, the next run a fresh budget; the `CURSOR_EXPIRED` caveat above applies: past one ceiling's worth of bytes it is a
scope cap), RECONCILE is a **hard cap** (no anti-join on the incomplete set).

**ISSUES resume caveat.** A count is per invocation, so a legitimately larger scope normally completes, one ceiling's worth a run. But the
stored `nextPageToken` is a Jira token: if Jira answers `CURSOR_EXPIRED` for it (tokens are not kept forever), the run restarts from
`backfillFrom` (`freshRun` with no watermark), and a scope that needs more than one ceiling's pages may then never complete. A restart
keeps counting against the same run budget. Treat the ceiling as a scope cap in that case. A restart that re-stores identical pages is a
no-progress trip (a page counts as progress only when a row changed, `ingestion.md` "Ceiling trips and the pause"), so since 1.22.2 N such
runs in a row pause the connection's scheduled syncing instead of repeating every back-off for ever.

**Cross-run growth is rate-limited, not bounded.** Every ceiling is per run. A persistently hostile host costs one ceiling's worth of
rows, bytes and calls a run, paced only by the failure back-off (the connection's failure delay), not capped across runs, except that N
no-progress `LIMIT_EXCEEDED` trips in a row pause the connection's scheduled syncing and the per-job wall clock bounds one run
(`ingestion.md` "Ceiling trips and the pause").

**Typed reads.** The ids, keys, project ids and dates the streams read off a page (an issue and its project in ISSUES and RECONCILE, a
sweep id, an entity id, a board id, a worklog's) go through `JiraTypedReads.kt`: a missing or unusable one is `INVALID_RESPONSE` with
constant text naming WHAT and WHICH FIELD ("an issue without a usable updated"), never the value. A bare `getValue(..)`/`toLong()`
failure would put the host's text (a `NumberFormatException` quotes its input) into `error_detail`. `IngestWorker.onFailed` also
replaces control characters in `error_detail` (as `warnSkippedIssue` does for a log line), so no value forges a second line or a terminal
escape on the status page.

**`nextPageToken` guard (`PageTokenGuard`, ISSUES and RECONCILE).** A token the host already sent in this run (the chain cycles, so the
same pages would be re-fetched until a ceiling) or longer than 8,192 characters (a token goes back in a URL and into the cursor) is
refused as `INVALID_RESPONSE`, constant text, before the page it came with is stored and before the token is persisted. The guard
remembers a SHA-256 per token, seeds itself with the token a resumed run starts from, and forgets them on a `CURSOR_EXPIRED` restart (a
new query is a new chain; pinned: a new chain may repeat an old token). It is not a progress proof for a host that answers fresh tokens
forever: that is the run ceiling's job.

Pinned by `JiraPagingCeilingTest` (Ktor `MockEngine` host, small ceilings: every endless/cycling/oversized case above, the typed failure,
the exact call counts, the committed rows and the cursor, a resumed ISSUES run and a resumed REFERENCE step that complete, the sprint
budget spanning two boards, the field-scheme page ceiling per group not per project and its work-type/row/field-id caps, a RECONCILE
sweep restarted mid-chain whose new chain repeats the old token, the typed refusals carrying no host text, the anti-join not running, the
byte ceiling of ISSUES (a trip that resumes with a fresh budget) and of RECONCILE (a hard stop), a whitespace-padded page charged in full; and a test that the shipped figures,
bytes included, keep their headroom over the production scope), `JiraResponseNodeCeilingTest` ("Node and depth ceiling"), `IngestWorkerTest`
(control characters in `error_detail`) and `NumericConfigBoundsTest` (the shipped 8 MiB cap).

### Stored-size ceilings and the PROCESS byte budget (1.24.1)

`jira.maxResponseBytes` bounds one RESPONSE, but the text the raw writers STORE is read back into trees later: PROCESS re-loads a page of issues
with their histories and worklogs, the changelog/worklog writers parse each text item, REFERENCE's PROJECT_FIELDS and the data PROFILE parse whole
entity sets, and the `web` role's raw-issue inspector builds trees of a stored issue. A hostile host behind the allow-list could store a dense
document through any of them. Four levers close it (`jira/JiraStoredCeilings.kt`, `JiraRenderedSize.kt`, `JiraEntityReads.kt`, `.claude/docs/ingestion.md`):

- **A stored-size ceiling on the raw writers** (`RawUpsertOutcome.REFUSED`, nothing written, a stored row only touched; "A stored item over its
  ceiling is refused, loudly" in `ingestion.md`): an issue or entity over `jira.maxStoredPayloadBytes` (2 MiB, bounds 256 KiB to 8 MiB), a history
  over `MAX_STORED_HISTORY_BYTES` (512 KiB) and a worklog over `MAX_STORED_WORKLOG_BYTES` (16 KiB), each measured as the text arrived (before any
  parse; the constants follow the answer-ceiling precedent of `JiraOversized.kt`) PLUS the plain digits PostgreSQL will render for its
  exponent numbers (`String.renderedBytes`: `jsonb` keeps a number as a `numeric` and `payload::text` writes it in plain digits, so
  `'[1e131071]'::jsonb` is 22 bytes stored but 131,074 as text - the very `octet_length(payload::text)` PROCESS's claim and the inspector measure).
  An exponent alone refuses nothing (`1.0E7` costs seven digits; exponent-looking text inside a string costs none): only an item whose estimate
  is over its ceiling is refused, 70 literals of `1e131071` in a 700-byte issue included. Separators count too: PostgreSQL writes a space after every `,` and `:`
  outside strings (`'[0,0,0,0,0]'::jsonb::text` is 15 bytes against 11). A real tenant sits orders of magnitude below
  each; the stub never trips one (`SyncedStubFixture` fails if it does, or if PROCESS skips an issue). Startup fails unless
  `jira.processPageBytes >= jira.maxStoredPayloadBytes + 512 KiB` (`requireProcessPageHoldsAnIssue`): that guarantees a payload at the ceiling plus ONE history fits a page, no more - an issue with many histories
  or worklogs can still total more than a page and is then skipped by PROCESS (`issuesOversized`; it keeps its old `norm` rows, stale not absent).
- **A PROCESS byte budget** (`jira.processPageBytes`, 8 MiB, bounds 2 to 64 MiB): a PROCESS page is cut by the stored bytes of its issues (payload +
  histories + live worklogs; the `payload::text` rendering is evaluated only for the <= 50 LIMITed ids of a claim) and an issue alone over it is
  skipped (`issuesOversized`; it keeps its old `norm` rows, "stale, not absent": `ingestion.md`).
- **Reference kinds are read in chunks, never whole** (`JiraEntityReads`, `JiraRawStore.forEachEntity`/`mapEntities`): PROCESS's reference rebuild,
  the data profile, PROJECT_FIELDS' stored inputs and RECONCILE's `resolveProjectIds` read one kind at a time in keyset chunks of at most
  `jira.processPageBytes` stored bytes (ascending `entity_id`; a planning query of ids and `octet_length` sizes, then the payloads of one id
  range), parse each payload, keep the small value they need (`JiraReferenceRows`: a status/person/sprint/board row, a board's columns, an
  issue-type level, a project id, the issue-type ids of a statuses document, a `PROJECT_FIELDS` id set) and drop text and tree before the next
  chunk; `BOARD_CONFIGURATION` is reduced to `(board id, columns)` FIRST and joined by id, so a board tree and a configuration tree never coexist.
  So no kind size - a tenant with a very large USER kind included - can hold the heap, trip a refusal or stall PROCESS (there is no whole-kind
  budget any more: it falsely stalled a bigger tenant's every SYNC).
- **One entity tree at a time**: `JiraReferenceRows.FieldIdsCollector`, the profile's custom-field step and PROJECT_FIELDS' project ids parse each
  stored entity, read it and drop it.

**The heap fit budgets them.** A slot's own terms are the response tree, a PROCESS slot, a CHANGELOGS slot and a WORKLOGS slot.
`denseTreeBytes(b) = b x 3 / 8 x TREE_BYTES_PER_NODE` (stored text can be 3x denser than a response may be: 1 node per 8/3 bytes); the PROCESS slot
is `4 x jira.processPageBytes + denseTree(jira.maxStoredPayloadBytes) + denseTree(512 KiB)` = 32 + 84 + 21 = 137 MiB at the defaults (the page's
strings as read at UTF-16's worst case, 2x, plus the `customFieldsJson` copies the normalizer builds from them, 2x; a reference chunk is smaller and
covered). **CHANGELOGS and WORKLOGS keep their accumulated TEXT but no page tree beside it:** each page is converted to its trimmed item texts INSIDE the
client call, under the big-parse lock (`JiraHttp.requestAs`, `JiraClient.*As`, `JiraPageTexts.kt`), so the previous page's tree is garbage before the next
is parsed (measured before this: ~5 trees of ~108 MiB per WORKLOGS slot from the fetch-ahead window, ~206 MiB per CHANGELOGS slot, build-times WHY 48).
Their own terms are the text at UTF-16: CHANGELOGS `2 x MAX_ANSWER_CHARS` = 32 MiB; WORKLOGS `2 x ((jira.maxConcurrentRequests + 1) x 4 Mi characters +
the 4 Mi flush bound)` = 48 MiB. The lock lets only ONE tree be built at a time, but that does not make the tree a shared allowance: the
ISSUES / REFERENCE / RECONCILE page trees outlive the lock and PROCESS takes none, so while one slot converts a page (its text + the 112 MiB tree) the
others may each be at their own largest. The need is therefore `(slots - 1) x max(own terms) + max(own terms, text + tree)`: (2 - 1) x 137 (PROCESS)
+ 160 (WORKLOGS text 48 + tree 112; CHANGELOGS is 144) + 4 x 8 + 96 = 425 MiB against 448 MiB. (The WORKLOGS answer ceilings were 8 Mi characters with an
8 Mi flush bound, 96 MiB of text, 208 MiB under the lock: 137 + 208 + 128 = 473 MiB did not fit, so they were lowered to 4 Mi, still 8x the largest
real issue and 20x its worklog count.) The inspector is bounded by the same stored-byte figure and a two-at-a-time permit (`ingestion.md` "Raw issue
inspector"): the `web` role never parses what PROCESS would refuse.

### Response size cap (`jira.maxResponseBytes`, 8 MiB)

The cap bounds the WIRE bytes, not the parsed tree. One response is read into a buffer, copied, decoded to a string and parsed, so it is
held ~3-5x its size for a realistic document (the JSON tree of a normal Jira payload) and up to ~30x for a document of minimal objects
(`{}` repeated: tiny on the wire, ~50+ bytes of objects each), and up to `jira.maxConcurrentRequests` (4) are in flight in a worker of
`-Xmx512m`. A 32 MiB cap was ~230-330 MiB transient per realistic response (the audit of 2026-10-25); the default is now 8 MiB
(~25-40 MiB per realistic response, ~130 MiB at four). That closes the risk from a legitimate tenant's biggest response; **a hostile
host behind the allow-list could still OOM one worker with a dense document, which "Node and depth ceiling" below narrows but does
not close**. It is not an answer ceiling and the answer ceilings do not move with it. **What a response over it does:** an ISSUES page
halves, a CHANGELOGS/WORKLOGS batch halves, then the id is skipped (the sections above); a one-shot or per-id response that cannot shrink
fails or skips its unit. `JiraHttp` logs one WARN (constant text, the two sizes) whenever a response passes half the cap, so a one-shot
list (`GET /field`) nearing it is seen before it fails. Evidence it fits every real response (dev
database, aggregate sizes only, 2026-10-27; wire size estimated as the stored size + ~2.5 KB an issue for the three embedded users `trimIssue` slims):

| Response | Real size | Against 8 MiB |
|---|---|---|
| `search/jql` page of 100 issues (the default) | average ~1.3 MB; the worst 100 issues of the dev tenant together ~4.3 MB | fits (2x headroom on the worst page) |
| `search/jql` page of 500 (the `jira.pageSize` maximum) | worst consecutive 500 ~7.7 MB; the 500 largest issues ~13.9 MB | usually fits; the extreme page halves (the existing path) |
| `GET /field` (one shot, ~9.7k fields on the real site) | ~4.1 MB, the largest single response | fits at ~2x; a site with ~20k fields needs `JIRA_MAX_RESPONSE_BYTES` raised (the step cannot halve; "`GET /field` stays one shot" below) |
| `project/{key}/statuses` | up to ~76 KB | trivial |
| `users/search`, `project/search`, `statuses/search`, boards, sprints, board configuration | under ~0.1 MB a page (largest document ~5 KB) | trivial |
| `changelog/bulkfetch` page of 1,000 histories (default) | ~1.4 MB | fits; the batch halves otherwise |
| `worklog/list` of up to 1,000 / one issue's `GET /issue/{id}/worklog` page | ~2 MB per 1,000 worklogs on the wire (~2 KB untrimmed each); the busiest real issue ~1.2k worklogs | fits; an issue's single page of several thousand worklogs (Jira's default page is 5,000) would be skipped like any oversized per-issue page |

Two knobs interact: `jira.changelogPageSize` near its 10,000 maximum asks ~14 MB of histories a page, over 8 MiB, and the oversized path
halves the ISSUE count, not the page size (above), so keep the default or raise the cap together. A tenant whose own responses are bigger
raises `JIRA_MAX_RESPONSE_BYTES` (1 KiB..128 MiB); the worker's heap is the budget (`k8s/worker-deployment.yaml`).

#### Node and depth ceiling (`JiraNodeCeiling.kt`)

The byte cap bounds the wire, not the tree: a document of minimal objects or one-byte scalars parses to ~30x its size, and a deeply
nested one overflows the stack of the parser (and of `toString()` and the canonical-JSON writer) with a `StackOverflowError`,
an `Error` that no `catch (Exception)` between `JiraHttp` and `IngestWorker.runJob` sees. `JiraHttp` therefore scans the body BEFORE it
is decoded or parsed (`jsonCeilingBreach`: one allocation-free pass over the UTF-8 bytes, stopping at the first breach, not a validator)
and refuses it as `LIMIT_EXCEEDED` with a constant text and one WARN that carries the text and the two numbers, never the body:

- **Nodes** (`response over the node ceiling`): more structural tokens than `maxResponseBytes / 8` (`RESPONSE_BYTES_PER_NODE`), i.e.
  0.125 a byte, 1,048,576 at the default 8 MiB. A token is an opening `{` or `[`, a `:` or a `,` outside a string: about one per value,
  entry or list slot, a PROXY for the tree's size (the heap per token varies 25-110 bytes with the shape, below).
- **Depth** (`response over the depth ceiling`): more than 512 open containers (`MAX_RESPONSE_DEPTH`; the stub's pages nest at most 7
  levels). `CanonicalJsonTest` measured, on a worst-case (interpreted, 1 MiB) thread, 1,015 object+array PAIRS that parse and 515 pairs
  that canonicalise and render (`toString()`): ~2,030 and ~1,030 levels, so 512 levels leaves ~2x margin below the first stack to give
  out, and a body just past 512 is refused rather than left to find a stack that is smaller than the test's.

It is the failure an over-cap body is, so every stream answers it the same way: an ISSUES page halves, a CHANGELOGS/WORKLOGS batch
halves, then the id is skipped, a one-shot call fails. **The node ceiling is derived from the cap, not a second knob** (a fixed count
would trip a legitimate response the moment an operator raised `JIRA_MAX_RESPONSE_BYTES`); there is no configuration key, and the
`JiraHttp` constructor takes `maxResponseNodes` only for tests. As a belt-and-braces `IngestWorker.runJob` also turns a
`StackOverflowError` that still escapes (a path this scan does not cover) into an ordinary failed job (`JobStackOverflowException`,
constant text, `RUN_FAILED`, the `sync_job.failed` audit event, the back-off); no other `Error` is handled (an `OutOfMemoryError` is a
worker exit).

**Measured density** (tokens per byte; the stub's pages minified as Jira sends them, `sample-data/jira-stub/__files`): `search/jql` pages
(up to ~111 KB) 0.10, `changelog/bulkfetch` chunks 0.11, `GET /field` 0.11, `projects/fields` pages 0.11, users 0.07, sprints 0.06; the
dense small shapes are a project's statuses (0.13-0.14, ~76 KB at the busiest) and the ids-only `search/jql` of RECONCILE (0.20, 75 KB
for 5,000 ids). The pages that can approach the byte cap are the 0.10-0.11 ones; 0.125 is ~1.15x their density, deliberately tight
(below). **Which responses it can refuse:** every paged endpoint answers `LIMIT_EXCEEDED` by shrinking its page or batch (ISSUES,
CHANGELOGS, WORKLOGS), so a refusal there self-heals. The only response that cannot shrink is the one-shot `GET /field`: ~4.1 MB at ~10k
fields x 0.11 = ~450k tokens against 1,048,576, more than 2x headroom (a site with 20k fields is ~900k and still passes; it would also
need `JIRA_MAX_RESPONSE_BYTES` raised, which raises this with it). The two dense small shapes are far below the ceiling in absolute terms
at the shipped cap (15k and ~10k tokens). `JiraResponseNodeCeilingTest` pins it: every stub page of a kilobyte or more passes at the real
cap, every one but those two shapes also passes with the byte cap set just above the page (so the ratio is what is tested), and the
field-list headroom.

**What it costs and what it buys.** The scan costs ~10 ms on an 8 MiB realistic body (first call ~17 ms, JIT cold) and ~5 ms to stop on a
dense one. Retained tree after GC at the 1,048,576-token ceiling, `kotlinx.serialization` `parseToJsonElement` (one JVM, 2026-10-28):

| Document at the ceiling | Wire | Retained tree |
|---|---|---|
| `[{"a":0},...]` (349,525 objects, ~320 B an object: the worst shape found) | 2.7 MiB | ~108 MiB |
| `[{"a":0,"b":0},...]` | 2.8 MiB | ~97 MiB |
| `{"k0":0,"k1":0,...}` (524,288 distinct keys) | 5.9 MiB | ~85 MiB |
| `[0,0,...]` and `["a","a",...]` (~77 B a token) | 2 / 4 MiB | ~77 MiB |
| `[{},{},...]` | 1.5 MiB | ~43 MiB |
| `[[],[],...]` | 1.5 MiB | ~23 MiB |
| `{"a":{"a":...}}` 512 deep (the depth cap) | 3 KiB | under 1 MiB |
| 8 MiB of `search/jql` pages (realistic, 0.10 tokens a byte) | 8 MiB | ~74 MiB |

The depth cap is what bounds the nested shape (a chain of any breadth within the node ceiling is the breadth rows above, nested at
most 512 deep); without it an 8 MiB `[[[...` was a stack overflow, not a heap figure. **The worst case per response is ~108 MiB of tree**,
down from ~230 MiB for 8 MiB of `{},` with no node ceiling (measured) and ~300 MiB for `0,` (extrapolated, ~73 B a token). On top, per
response in flight: the body bytes (up to 8 MiB), the buffer copy `readBounded` makes (up to ~2x), and the decoded string (8 MiB for an
ASCII body, Java compact strings; 16 MiB if it holds a non-Latin-1 character).

**Serialised big parses.** The decode and parse of a body over 1 MiB (`BIG_BODY_BYTES`) run behind one `Mutex` in `JiraHttp`; smaller
bodies take no lock, so at most one potentially hostile big tree is being built at a time. The lock is taken inside `attemptOnce`, after
the body is read, so **a call waiting for it KEEPS its permit, its body bytes and its open response**: up to three of the four
permits can sit idle for the length of the parse ahead of them (~50 ms for an 8 MiB page, ~23 ms for 4 MB, ~7-9 ms for 1.3 MB per queued
big parse), the wait is cancellable, and there is no deadlock because the holder never suspends while it holds the lock (decode and parse
are plain CPU). The construction peak of the four in-flight calls (`jira.maxConcurrentRequests`) is one big tree (<= ~108 MiB) plus
three bodies of <= 1 MiB (<= ~40 MiB each: 131k `{"a":0},` objects or 524k `0,` scalars) = ~230 MiB, against 4 x ~108 = ~430 MiB
without it, and that still holds with the waiters, which keep only their body bytes (<= 8 MiB each), not a tree. The cost is the parse time
itself, in turn, noise next to the network round trip (decode 0.2-0.7 ms). **The lock bounds construction, not retention:** a finished tree stays alive with the stream
that asked for it (a page being written, a WORKLOGS fetch-ahead batch), which this does not limit.

**The residual, stated plainly: narrowed, not closed.** A hostile host behind the allow-list can still keep ONE ~108 MiB tree alive per
job slot on the RESPONSE path (the ISSUES, REFERENCE and RECONCILE pages are sequential and big parses are serialised), so
`ingest.workerSlots` (2) of them at once in a `-Xmx512m` worker, beside the construction peak under the lock, the bodies in flight and
whatever a running DERIVE keeps (~350 MiB committed at scale 20). With `-XX:+ExitOnOutOfMemoryError` (`server/build.gradle.kts`) an OOM is
a worker EXIT, and a job that is retried after the restart meets the same host: a crash loop that stops ingestion for every connection of
that worker until the data source is disabled or the host stops.

**What the boot check does NOT bound yet (named; BACKLOG).** Since 1.24.1 the check budgets the stored-text paths too (a PROCESS slot, and the
CHANGELOGS and WORKLOGS answer text with one converting tree, "Stored-size ceilings" above), so the PROCESS page, the history/worklog per-item
parse and the entity sets (REFERENCE's PROJECT_FIELDS and the data PROFILE, read in chunks) are bounded. Left, named: the rows beside the WORKLOGS
text (each worklog is also ~700 B of row objects on its way into Postgres, not counted); the TRIM step (`JiraRawTrim`, 50 payloads / 200 histories
a batch by COUNT, legacy untrimmed rows only); the raw-issue inspector on the `web` role (two permits x up to `jira.processPageBytes` of text, UTF-16
~16 MiB each plus the encoded response, ~80 MiB at the defaults) is capped by a size and a permit, but is NOT part of the web heap fit (a `web` role
only warns); the `all` role is budgeted for its worker only (a Test connection's own tree is not added); the 112 B a node, the
one-tree-a-slot and the dense-text figures are measured or derived but not yet re-measured end to end.

**One tree a slot, because the raw writers do not re-parse (build-times WHY 48).** A stored response used to pin THREE trees: the page's own
tree, the writer's re-parse of the payload text (`trimmedIssueCanonicalJson` / `canonicalJson(String)`), and the sorted copy `canonicalize`
built; two hostile SYNCs at once peaked at 2 x 318 MiB over idle and OOM-exited a `-Xmx512m` worker. Now the issue and the reference-entity
writers (`RawIssueInput.payload`, `RawEntityInput.payload`: a `JsonElement`, not text) canonicalize the tree the stream parsed, and
`canonicalJson(JsonElement)` (`infra/json/CanonicalJson.kt`) writes it straight to text, sorting only each object's key list, so no second
tree and no copy exist. The stored text and its sha256 are byte-identical to the old path's (that equality decides UNCHANGED vs CHANGED
against every stored row; `CanonicalJsonWriterEqualityTest` pins it over every stub body, edge cases and random trees). The changelog and
worklog writers still take TEXT: their inputs are accumulated across many pages under char-based ceilings (`BoundedAnswer`,
`FetchedBatch`), where holding trees instead of the trimmed text would multiply the held memory by ~10; they parse one small item at a time,
inside the writer, and drop it.

**The boot-time heap fit check (`JiraHeapFit.kt`, `requireHeapFitsJiraCeilings`)** makes the configuration, not the luck of the host, the
bound: at boot, `need = ingest.workerSlots x max(response tree, PROCESS slot, CHANGELOGS slot) +
jira.maxConcurrentRequests x jira.maxResponseBytes + the role's reserve` (the response tree is `STORED_RESPONSE_TREES x TREE_BYTES_PER_NODE x
(jira.maxResponseBytes / 8)`; the other two terms are "Stored-size ceilings" above) must fit the OLD generation (`-Xmx` less the young generation: the
`Tenured Gen`/`Old Gen` memory pool's cap, `-Xmx512m -Xmn64m` = 448 MiB; Parallel's `PS Old Gen` is the heap less `MaxNewSize`, G1's old
pool reports the whole heap, and a collector with one pool and no "Old" in its name (Shenandoah, non-generational ZGC) takes the
fallback of two thirds of `Runtime.maxMemory()`, which errs towards refusing). When the young generation scales with `-Xmx` (no `-Xmn`
pins it) the `-Xmx` named in the error is scaled by the heap-to-old ratio, not just need + young. A role that runs the worker (`worker`, `all`) that does not fit FAILS STARTUP, naming the three knobs and the `-Xmx`
it needs, and the knobs of the term that dominates the slot (`jira.maxResponseBytes`, or `jira.processPageBytes` and `jira.maxStoredPayloadBytes`, or
only the slots or `jira.maxConcurrentRequests` for the CHANGELOGS / WORKLOGS answer text); a `web` role (`HeapFitRole.WEB`: it parses only Test connection responses, one at a time behind the big-parse lock, and stores
none: ONE tree, `jira.maxConcurrentRequests` bodies (a call waiting for the big-parse lock keeps its body), a 40 MiB
`WEB_RESERVE_BYTES`; its message names only the knobs it uses) logs ONE WARN instead. The `all` role is judged as the worker only: a
Test connection's own tree is not added (425 + 112 = 537 MiB would refuse the shipped compose and `:server:run` heaps), which is part of
the residual above. The constants are measured (WHY 48):
`TREE_BYTES_PER_NODE` = 112 B (108 B a node measured on a hostile `GET /field` + ~4 %, i.e. 112 MiB a tree at the default 1 Mi-node
ceiling), `STORED_RESPONSE_TREES` = 1 a worker slot (**provisional**: measured as 3 before the no-reparse fix, set to 1 by it, to be
re-measured in WHY 48), `RESERVE_BYTES` = 96 MiB for a worker (idle live set + the collector's slack at the two-slot peak). Shipped defaults:
(2 - 1) x 137 MiB (PROCESS, the largest own term) + 160 MiB (the WORKLOGS slot under the lock: 48 MiB of text + the 112 MiB tree) + 4 x 8 MiB + 96 MiB = 425 MiB against 448 MiB, so the shipped worker (and the compose `all` role, and `:server:run`, which
raises its heap to 512m for this: the baked `-Xmx256m -Xmn64m` is a 192 MiB old generation) passes with 23 MiB to spare; the baked
`-Xmx256m` does not, which is why a worker role must carry `JAVA_OPTS=-Xmx512m`. The `web` role as shipped (`-Xmx256m`): 112 + 4 x 8 + 40
= 184 MiB against 192 MiB, so it boots without a WARN. `JiraHeapFitTest` pins the arithmetic, the shipped values and both role outcomes.
What would close the response path for good (BACKLOG): a per-slot heap budget (admission by estimated tree size before the parse) or a
streaming parse that never builds the tree.

#### `GET /field` stays one shot (recorded decision, 2026-10-28)

`GET /rest/api/3/field` returns a bare array (no paging, ~4 MB at ~10k fields against the 8 MiB cap). BACKLOG proposed paging it through
`GET /rest/api/3/field/search` (`startAt`/`maxResults`, `PageBeanField`: `isLast`, `total`, `values`; default page 50). Atlassian's REST v3
docs (https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issue-fields/, `#api-rest-api-3-field-get` and
`#api-rest-api-3-field-search-get`) say:

| | `GET /field` | `GET /field/search` |
|---|---|---|
| Permissions required | "None." (anonymous access allowed; fields the caller may browse) | "Permission to access Jira." |
| OAuth scopes | classic `read:jira-work`; granular `read:field:jira`, `read:avatar:jira`, `read:project-category:jira`, `read:project:jira`, `read:field-configuration:jira` | classic `read:jira-work`; granular `read:field:jira`, `read:field-configuration:jira` |
| 403 example | none | `{"errorMessages":["Only Jira administrators can access fields."]}` |

The documented permission line and scopes would be usable by the connector's non-admin service-account token (the `field` probe asks
`read:field:jira`), but the endpoint's own documented 403 says only Jira administrators can access it, the docs contradict themselves, and
the endpoint is documented "for Classic Jira projects". The step is a REQUIRED probe and the first REFERENCE read: a wrong guess would turn
a working connection into `FORBIDDEN_SCOPE` for every non-admin token. **So `/field` is not switched**; the verdict can only be settled
against a real non-admin token, which this change cannot use. If a site's field list outgrows the cap, raise `JIRA_MAX_RESPONSE_BYTES`
(the early-warning WARN fires past half the cap).

**How the owner settles it (1.23.0):** the optional Test-connection probe `field_search` (`GET /field/search?startAt=0&maxResults=1`, scopes
`read:field:jira, read:field-configuration:jira`; it runs after the board probes, just before `project_fields`, so it cannot eat the required
probes' budget) answers whether the non-admin token may call the endpoint, and writes one INFO line `field/search total=N, values=V,
isLast=B, field count=M` (no identifiers) next to the `GET /field` count (`values`/`isLast` show whether a `total` of 0 is a real count or
a 200 without the field). Read the row by its status:
- **401** on a scoped token is a MISSING SCOPE, not the answer: cross-check the `project_fields` row (it needs `read:field-configuration:jira`),
  fix the token's scopes and run the test again.
- **403** (`Only Jira administrators can access fields.`) is the deciding answer: `GET /field` stays for good.
- **200** with `total` equal to `field count` makes paging safe. A 200 with a different set would silently tombstone FIELD entities the old
  call listed, so then `GET /field` stays too.
The line carries no data-source context, so test ONE data source at a time. If a 200 with the same count is confirmed, a later change pages the
FIELD step over `field/search` and **drops the `GET /field` call** (no fallback: two code paths would mean two field sets). A failed optional row
never fails the test, and the `data_source.tested` audit event's `ok` ignores it (`observability.md`).

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

**Users refresh.** **`jira.usersRefreshHours`** (default 24, `JIRA_USERS_REFRESH_HOURS`, 0..168; since 1.17.4; 0 = every SYNC,
the behaviour before) bounds how often a data source's REFERENCE pass pages `users/search`, the site's whole user list: at the
real tenant's ~2,300 users (the 2026-10-24 measure; the dev database now holds ~16k USER rows, ~330 pages) that is ~47 of an incremental SYNC's ~74 calls (dev stack, 2026-10-24), the same answer every 15 minutes.
The pass skips the `USER` step while the OLDEST `last_seen_at` of the connection's live `USER` rows is younger than the window (a
completed pass lifts every live user to its own start; no user stored = fetch), ticks `usersSkipped`, and leaves `USER` out of its
tombstone sweep, so a skipped pass changes no user row. The flag rides the REFERENCE cursor (`ReferenceCursor.usersSkipped`), so a
pass resumed after the skip keeps it, and a fetch from the start clears it. **A SYNC an ADMIN requests ("Sync now",
`requested_by_user_id` set → `SyncJobClaim.requestedOnDemand`) always fetches the users.** (A request that joins a SYNC the scheduler already queued keeps that job scheduled.) Otherwise a new Jira account reaches
`norm.people` (PROCESS rebuilds it from the live `USER` rows) up to a day later, and `norm.people` is what team membership
validates an account against (an unknown one is a 400), what the Jira-users picker and Compare people list: after creating a
new hire's Jira account, press "Sync now" before adding them to a team. **Rollback past 1.17.4:** an older binary drops the
unknown `usersSkipped` key, so a pass it resumes after a skip would tombstone every user until the next SYNC; delete the
`reference` rows of `sync_cursors` first.

**Changelog page size.** **`jira.changelogPageSize`** (default 1,000, `JIRA_CHANGELOG_PAGE_SIZE`, 1..10,000; since 1.17.2) is
the `maxResults` of every `changelog/bulkfetch` page: the HISTORIES per page, independent of the chunk's issue count. Jira's
v3 spec (`BulkChangelogRequestBean`) documents `maxResults` default 1,000, maximum 10,000, and up to 1,000 `issueIdsOrKeys`.
Until 1.17.2 the stream asked the chunk size (50) as the page size: at the real tenant's ~37 histories per issue a 50-issue
chunk took ~37 sequential calls, ~4,700 for a 6.4k-issue project, ~75 % of its 46-53 min backfill (dev stack, 2026-10-24).
Measured on the real tenant (1.17.4, two backfills at once, 2026-10-25, `build-times.md` WHY 41 follow-up): 108k histories in ~1.5 min
(~60k a minute, ~7x the 50-per-page rate), zero 429s. A 1,000-history page is ~1.4 MB at the untrimmed ~1.35 KB per history, far under `jira.maxResponseBytes`; a page over the cap
still takes the oversized-batch path above (the chunk halves down to one issue, then the per-issue endpoint pages it). That
path shrinks the issue count, never the page size, so near the 10,000 ceiling a tenant with very large histories (description
edits) can discard a few cap-sized responses before a page fits; the default stays far from that.
Small pages also had a Jira bug (JRACLOUD-94906, fixed 2025-07: duplicated and missing histories at `maxResults` 100 on the v2
endpoint), one more reason to ask Jira's own default. The stub answers each chunk in one page whatever the size.

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
  payload (`JiraRawStore.mapEntities`, which hands the `entity_id` beside the payload, in chunks,
  since the profile needs to know WHICH project a `PROJECT_STATUSES` row belongs to) and reports it as `referenceStatusNames`, alongside
  the statuses ACTUALLY observed in that project/type's own tiled status intervals.
- **`BOARD_CONFIGURATION`** (`GET /board/{id}/configuration`) — already PROCESS's own input since
  V13 (`JiraReferenceRows.boardRef`/`boardColumns`, rebuilding `norm.boards`/`norm.board_columns` every PROCESS run,
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
- **`GET /rest/api/3/issue/{id}` with `fields=*all,-comment,-worklog,-description,-attachment`** (`JiraClient.issue(idOrKey, fields)`,
  the ISSUES stream's `ISSUE_SEARCH_FIELDS`) - the anti-join's index-gap fetch: an id the sweep saw that `raw.jira_issues` never
  stored is fetched in full (the same document the ISSUES stream itself upserts, so the trimmed shape is the same whichever path
  stored it) and written through `JiraRawStore.upsertIssue`.

`JiraClient.issue`'s `fields` parameter is optional and defaults to `null` (the full document); the missing-issue probe passes
`project,key` and the index-gap fetch `ISSUE_SEARCH_FIELDS`.

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
JSON, a WORKLOGS feed page whose `until` is in the future or does not advance ("Worklog answer ceilings"), a repeated or over-long ISSUES/RECONCILE `nextPageToken` ("Paging ceilings"), an unexpected shape — every `.jsonObject`/`.jsonArray`/`.jsonPrimitive` cast is routed through
one decode helper so a mismatch is always this code, never an uncaught exception — an invalid
`cloudId` shape, or a Jira-supplied issue id/key that doesn't match the expected path shape) ·
`LIMIT_EXCEEDED` (response over `jira.maxResponseBytes`, or over the node or depth ceiling, "Node and depth ceiling"; the ISSUES stream answers a `search/jql` page with a smaller one, see "Oversized issue page (`LIMIT_EXCEEDED`)" above; CHANGELOGS/WORKLOGS halve their batch, see "Oversized changelog/worklog batch"; a whole answer or a run past a ceiling, "Worklog answer ceilings" and "Paging ceilings") · `BLOCKED_HOST` (the outbound guard
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
- `JiraPayloadTrimTest` — the payload trim (`jira/JiraPayloadTrim.kt`): the exact dropped key sets, everything else kept (nested custom-field ADF, `timetracking`, `issuelinks`, a custom field named like a dropped item), totality on odd shapes, idempotence, the stable hash,
  and the equivalence property: `JiraNormalizer.normalizeIssue` over the trimmed documents equals the untrimmed ones, for a rich synthetic issue and for every raw row of the synced stub fixture with the dropped keys injected.
- `JiraTrimStreamTest` — the TRIM stream (`ingestion.md` "TRIM stream") over clones of the synced fixture with rows put back into Jira's shape by a raw `UPDATE`: every table returns to the trimmed original
  (payload and sha256), nothing but those two columns moves (also a tombstoned row), a history of only dropped items keeps `items: []`, a follow-up write of the untrimmed Jira text answers `UNCHANGED`, the `DONE` cursor, a second run
  writes nothing, each dropped shape ALONE makes a row a candidate of the SQL filter while odd shapes (a string payload, `fields: null`, ...) are neither candidates nor failures, a lease lost mid-walk over sparse
  candidates leaves the cursor on the last candidate read and the next run resumes to the same rows, and PROCESS over a trimmed clone reproduces the source's status-interval digest.
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
- `JiraPagingCeilingTest` — "Paging ceilings": a `MockEngine` host that never stops paging, cycles or over-lengthens its `nextPageToken`, or packs a giant boards answer, against the REFERENCE, PROJECT_FIELDS, ISSUES and RECONCILE loops with small ceilings: the typed failure, exact call counts, committed rows and the cursor left at the last committed page (a resumed ISSUES run completes; no RECONCILE anti-join on an incomplete sweep).
- `JiraRequestAsTest` — `JiraHttp.requestAs`: concurrent big fetches never overlap their transforms (at most one big tree), a source scan that the changelog and worklog streams use only the `As` calls, a failing transform propagates and frees the lock.
- `JiraHeapFitTest` — "Node and depth ceiling" boot check: the pure `heapFitVerdict` (the shipped defaults need 425 MiB against the 448 MiB old generation of `-Xmx512m -Xmn64m`, read from `application.yaml`, `k8s/worker-deployment.yaml` and `server/build.gradle.kts`; more slots, a bigger cap or more requests without heap fail with a message naming the knobs of the dominating term and the `-Xmx`; the dense-tree, PROCESS-slot and CHANGELOGS-slot arithmetic and which term dominates), one boot per role (`worker`/`all` refuse, `web` warns once and boots).
- `JiraStoredCeilingTest` / `JiraProcessBudgetTest` — "Stored-size ceilings": a padded item per page (stub overrides) for the issue, entity, history and worklog writers (REFUSED, the row touched not changed, the counters, the cursor advanced, the rest stored, the WARN without the payload; inclusive at the ceiling), the full stub SYNC refusing nothing, the PROCESS claim by stored bytes (page splits, an always-fitting first issue, the oversized skip, PROCESS over reference kinds spanning many chunks writing the very rows an unchunked read does, a 2,100-entity USER kind tripping nothing, PROJECT_FIELDS storing every project at a 1 KiB chunk), the rendered-size estimate (70 x `1e131071` refused, `1.0E7` stored, the estimate never below PostgreSQL's own rendering).
- `JiraWorklogCeilingTest` — "Worklog answer ceilings": a Ktor `MockEngine` host that never stops paging (`total`, `lastPage=false`), packs a giant answer or
  sends a far-future/non-advancing `until`; the typed failure, the bounded call counts (peak in flight within the window), the skipped issue left unsynced,
  and the cursor left at the last committed page.
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
