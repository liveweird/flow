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

Every job kind runs a no-op success today (`ingest/Connector.kt`'s default `run()`) — the actual
streams (REFERENCE, ISSUES, CHANGELOGS, WORKLOGS, RECONCILE, PROCESS, PROFILE) and PURGE's
connector-owned cleanup steps (`purgeSteps`) arrive in later v0.2.0 commits, once V10-V12 give them
raw/normalized rows to act on.

## Sync cursors (V9)

`sync_cursors` (`ingest/SyncCursors.kt`'s `SyncCursorsService`) is the resumable per-stream cursor
store the sync streams (arriving in later commits) read from and write to: one row per
`(connection_id, stream)` (composite PK), where `stream` names a phase within a job (e.g.
`"issues"`, `"changelogs"` — the exact names are owned by each stream's implementation, not fixed
here). `cursor` is jsonb text (`infra/db/Jsonb.kt`) with a per-stream shape; `watermark_at` and
`last_completed_at` are the stream's own bookkeeping. `SyncCursorsService.put` upserts by
`(connection_id, stream)` and is expected to run in the SAME transaction as the stream's page write
once streams exist, so a crash never leaves a cursor pointing past data that was never committed.

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
  from the same `sample-data/jira-stub/` directory, arriving with the Jira client and sync
  pipeline in later commits) assert exact counts against `sample-data/jira/expected.json` — issues
  per project, changelog histories, worklogs in scope, reopens, sprint carry-overs, flagged
  issues, the deliberately-omitted changelog bulkfetch chunk (and which issue ids fall back to the
  per-issue changelog endpoint because of it), and the day-2 deleted/moved issue ids and worklog
  deltas.
- The dataset's four in-scope projects (three Scrum, one Kanban) plus one deliberately
  out-of-scope project let A1 (worklogs stored for in-scope issues only, per §0 of the plan) be
  tested directly: the out-of-scope project's issues are never requested via `search/jql`, but its
  worklogs still show up in the instance-wide `worklog/updated` feed in the `day2` scenario, so the
  WORKLOGS stream's scope filter has something real to drop.

Nothing reads this stub yet — the Jira HTTP client, the outbound guard and the sync streams that
call it land in later commits. `docker-compose.yaml`'s `app` service already carries a
`JIRA_STUB_BASE_URL` pointing at it, harmlessly unread until then.
