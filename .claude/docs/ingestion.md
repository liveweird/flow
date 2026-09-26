# Jira ingestion (v0.2.0)

This doc grows commit by commit alongside the v0.2.0 Jira Cloud ingestion work (see the Product
section of `CLAUDE.md` for the roadmap). It starts with the role split that every later ingestion
commit builds on; the sync-job queue, the Jira client, the raw/normalized data model and the data
profile each add their own section as they land.

## Roles

One codebase and one image serve three process shapes, switched by `FLOW_ROLE` (`app.role` in
`server/src/main/resources/application.yaml`, read once at boot by `plugins/Role.kt` into
`AppRole { WEB, WORKER, ALL }`):

- **`web`** serves the HTTP API — every feature route module (`configureUserRoutes`,
  `configureTeamRoutes`, `configureAuthRoutes`, …) plus the SPA/static catch-all
  (`plugins/Routing.kt`) — but runs no ingestion worker.
- **`worker`** serves only the health/ready probes (`plugins/Health.kt` — `/api/v1/health` and
  `/api/v1/ready`, its one HTTP surface) and runs the ingestion worker (`ingest/IngestWorker.kt` —
  the sync-job scheduler, its lease/heartbeat claim loop, and the Jira sync/reconcile/reprocess/
  purge jobs it claims and runs; see "Sync-job queue" below).
- **`all`** (the default — unset `FLOW_ROLE`) does both in one process.

`Flyway` and `Bootstrap` always run, in every role: schema and seed state must be current
regardless of which surface a given instance serves. An unrecognized `FLOW_ROLE` value fails
startup in every mode — a bad role is a deploy-time config error, not something to limp along
with.

`Application.servesApi()` (true for `WEB`/`ALL`) is the guard every feature route module and the
SPA catch-all check first; `Application.runsWorker()` (true for `WORKER`/`ALL`) is its counterpart
for the ingestion worker.

**Dev, `docker compose` and the test suite run `all`** — a single process, the simplest shape for
local iteration and CI. **Production runs a `web` Deployment and a separate `worker` Deployment**
(`k8s/web-deployment.yaml`, renamed from `app-deployment.yaml`; `k8s/worker-deployment.yaml`), so
the worker's long-running Jira syncs scale and restart independently of the request-serving
replicas, and only the `web` Deployment sits behind `k8s/app-service.yaml` — the worker
Deployment's pod labels (`io.kompose.service: worker`) are deliberately distinct from the
Service's selector (`io.kompose.service: app`), so it never receives API traffic. Both Deployments
run the same image and differ only in `FLOW_ROLE`, secrets/env, resource sizing and — for the
worker — a larger heap (`JAVA_OPTS=-Xmx512m`, overriding the image's baked `-Xmx256m`: the
Gradle-generated launcher script assembles `$DEFAULT_JVM_OPTS $JAVA_OPTS $SERVER_OPTS` on one
`java` command line, and the JVM honours the LAST `-Xmx` flag it sees). The worker runs a single
replica with `strategy: Recreate`: the sync-job lease (below) makes a second worker instance safe
— it would only ever reclaim an abandoned lease — but there is nothing for it to parallelize
beyond `ingest.workerSlots`' in-process concurrency, so it stays a deliberate no-op possibility
rather than something worth running. Set the environment variable:

```
FLOW_ROLE=web     # or worker, or all (default)
```

See the [run-stack skill](../skills/run-stack/SKILL.md) for how this fits into local dev, compose
and Kubernetes, and the README's environment-variable table for `FLOW_ROLE`'s default and purpose.

## Sync-job queue (V9)

