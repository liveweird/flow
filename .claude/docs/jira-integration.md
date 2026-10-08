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
precedence over the exponential formula). An `X-RateLimit-NearLimit: true` response header adds a
1s pause before the NEXT call. A `Semaphore(jira.maxConcurrentRequests)` caps concurrent in-flight
calls per process. A response body is read bounded at `jira.maxResponseBytes` (default 32 MiB,
Covenant's `ToadieGraphqlClient` shape) — `LIMIT_EXCEEDED` past the cap.

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
  step order; `ISSUE_TYPE` moved up one slot). A resumed cursor stays valid — it names a step, resume restarts there, and
  every step is idempotent (a cursor persisted under the old order that names `PROJECT_FIELDS` resumes with the issue types
  of the previous pass, if any). The split keys are OMITTED — only the union is stored — when no `ISSUE_TYPE` entity exists or
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
  profile (`JiraProfile`) maps those ids through the same `ISSUE_TYPE` hierarchy (`JiraHierarchy.bucket`) into
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
  to CHANGELOGS/WORKLOGS, so run without the full connector's REFERENCE pass). Separately drives
  `JiraConnector.run(RECONCILE)` against the `jira-day2` scenario: the day2 deleted/moved issue ids
  tombstone correctly, flag `needs_processing`, and a second RECONCILE pass over the same drift is a
  no-op (idempotence); plus the index-gap case (an id the sweep sees that `raw.jira_issues` never
  stored gets fetched and upserted).
- `JiraRawStoreTest` — `JiraRawStore`'s own write paths in isolation: the append-only changelog
  dedup (`ON CONFLICT DO NOTHING` on a re-inserted history id), the worklog sha256 diff/tombstone/
  resurrection cycle, `staleChangelogIssueIds`/`staleWorklogIssueIds`'s claim-scan predicates, and
  `knownInScopeIssueIds`'s scope filter.