`sync_jobs` (`ingest/SyncJobs.kt`'s `SyncJobsService`, plan §4/§5/§9) is ONE table serving as both
job history and command queue for every connector kind. `ingest/SyncJobRoutes.kt` is the ADMIN-only
web-role surface (`POST/GET /api/v1/data-sources/{id}/sync-jobs`, `GET .../sync-jobs/{jobId}`,
`POST .../sync-jobs/{jobId}/cancel`); `ingest/IngestWorker.kt` is the sole claimer, running only
under `runsWorker()`.

**Kinds and statuses.** `SyncJobKind`: `SYNC`, `RECONCILE`, `REPROCESS`, `PURGE` (the last is
internal-only — `SyncJobRoutes.kt` 400s a caller-requested `PURGE`; only the scheduler enqueues
it). `SyncJobStatus`: `PENDING` → `RUNNING` → one of `SUCCEEDED`/`FAILED`/`CANCELLED`. `priority` is
`0` for a manual request ("Sync now"/"Reconcile now"/"Reprocess", `SYNC_JOB_PRIORITY_MANUAL`) and
`10` for a scheduler-enqueued job (`SYNC_JOB_PRIORITY_SCHEDULED`) — claiming orders by `priority`
then `requestedAt`, so a manual request preempts the schedule.

**Coalescing.** `uq_sync_jobs_open_per_kind` (a partial unique index over `(connection_id, kind)
WHERE status IN ('PENDING','RUNNING')`) makes "at most one open job per (connection, kind)"
race-free by construction. `SyncJobsService.enqueue` (shared by `requestJob` — manual — and
`enqueueScheduled` — the scheduler) tries `insertIgnoreAndGetId`; a null id means an open job
already exists, so it reads that row back and reports `coalesced = true` instead of erroring. The
web route echoes `{job, coalesced}` (`SyncJobActionResult`) on `202 Accepted`.

**Claiming.** `SyncJobsService.claim` selects PENDING rows plus RUNNING rows whose `lease_until`
has passed, ordered by `(priority, requestedAt)`, under `FOR UPDATE SKIP LOCKED` — concurrent
claimers (multiple worker replicas, or overlapping ticks) never block on the same candidate row,
they just skip it. It then scans candidates in Kotlin rather than claiming the first row
unconditionally, because two checks can't live in the SQL predicate: a candidate whose `attempt`
has reached `max_attempts` is failed `RETRIES_EXHAUSTED` and skipped; a candidate whose stored
`config_revision` no longer matches the connection's current one (edited since enqueue) is
cancelled `CONFIG_CHANGED` and skipped — both terminal transitions happen inline, and the scan
continues to the next candidate rather than returning nothing. **One RUNNING job per connection**
is also enforced here, not by an index: the partial unique index above only dedupes per `kind`, so
a SYNC and a RECONCILE could otherwise both go RUNNING at once for the same connection; `claim`
additionally checks no other row is already RUNNING for that `connection_id` before claiming, and
skips the candidate (leaving it PENDING) if one is.

**Lease and heartbeat.** A claim sets `lease_owner` (the worker's `ingest.workerId`, default
hostname + a random suffix), `lease_until = now + leaseSeconds * 1000` and `heartbeat_at`, and
increments `attempt`. `IngestWorker.runJob` runs a ticker coroutine alongside the connector's
`run()` that heartbeats every `max(1s, leaseSeconds/3)` (`SyncJobsService.heartbeat`, which also
re-extends `lease_until`) and checks `isCancelRequested`. A heartbeat that affects zero rows (the
lease was reclaimed by another worker, or the job left RUNNING under it) means the lease is lost:
the ticker throws `LeaseLostException`, and the run stops without touching cursors or the
connection's sync-status columns — the job is left RUNNING under whoever now holds the lease, or
re-claimable once that lease itself expires.

**Cancel.** `SyncJobsService.requestCancel`: a PENDING job is cancelled immediately
(`CancelOutcome.CANCELLED_NOW`); a RUNNING job gets `cancel_requested_at` stamped
(`CancelOutcome.CANCEL_REQUESTED`) for the worker to honour cooperatively — the ticker's
`isCancelRequested` check throws `JobCancelRequestedException`, which `runJob` catches by calling
`markCancelled` (status `CANCELLED`, lease cleared); a job already in a terminal status answers
`CancelOutcome.ALREADY_TERMINAL` (`409` from the route). Soft-deleting a connection
(`cancelOpenForConnection`) applies the same PENDING→CANCELLED / RUNNING→`cancel_requested_at`
pair to every open job on that connection.

**Scheduling.** `IngestWorker.tick` (every `ingest.schedulerTickSeconds`) calls `enqueueDue` —
`DataSourceService.dueForSync` (enabled, active connections whose `next_sync_at` is null or past),
`dueForReconcile` (past today's `reconcile_hour_utc` UTC boundary since `last_reconcile_at` — a
worker outage spanning the boundary still catches up the same day, and a job already run today
isn't re-enqueued) and `dueForPurge` (soft-deleted, never-purged connections past
`ingest.purgeGraceDays` since `updated_at` — plan §0 A2: a mistaken delete can be undone within the
grace period) — before pruning old rows and claiming up to `ingest.workerSlots` free jobs.
`DataSourceService.recordSyncOutcome` applies a finished SYNC's result: success resets
`consecutive_failures` to 0 and schedules `next_sync_at = now + syncIntervalMinutes`; a failure
backs off `next_sync_at = now + syncIntervalMinutes × 2^consecutiveFailuresBeforeThisOne`, capped
at `MAX_BACKOFF_MILLIS` (6 hours) so a persistently-failing connection never waits longer than
that between retries.

**Shutdown and release.** On `ApplicationStopping`, `configureIngestWorker` cancels the worker's
`CoroutineScope` and joins it with a 5-second timeout. `runJob`'s `CancellationException` handler
runs under `NonCancellable` before the join completes: it calls `SyncJobsService.release` (status
back to `PENDING`, lease cleared — the attempt count is deliberately NOT reset, so a job that keeps
crashing the process it runs on still exhausts `max_attempts`) and audits `sync_job.released`. A
resumed job picks its cursors up where the last completed page left them (`sync_cursors` below) —
this is what makes "kill the worker mid-sync" safe rather than merely tolerated (see "k8s" in the
plan's verification section).

**Worker-side audit events** (`.claude/docs/observability.md`): `sync_job.started` (before running
a claimed job), `.succeeded`, `.failed` (`RUN_FAILED`, with the truncated exception message),
`.released` (shutdown). Web-side: `sync_job.requested` (`POST .../sync-jobs`, with `coalesced`) and
`.cancel_requested` (`POST .../sync-jobs/{jobId}/cancel`).

A SYNC job runs REFERENCE → ISSUES → CHANGELOGS → WORKLOGS for real as of this commit
(`jira/JiraConnector.kt`'s `runSync`, see "Streams" below); RECONCILE, PROCESS and PROFILE still
join that list in later v0.2.0 commits (PROCESS with the `norm` schema, plan commit 8; PROFILE,
plan commit 9; RECONCILE's own commit is not yet scheduled). PURGE's connector-owned cleanup step
(`purgeSteps`) now drains `raw.jira_issues`/`raw.jira_entities` (V10, plan §0 A2) AND
`raw.jira_changelogs`/`raw.jira_worklogs` (V11) in batches; `ingest/Connector.kt`'s default no-op
`run()` remains the fallback for `RECONCILE`/`REPROCESS` until their own commits land.

## Sync cursors (V9)

`sync_cursors` (`ingest/SyncCursors.kt`'s `SyncCursorsService`) is the resumable per-stream cursor
store the sync streams read from and write to (REFERENCE, ISSUES, CHANGELOGS and WORKLOGS as of
this commit; RECONCILE/PROCESS/PROFILE join them in later commits): one row per
`(connection_id, stream)` (composite PK), where `stream` names a phase within a job (e.g.
`"issues"`, `"changelogs"` — the exact names are owned by each stream's implementation, not fixed
here). `cursor` is jsonb text (`infra/db/Jsonb.kt`) with a per-stream shape; `watermark_at` and
`last_completed_at` are the stream's own bookkeeping. `SyncCursorsService.put` upserts by
`(connection_id, stream)` and runs in the SAME transaction as the stream's page write (REFERENCE
and ISSUES do this as of this commit — see "Streams" below), so a crash never leaves a cursor
pointing past data that was never committed.

## Streams

The REFERENCE, ISSUES, CHANGELOGS and WORKLOGS streams (v0.2.0 plan §7, plan commits 6-7) are the
ordered list a SYNC job runs (`jira/JiraConnector.kt`'s `runSync`): **REFERENCE → ISSUES →
CHANGELOGS → WORKLOGS** today; PROCESS → PROFILE join the end of that list in plan commits 8-9.
Every stream implements `ingest/Stream.kt`'s `Stream` interface and shares one `StreamContext` per
job (cursor read/write scoped to the connection, a `transaction {}` wrapper, `heartbeat()`, an
injectable `clock`).

**Cursor shapes.**

- **`reference`** (`jira/JiraReferenceStream.kt`'s `ReferenceCursor`): `passStartedAt` (fixed for
  the whole pass, preserved across a resume), `step` (a `JiraEntityKind` — doubles as
  `raw.jira_entities.kind`, in the FIXED order the enum declares: `FIELD`, `STATUS`,
  `STATUS_CATEGORY`, `PROJECT`, `PROJECT_STATUSES`, `ISSUE_TYPE`, `PRIORITY`, `RESOLUTION`,
  `ISSUE_LINK_TYPE`, `USER`, `BOARD`, `BOARD_CONFIGURATION`, `SPRINT`), an optional `boardId`
  (diagnostic only, for the two board-scoped steps) and `startAt` (a real Jira `startAt` for the
  `startAt`-paged steps, or an index into the in-scope project-key/board list for the per-item
  steps).
- **`issues`** (`jira/JiraIssuesStream.kt`'s `IssuesCursor`): `watermarkAt` (null until a first run
  completes), `jql` (the exact query driving the CURRENT run, computed once and reused for every
  page — a real tenant's paging must reuse the same query text), `nextPageToken` (Jira's opaque
  cursor) and `runStartedAt` (this run's own start time, becoming the new watermark once the run's
  LAST page is written).
- **`changelogs`** (`jira/JiraChangelogStream.kt`'s `ChangelogsCursor`): a SINGLE field,
  `bulkUnavailableUntil` (null by default) — see "CHANGELOGS stream" below for what sets/reads it.
  The stream needs no page-position cursor of its own: an interrupted batch's issues are simply
  still stale on the next run (nothing was marked synced, and `insertChangelog`'s `ON CONFLICT DO
  NOTHING` makes a re-fetched history a no-op).
- **`worklogs`** (`jira/JiraWorklogStream.kt`'s `WorklogsCursor`): `updatedSince`/`deletedSince` —
  see "WORKLOGS stream (A1)" below for their initialization and advance rule. The per-issue backfill
  half of the stream needs no cursor of its own either, for the same reason as CHANGELOGS: a stale
  issue (`worklogs_synced_at IS NULL`) simply stays stale until backfilled.

**Per-page transaction with the cursor advance.** Every page (or, for REFERENCE's single-shot
steps, every whole step) writes its raw-store rows and its own `sync_cursors` row inside the SAME
`context.transaction {}` block, so a crash between them is impossible: a committed cursor always
describes a committed page. `context.heartbeat()` is called once per page, AFTER that transaction
commits — a lost lease is caught here, never before the write.

**Resume semantics.**

- REFERENCE resumes at the `step`/`startAt` its last-written cursor named: single-shot steps
  (`FIELD`, `STATUS_CATEGORY`, `ISSUE_TYPE`, `ISSUE_LINK_TYPE`) have no cursor of their own and
  simply restart from scratch (idempotent, hash-diffed); `startAt`-paged steps
  (`STATUS`/`PROJECT`/`PRIORITY`/`RESOLUTION`/`BOARD`), the per-project-key `PROJECT_STATUSES` loop,
  the per-board `BOARD_CONFIGURATION` loop and the per-board-then-per-sprint-page `SPRINT` loop all
  resume mid-list/mid-page from their persisted `startAt`/index. `users/search` (`USER`) has no
  `total` in its response, so its own "last page" detection compares each page's size against the
  FIRST page size seen, not a resumed one — a resume recomputes that from its own first fetched
  page. Users are keyed by `accountId`, every other single/paged step's entity id is the payload's
  own `id` field.
- ISSUES resumes a persisted `nextPageToken` AS-IS (same `jql`/`runStartedAt`/token) if one exists —
  meaning a prior run was interrupted mid-page; otherwise it starts a fresh run from the last
  completed watermark.

**Entity tombstoning at pass end.** Once every REFERENCE step completes, `JiraReferenceStream.run`
tombstones every `JiraEntityKind` in one final transaction
(`JiraRawStore.markEntitiesDeletedNotSeenSince`, per kind, entities whose `last_seen_at` predates
`passStartedAt`) and clears the `reference` cursor (`context.clearCursor`) — a fresh pass starts
clean next time. An entity or issue tombstoned in an earlier pass that reappears is resurrected
(`deleted_at`/`moved_out_at` cleared) the moment the corresponding upsert sees it again, whether
that is the next REFERENCE pass or, for issues, the next ISSUES page.

**Watermark, overlap and relative JQL.** `JiraIssuesStream` computes `N` (Jira's relative
`updated >= "-Nm"` window, `jira/JiraJql.incremental`) as minutes-since-`backfillFrom` on the very
first run, or minutes-since-the-last-completed-watermark PLUS `jira.incrementalOverlapMinutes`
(default 10) on every later run — the overlap re-covers a page that committed an issue Jira stamped
just before the watermark but the search only surfaced after it. The watermark itself only advances
to `runStartedAt` once the run's LAST page (`nextPageToken == null`) is written — a run that
crashes mid-page leaves the watermark exactly where the previous completed run left it, so the next
run's `-Nm` window naturally re-covers everything since then.

**`CURSOR_EXPIRED` restart.** `HttpJiraClient.searchJql` (`jira/JiraClient.kt`) maps a 400/410
response to `CURSOR_EXPIRED` only when the request carried a `nextPageToken` (a first page's own
400/410 is a genuine `INVALID_RESPONSE` — there was no token to expire). `JiraIssuesStream` catches
exactly that code and starts a fresh run from the last completed watermark (`freshRun`), up to
`MAX_CURSOR_RESTARTS` (5) restarts within one stream invocation before letting the exception
propagate — a bound against a pathologically misbehaving upstream, not an expected real-world count.

**Lease loss.** Every stream (REFERENCE, ISSUES, CHANGELOGS, WORKLOGS) lets `context.heartbeat()`'s
`LeaseLostException` propagate uncaught after every committed page/step/transaction — none of them
catches it, matching `StreamContext`'s contract (`.claude/docs/ingestion.md` "Lease and heartbeat"
above): the job stops exactly where its last committed cursor left it, safe to reclaim and resume by
any worker.

## CHANGELOGS stream

`jira/JiraChangelogStream.kt` (v0.2.0 plan §7, plan commit 7, V11) claims STALE in-scope issues —
`changelog_synced_at IS NULL` (never synced) or older than `changed_at` (the issue changed since its
last sync) — off `JiraRawStore.staleChangelogIssueIds`, ascending issue id, in batches of
`jira.changelogBulkSize` (default 50, `.claude/docs/jira-integration.md`).

**Bulk, then per-batch fallback.** Each batch is tried against `POST
/rest/api/3/changelog/bulkfetch` first (paged by `nextPageToken`), all in ONE transaction with
marking every issue in the batch `changelog_synced_at` (and `needs_processing = true`) via
`JiraRawStore.markChangelogSynced`. A 404/405/410/501 response — the bulkfetch endpoint itself being
unavailable (a GA/scope gap), not a transient failure — makes that ONE batch fall back to per-issue
`GET /issue/{id}/changelog` (`startAt`-paged, reading the `histories` key — see
`.claude/docs/jira-integration.md`), one issue per transaction+heartbeat instead of one transaction
for the whole batch (finer-grained resumability, since each issue is its own sequence of HTTP
calls). Any OTHER `JiraFetchException` status propagates rather than triggering the fallback.

**Why the bulk-unavailable flag is decided ONCE at run start, not re-checked per batch.** A 24-hour
`bulkUnavailableUntil` window is written to the `changelogs` cursor the FIRST time a batch falls
back, but it is only ever READ at the very start of a run (`bulkUnavailableAtStart` in
`JiraChangelogStream.run`) — a run that begins inside that window skips bulk entirely for EVERY
batch it processes; a run that begins outside it still tries bulk fresh on every batch, even after
one batch falls back mid-run. The sample dataset's OWN fixture (`sample-data/README.md`: exactly one
50-id bulkfetch chunk is deliberately unmapped) is the reason this matters: one chunk-specific
failure must not blind the REST of the SAME run's batches to a bulk endpoint that works fine for
them — re-checking the flag per batch would do exactly that (treat a single unmapped chunk as
"bulk is down" for the whole run), so the check happens once, at the boundary a NEW run naturally
gives it.

**Dedup and resumability.** `JiraRawStore.insertChangelog` is append-only — a changelog history is
immutable once Jira creates it, so `ON CONFLICT DO NOTHING` keyed by `(connection_id, history_id)`
is the whole dedup rule: the same history reaching here twice (a resumed batch, an overlapping
bulk/per-issue-fallback pair across runs) is a silent no-op. The stream itself needs no
page-position cursor: an interrupted batch's issues are simply still stale on the next run, since
nothing was marked `changelog_synced_at` until the whole batch (or, in the fallback path, the whole
issue) committed.

## WORKLOGS stream (A1)

`jira/JiraWorklogStream.kt` (v0.2.0 plan §0 A1/§7, plan commit 7, V11) is the direct implementation
of amendment A1: **worklogs are stored for in-scope issues only**, never the whole Jira instance's
time-tracking data.

**Why A1 shapes the stream the way it does.** The architect's original design would have read the
instance-wide `/worklog/updated` feed back to `backfillFrom` — the same historical depth every other
stream backfills to. That would sweep every OTHER unit's time tracking into Flow the moment their
worklogs touched an issue this connection happens to see on the feed, since `/worklog/updated`
carries no project/scope filter of its own. A1's fix has two parts, run in this order:

1. **Per-issue backfill first** (`backfillPerIssue`): issues with `worklogs_synced_at IS NULL` (not
   tombstoned) — first ingested, or newly back in scope — are read via `GET /issue/{id}/worklog`
   (`startAt`-paged), one issue per transaction+heartbeat, same shape as `JiraChangelogStream`'s
   per-issue fallback. This is the only step that ever reaches back before the stream's own start —
   but it only ever asks Jira about issues Flow already knows are in scope (a row in
   `raw.jira_issues`), so it can never surface an out-of-scope worklog.
2. **Then the instance-wide incremental feed, scoped to THIS connection's own lifetime, not
   `backfillFrom`.** `updatedSince`/`deletedSince` (the `WorklogsCursor`) are initialized to THIS
   RUN's own start time on a connection's very first WORKLOGS run — never `backfillFrom` — so
   `/worklog/updated`/`/worklog/deleted` only ever cover time since Flow started watching this
   connection, not history from before it existed.

**Scope filtering happens before any write.** `/worklog/updated` returns bare `{worklogId,
updatedTime}` pairs with no `issueId`, so every id on a page is resolved through `POST
/worklog/list` (≤1000 ids) FIRST; `JiraRawStore.knownInScopeIssueIds` then checks each resolved
`issueId` against `raw.jira_issues` for this connection (non-tombstoned only) — anything NOT in that
set is dropped without ever reaching `raw.jira_worklogs`, and counted
(`JiraWorklogStream.lastRunOutOfScopeCount`, A1's `worklogsOutOfScope`). `/worklog/deleted` runs the
same way after `/worklog/updated` fully drains, tombstoning whatever it names — a no-op for an
out-of-scope worklog Flow never stored.

**Cursors are rebuilt from `since`/`until`, never Jira's `nextPage` URL.** Each page's `since`
advances to that SAME page's own `until` once the page commits, and the NEXT call re-issues the
identical request shape with the advanced `since` — the client never follows the `nextPage` URL
Jira's response carries. This is a security-review rule, not a style preference: an absolute URL
handed back by an upstream response is untrusted input that the outbound guard (see
`.claude/docs/security.md` "Outbound HTTP calls") never gets a chance to re-validate — reconstructing
the next request from parameters this client already trusts keeps every outbound call subject to the
same guard as the first one.

## Data sources

`source_connections` (V8, `ingest/DataSourceService.kt`) is the generic connector registry: `kind`
(`JIRA_CLOUD` today), typed common columns (schedule, sync status, `config_revision`) and a
`settings` jsonb holding the Jira-specific shape (`siteUrl`, `email`, `projectKeys`, `authScheme`,
`cloudId`) — a future GitLab connector reuses this table, its queue and its worker outright. It
stays in `public` (the main-session PG-schemas amendment keeps operational tables there;
connector-raw and normalized data get their own `raw`/`norm` schemas starting at V10). The scoped
read-only Jira API token is the first `EncryptedAtRest` consumer
(`.claude/docs/security.md` "Encryption at rest").

`ingest/DataSourceRoutes.kt` exposes ADMIN-only CRUD at `/api/v1/data-sources` (list/create/get/
update/delete — see `.claude/docs/authorization.md`): `jira.siteUrl` must be exactly
`https://<site>.atlassian.net` (the outbound allow-list boundary, `.claude/docs/security.md`) and
is fixed at create — a later change is `409`. `jira.apiToken` is write-only: every response
carries `jira.hasApiToken` instead, and it is required (non-blank) on create; omitted OR blank on
update keeps the current token (the SPA's masked-password field sends `""` for "unchanged"), any
other present value rotates it and audits `data_source.token_rotated` separately from the ordinary
`.updated` event. Delete is soft — it disables the connection; the raw/normalized rows purge
later, once the sync-job queue and its `PURGE` job land (plan §0 A2). The uniqueness constraint is
on `name`, not `jira.siteUrl` — **two data sources may legitimately point at the same Jira site**
(e.g. different `projectKeys` scopes owned by different teams), so create/update never reject on a
repeated `siteUrl` alone.
The response's `status.state` (`NEVER_SYNCED`/`CURRENT`/`STALE`/`FAILED`/`DISABLED`) is derived
from the sync-status columns; `runningJobId` names the connection's RUNNING sync job, if any (see
"Sync-job queue (V9)"). Test connection lives at `POST /api/v1/data-sources/test` and
`…/{id}/test` (see `.claude/docs/jira-integration.md`), the job API at `…/{id}/sync-jobs`; the
status, profile and raw-issue endpoints arrive with the streams (later v0.2.0 commits).

**`infra/db/Jsonb.kt` + `infra/json/CanonicalJson.kt`** (this commit's supporting infra, detailed
in `.claude/docs/persistence.md` "Data sources (V8)"): the repo-local `jsonb` column binding
`source_connections.settings` uses, and the canonical-JSON/sha256 helper the Jira raw store (V10)
will hash payloads with.

## Jira stub

Before there's a real Jira client to point at anything, v0.2.0 commit 3 lands the fixture the
whole ingestion pipeline is built and tested against: a deterministic synthetic Jira Cloud dataset
plus a WireMock stub that serves it, generated by `sample-data/jira/generate.mjs` (Node ≥24, zero
dependencies, seeded PRNG — two runs produce byte-identical output). See
`sample-data/README.md` for the full dataset shape and stub contract; this section is the
one-paragraph orientation for anyone landing here first.

**Why generated, not hand-authored.** Hand-written fixtures drift from what the real Jira Cloud
API actually returns (paging shape, `nextPageToken` vs `startAt`, `schema.custom` discovery,
changelog history structure) and can't grow to the volume (~1,200 issues, sprints, worklogs,
reopens) that meaningfully exercises the normalization layer's invariants (§8 above). Generating
the dataset and the stub mappings from the same source means the changelog histories the stub
serves are *derived from* each issue's simulated event timeline, not authored independently of it
— they can never disagree with the issue's current field values, the way a hand-authored fixture
easily could.

**What it's for.**

- `docker compose up` brings up `jira-stub` (`127.0.0.1:8094`, mounted from
  `sample-data/jira-stub/`) so a real connection can be created and synced against it locally,
  without touching Atlassian's cloud, and so its `jira-day2` WireMock scenario can be flipped
  (`PUT /__admin/scenarios/jira-day2/state`) to see a reconcile/incremental-sync delta.
- The server's integration tests (Testcontainers Postgres + an in-JVM WireMock instance loaded
  from the same `sample-data/jira-stub/` directory) assert exact counts against
  `sample-data/jira/expected.json` — issues per project, changelog histories, worklogs in scope,
  reopens, sprint carry-overs, flagged issues, the deliberately-omitted changelog bulkfetch chunk
  (and which issue ids fall back to the per-issue changelog endpoint because of it), and the day-2
  deleted/moved issue ids and worklog deltas.
- The dataset's four in-scope projects (three Scrum, one Kanban) plus one deliberately
  out-of-scope project let A1 (worklogs stored for in-scope issues only, per §0 of the plan) be
  tested directly: the out-of-scope project's issues are never requested via `search/jql`, but its
  worklogs still show up in the instance-wide `worklog/updated` feed in the `day2` scenario, so the
  WORKLOGS stream's scope filter has something real to drop.

**`expected.json` figures must be in-scope-reachable, not whole-dataset counters.** A test asserting
`JiraRawStore` counts against `expected.json` must only ever compare against a figure an A1-correct
sync could actually produce — i.e. one computed over the four in-scope projects alone. Some of the
generator's counters (`worklogs.inScopeIssueCount`/`inScopeTotalCount`, despite the name) are
currently computed over the WHOLE simulated dataset, including the out-of-scope `SEC` project's own
worklogs — a number no correct WORKLOGS-stream run can ever reach, since `SEC` issues are never
fetched via `search/jql` and so never become a `raw.jira_issues` row eligible for the per-issue
worklog backfill. A fix to `sample-data/jira/generate.mjs` to make these counters genuinely
in-scope-only is landing in the next commit; until then, a test written against them should scope
its own assertion to the in-scope projects rather than trust the field name.

The Jira HTTP client, the outbound guard and the REFERENCE/ISSUES/CHANGELOGS/WORKLOGS sync streams
all read this stub as of this commit (`docker-compose.yaml`'s `app` service's `JIRA_STUB_BASE_URL`
points at it); RECONCILE/PROCESS/PROFILE remain the only consumers still to land.
