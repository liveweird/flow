# Jira ingestion (v0.2.0)

This doc grows commit by commit alongside the v0.2.0 Jira Cloud ingestion work (see the Product
section of `CLAUDE.md` for the roadmap). It starts with the role split that every later ingestion
commit builds on; the sync-job queue, the Jira client, the raw/normalized data model and the data
profile each add their own section as they land.

## Roles

One codebase and one image serve three process shapes, switched by `FLOW_ROLE` (`app.role` in
`server/src/main/resources/application.yaml`, read once at boot by `plugins/Role.kt` into
`AppRole { WEB, WORKER, ALL }`):

- **`web`** serves the HTTP API — every feature route module (`configureFlowUserRoutes`,
  `configureTeamRoutes`, `configureAuthRoutes`, …) plus the SPA/static catch-all
  (`plugins/Routing.kt`) — but runs no ingestion worker.
- **`worker`** serves only the health/ready probes (`plugins/Health.kt` — `/api/v1/health` and
  `/api/v1/ready`, its one HTTP surface) and runs the ingestion worker (`ingest/IngestWorker.kt` —
  the sync-job scheduler, its lease/heartbeat claim loop, and the Jira sync/reconcile/reprocess/
  purge jobs it claims and runs; see "Sync-job queue" below).
- **`all`** (the default — unset `FLOW_ROLE`) does both in one process.

**Readiness sees the scan loop** (checkup D9-2; `ingest/WorkerLiveness.kt`, `plugins/Health.kt`). On `worker` and `all`, `/api/v1/ready`
answers `503` (a problem body, the same shape as the database-down answer) when either signal trips, both measured on a monotonic
time source (`TimeSource.Monotonic`; a host suspend or an NTP step never reads as a stall) against `max(3 x ingest.schedulerTickSeconds,
60 s)` (45 s at the default 15 s tick rounds up to the 60 s floor; 900 s at the 300 s maximum):
(1) **ticks** - the last COMPLETED tick is older than that. `WorkerLiveness.completingTick` wraps every `tick()` of the loop: a tick that
fails is caught, logged on the worker's logger ("Ingest scheduler tick failed") and still stamps (a flaky Jira or database blip is the
sync jobs' own failure, not a stalled loop), a cancelled one does not. What goes stale: a dead tick coroutine, a tick that hangs (a
database call that never returns); the origin is the worker's construction, so a loop that never completes its first tick goes stale
too. (2) **leaked permits** - at the end of each tick the loop samples `IngestWorker.leakedPermits()` (slot permits held minus jobs
running, `BusyTracker`); a leaked permit does NOT stop the stamps (`tryAcquire` never suspends), it shrinks the capacity for good, so
the sample must be positive at EVERY tick for longer than the threshold (the acquire-to-launch window never persists across ticks; one
consistent sample clears it). A normal busy worker (every slot held by a RUNNING job) reads zero. The `web` role runs no loop and
publishes nothing, so its `/ready` is the database round trip alone; `/api/v1/health` (compose's healthcheck, the k8s liveness probe)
never looks at the loop. In k8s the worker's `/ready` is its `readinessProbe`, so a stall shows as a NotReady pod (visible in
`kubectl get pods`, alertable) WITHOUT a restart loop - the worker takes no traffic anyway; deliberately not wired into liveness,
since a restart is the heavier remedy and would also interrupt healthy running jobs.

`Flyway` and `Bootstrap` always run, in every role: schema and seed state must be current
regardless of which surface a given instance serves. An unrecognized `FLOW_ROLE` value fails
startup in every mode — a bad role is a deploy-time config error, not something to limp along
with.

**Cold start.** Flyway is the first database contact at boot, so it retries its connection
(`postgres.connectRetries` / `POSTGRES_CONNECT_RETRIES`, default 10, 0..15 so the worst wait stays inside the k8s startup probe; doubling waits capped at
8 s, ~63 s at 10) instead of failing at once — a `web`/`worker` pod that starts before Postgres
accepts connections waits rather than crash-looping. That is inside the k8s `startupProbe` budget
(~150 s); after the retries are spent the original connection error is raised and the process exits
as before. `0` restores fail-fast. Ktor's own module-loading timeout (`ktor.application.startupTimeoutMillis`,
default 10 s) is raised to 140 s in `application.yaml` — at the default it cut this wait short and every fresh
deploy crashed once (checkup 2, live k8s).

`Application.servesApi()` (true for `WEB`/`ALL`) is the guard every feature route module and the
SPA catch-all check first; `Application.runsWorker()` (true for `WORKER`/`ALL`) is its counterpart
for the ingestion worker.

**Two boot checks follow the role.** A role that runs the worker fails startup when `postgres.pool.maxSize` is not greater than
`2 x ingest.workerSlots` (a DERIVE holds two connections; `ingest/IngestWorkerConfig.kt`), and when its heap cannot hold
`ingest.workerSlots` slots, each the largest of a worst-case response tree, a PROCESS slot and a CHANGELOGS / WORKLOGS answer-text slot, plus the
bodies in flight and a reserve (`jira/JiraHeapFit.kt`, `jira-integration.md` "Node and depth ceiling", "Stored-size ceilings", build-times WHY 48: 2 slots x
(2 - 1) x 137 MiB (PROCESS) + 174.3 MiB (WORKLOGS text + the flush rows + the converting tree) + 4 x 8 MiB + 96 MiB = 439.3 MiB against the 448 MiB old generation of `-Xmx512m -Xmn64m`). The heap message
names `ingest.workerSlots`, `jira.maxConcurrentRequests`, the knobs of the dominating term (`jira.maxResponseBytes`, or `jira.processPageBytes` and
`jira.maxStoredPayloadBytes`) and the `-Xmx` needed, so raising a slot count or a cap
means raising the worker's `JAVA_OPTS` heap (and its memory limit) with it. A `web` role only logs a WARN. The `all` role (compose,
`:server:run`) is a worker role, so it carries `-Xmx512m` too (`:server:run` raises it in `server/build.gradle.kts`).

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
`java` command line, and the JVM honours the LAST `-Xmx` flag it sees; `SERVER_OPTS` is the image's own AppCDS hook
(`-XX:SharedArchiveFile=/app/cds/flow.jsa`, build-times.md WHY 27), so operators use `JAVA_OPTS` and leave it alone). The worker runs a single
replica with `strategy: Recreate`: the sync-job lease (below) and the claim's per-connection lock
(see "Claiming") make a second worker instance safe — two claimers never both take a job or two jobs of one
connection at once, and a second instance only ever reclaims an abandoned (expired) lease; the lease bounds a stale
run, it does not exclude one (see "Lease and heartbeat") — but there is nothing for it to
parallelize beyond `ingest.workerSlots`' in-process concurrency, so it stays a deliberate no-op possibility
rather than something worth running. Set the environment variable:

```
FLOW_ROLE=web     # or worker, or all (default)
```

See the [run-stack skill](../skills/run-stack/SKILL.md) for how this fits into local dev, compose
and Kubernetes, and the README's environment-variable table for `FLOW_ROLE`'s default and purpose.

## Sync-job queue (V9)

`sync_jobs` (`ingest/SyncJobs.kt`'s `SyncJobsService` — enqueue/cancel/prune plus a facade over `SyncJobReads.kt` (the read side) and `SyncJobLeases.kt` (claim, lease, fenced writes); plan §4/§5/§9) is ONE table serving as both
job history and command queue for every connector kind. `ingest/SyncJobRoutes.kt` is the ADMIN-only
web-role surface (`POST/GET /api/v1/data-sources/{id}/sync-jobs`, `GET .../sync-jobs/{jobId}`,
`POST .../sync-jobs/{jobId}/cancel`); `ingest/IngestWorker.kt` is the sole claimer, running only
under `runsWorker()`.

**Kinds and statuses.** `SyncJobKind`: `SYNC`, `RECONCILE`, `REPROCESS`, `PURGE`, `DERIVE` (v0.3.0
M3 commit 7, V16's `sync_jobs_kind_check` swap — see "The DERIVE job kind" below; `PURGE` stays
internal-only — `SyncJobRoutes.kt` 400s a caller-requested `PURGE`, but a manual `DERIVE` IS
accepted, `202`). `SyncJobStatus`: `PENDING` → `RUNNING` → one of `SUCCEEDED`/`FAILED`/`CANCELLED`.
`priority` is `0` for a manual request ("Sync now"/"Reconcile now"/"Reprocess"/"Derive now",
`SYNC_JOB_PRIORITY_MANUAL`) and `10` for a scheduler-enqueued job (`SYNC_JOB_PRIORITY_SCHEDULED`) —
claiming orders by `priority` then `requestedAt`, so a manual request preempts the schedule.

## The DERIVE job kind (v0.3.0 M3 commit 7)

`DERIVE` (`metrics/MetricsDeriver.kt`'s `derive()`, dispatched by `IngestWorker.runJob` BEFORE the
connector registry, through the `JobHandler` that `metrics/MetricsJobHandlers.kt` registers on
`ingest/JobHandlers.kt`'s `JobHandlerRegistry` — `ingest/` never imports `metrics/` (checkup D5;
`PackageBoundaryTest` scans the sources: `ingest`/`norm`/`jira` never reference `metrics` or `reports`,
`metrics` never references `reports`; and everything under `infra/` never references a feature package, bar the composition root `infra/db/Database.kt`/`Bootstrap.kt` which names exactly the listed feature packages to build the services it publishes); a DERIVE claim with no registered handler FAILS the job rather than
succeeding silently;
`.claude/docs/metrics.md` "The DERIVE run algorithm" has the write-side detail)
is connector-agnostic: it reads `norm.*` plus the connection's effective metrics configuration and
writes `metrics.*`, never touching Jira, so it runs the same way whichever connector kind (of a kind that derives metrics,
`DataSourceKind.derivesMetrics`) the connection is; `runJob` fails a DERIVE for any other kind (the backstop, "Connector kinds"). **Chaining** (`IngestWorker.onSucceeded`, `.claude/docs/domain-model.md`'s plan §2
decision 1): every successful `SYNC`/`RECONCILE`/`REPROCESS` of a kind that derives metrics enqueues a scheduled-priority `DERIVE`
for its OWN connection (`SyncJobsService.enqueueScheduled`, coalesced by `uq_sync_jobs_open_per_kind`
if one is already open); a `DERIVE` run itself enqueues nothing UNLESS the shared
`metrics.settings.config_revision` moved WHILE it was running (a config PUT that coalesced into the
already-open job rather than getting its own — review round 1 fix, `derive()`'s return value is the
revision the run actually used, the handler's return value, compared in `onSucceeded` against the registry's
`ConfigRevisionSource` — `MetricsSettingsService.currentRevision`).
`MetricsSettingsService.bumpRevision` separately enqueues a `DERIVE`: for EVERY enabled, active
connection whose kind derives metrics (`DataSourceKind.derivesMetrics`, "Connector kinds") on a global-settings PUT, a team-membership mutation or a team default capacity change
(every connection's DERIVE reads those), and for the SAVED connection only on a per-connection
metrics-config PUT or a sprint-calendar save (`.claude/docs/metrics.md` "DERIVE fan-out per config change").

**Job orders, updated.** A SYNC job's list (REFERENCE → ISSUES → CHANGELOGS → WORKLOGS → PROCESS →
PROFILE) and a RECONCILE job's (`reconcile` → PROCESS) have no DERIVE step — `DERIVE` is never one of
their OWN steps, it is a SEPARATE job kind chained AFTER the whole job succeeds (above). A `DERIVE`
job runs the ONE `derive` stream. **PURGE**, updated: after the connector's own `purgeSteps` and the
generic per-connection `metrics.*` config drain (`MetricsConfigService.purgeConnectionConfig`,
already documented above; the drains are `PurgeStep`s registered by `registerMetricsHandlers` and run
by the worker in registration order), a THIRD generic step drains the connection's sprint calendars
(`SprintCalendarService.purgeConnection`, V24 — `metrics.team_capacity_defaults` and `metrics.team_work_category_targets` are per team and never purged), and a
FOURTH drains the derived star and `derive_runs` (`MetricsStore.purgeAll`, `.claude/docs/persistence.md` "The `metrics`
schema — the derived star (V16)") — snapshot rows through the `SET LOCAL metrics.allow_snapshot_delete = 'on'` bypass.

**Coalescing.** `uq_sync_jobs_open_per_kind` (a partial unique index over `(connection_id, kind)
WHERE status IN ('PENDING','RUNNING')`) makes "at most one open job per (connection, kind)"
race-free by construction. `SyncJobsService.enqueue` (shared by `requestJob` — manual — and
`enqueueScheduled` — the scheduler) tries `insertIgnoreAndGetId`; a null id means an open job
already exists, so it reads that row back and reports `coalesced = true` instead of erroring. The
web route echoes `{job, coalesced}` (`SyncJobActionResult`) on `202 Accepted`.

**Claiming.** `SyncJobsService.claim` selects PENDING rows that are DUE (`not_before` null or not in the future — "Debounced DERIVE" below) plus RUNNING rows whose `lease_until`
has passed, ordered by `(priority, requestedAt, id)` (a total order, the same as `openJob`'s), under
`FOR UPDATE SKIP LOCKED` — concurrent claimers (multiple worker replicas, or overlapping ticks) never
block on the same candidate row, they just skip it. A claimer holds the row locks of EVERY candidate its scan
returned until its transaction commits, so a second claimer racing the first inside that window can find nothing
to take and answers `null` for this tick (the next tick retries — a latency of one tick in a window of
milliseconds, never a lost or doubled job). It then scans candidates in Kotlin rather than claiming the first row
unconditionally, because two checks can't live in the SQL predicate: a candidate whose `attempt`
has reached `max_attempts` is failed `RETRIES_EXHAUSTED` and skipped; a candidate whose stored
`config_revision` no longer matches the connection's current one (edited since enqueue) is
cancelled `CONFIG_CHANGED` and skipped — both terminal transitions happen inline, and the scan
continues to the next candidate rather than returning nothing. **One RUNNING job per connection**
(of ANY kind) is also enforced here, not by an index: the partial unique index above only dedupes per
`kind`, so a SYNC and a RECONCILE (or DERIVE) could otherwise both go RUNNING at once for the same
connection; `claim` checks no other row is already RUNNING for that `connection_id` before claiming, and
skips the candidate (leaving it PENDING) if one is. That read alone is a check-then-act race between two
claimers (two worker PROCESSES — the slots of one process claim sequentially) that hold DIFFERENT candidate rows of one connection — `SKIP
LOCKED` only separates claimers on the same row — so each claimer first takes a transaction-scoped advisory
**try-lock** on the connection (`pg_try_advisory_xact_lock(CLAIM_LOCK_NAMESPACE, connection_id)`, `persistence.md`
lists it), BEFORE the RUNNING read, and holds it until its claim commits: of two racing claimers the second
cannot get the lock, never reaches the read concurrently, and skips the candidate (it stays PENDING for a later
tick) instead of waiting, so claims on OTHER connections are never held up — parallel DERIVEs of different
connections are unaffected. An advisory try-lock cannot deadlock (it never waits) and, touching no table row, cannot
conflict with the writers of the connection row (`DataSourceService`'s `FOR NO KEY UPDATE` updates, the config PUTs, soft
delete) or with the foreign-key share lock every job insert takes on it — which a `FOR UPDATE` on the connection
row would. `SyncJobQueueTest` pins it with two claimers interleaved deterministically (one held inside its claim
transaction by the `afterClaimLock` seam).

The one connection-row lock a claim DOES take is for the failure back-off of the jobs it closed `RETRIES_EXHAUSTED`
(see "Scheduling" below). It is taken AFTER the candidate scan, never inside it, and as `FOR NO KEY UPDATE`, in
ASCENDING connection id: that mode does not conflict with the `FOR KEY SHARE` the foreign key takes on every job insert
(`enqueueScheduled`, `MetricsSettingsService.bumpRevision`'s fan-out), so enqueuers are never held up; two claimers
exhausting jobs of several connections lock them in the same order, so they cannot deadlock each other; and the only
writers it can wait on (`DataSourceService.update`'s `FOR NO KEY UPDATE`, the other `ScheduleBackoff` writes) lock the connection
row alone and then commit, never waiting on a job row the claim holds. The scan holds the job-row `SKIP LOCKED` locks
first, so the order is always job rows, then connections.

**Debounced DERIVE (V27 `sync_jobs.not_before`).** A configuration change rewrites a connection's whole derived star, so a burst
of edits (five capacity saves in a minute ran five full DERIVEs per connection) is coalesced rather than re-derived once per edit.
`not_before BIGINT NULL` (epoch millis) is the earliest time a PENDING job may be claimed; null (every existing row, and every job
but the one case below) = due at once. The claim's candidate scan skips a PENDING job whose `not_before` is still ahead (neither
claimed nor row-locked), so a due job behind it is still taken; `idx_sync_jobs_claimable` is unchanged — the condition is a filter
over the partial index's open rows (at most one per connection and kind). The marker is explicit, never inferred from the job's
priority or requester (chained and config-caused DERIVEs look alike there): `SyncJobsService.enqueueDebouncedDerive` is the one
entry point, called by `MetricsSettingsService.bumpRevision` (every config caller that goes through it — settings, membership, capacity,
per-connection config, sprint calendars, public holidays, the membership seed) and by the worker's post-run stale-revision follow-up.
(`TeamService.closeOpenJiraMemberships`, a team delete closing its Jira memberships, bumps the shared revision directly and has always
enqueued no DERIVE; it is not on this path — the next sync's DERIVE picks it up. `teams/` does not import `metrics/`.)
**There is no maximum wait**: every further edit pushes `not_before` out again, so edits arriving more often than the window keep delaying
the DERIVE until they pause; a single operator's burst ends, so no cap is applied.

- **Window.** `ingest.deriveQuietSeconds` (`INGEST_DERIVE_QUIET_SECONDS`, default 45, `requireConfigInt` 0..3600, validated on every
  boot like the rest of `ingest.*`; wired in `infra/db/Database.kt` into `SyncJobsService`, so both roles read it). 0 turns the
  debounce off (`not_before` stays null). A DERIVE is scheduled at `now + window`.
- **Coalescing.** With a PENDING DERIVE already queued the enqueue inserts nothing (the unique index) and instead moves its
  `not_before` out: one conditional UPDATE `SET not_before = GREATEST(not_before, new)` (atomic, no lost push) that also restamps the
  job with the connection's current `config_revision` (`GREATEST`, never lowered by a concurrent bump). A data-source PUT inside the
  window (`DataSourceService.update` bumps `source_connections.config_revision`, even on a rename) restamps the delayed PENDING DERIVE
  in its own transaction while the connection stays enabled, so the claim does not cancel it `CONFIG_CHANGED` and the re-derive still
  runs at `not_before`. (Chosen over a claim that restamps-and-claims: the PUT is the only writer of that revision, so the fix sits at
  the write, and the claim's cancel rule stays one rule.) A burst therefore becomes ONE DERIVE, `window` after the last edit. A PENDING DERIVE that is already due
  (`not_before` null: a chained or manual one) is left alone — it runs sooner than any debounced time anyway.
- **RUNNING.** A bump while a DERIVE is RUNNING coalesces into it as before; the worker's post-run check
  (`revision moved while it ran`) then enqueues the follow-up through `enqueueDebouncedDerive`, so it honours the window too —
  counted from the end of the run, not from the last edit (the edit time is not tracked).
- **Never delayed.** `enqueueScheduled` (a DERIVE chained after a SYNC/RECONCILE/REPROCESS, and the SYNC/RECONCILE/PURGE schedule) and
  `requestJob` (a manual request, `POST …/sync-jobs`) insert a due job — and when they coalesce into a PENDING DERIVE that is
  debounced, they clear its `not_before` (and restamp the revision), pulling it forward to now instead of inheriting the delay.
- **Visibility.** `SyncJobResponse.notBefore` (nullable, in the sync-jobs list/detail, the action results and the status endpoint's
  `currentJob`/`lastJobs`). `openJob` ranks RUNNING, then due PENDING, then a delayed PENDING one. The data source details page
  (`JobScheduledNote`, in the current-job card and the jobs table) shows a PENDING job with a `notBefore` as the neutral gray Pending
  badge plus "Waiting for further changes — starts after <UTC time>" — a scheduled job is neither stuck nor failed, and the page's
  5 s open-job poll follows it through to Running.

**Lease and heartbeat.** A claim sets `lease_owner` (the worker's `ingest.workerId`, default
hostname + a random suffix), `lease_until = now + leaseSeconds * 1000` and `heartbeat_at`, and
increments `attempt`. `IngestWorker.runJob` runs a ticker coroutine (`ingest/LeaseHeartbeat.kt`) alongside the connector's
`run()` that renews the lease every `max(1s, leaseSeconds/3)` (`SyncJobsService.renewLease`: the heartbeat UPDATE
that re-extends `lease_until` plus the `cancel_requested_at` read, in ONE transaction — one pooled connection, one failure
policy). **Every run-owned write is fenced** — the heartbeat/renewal by `(id, lease_owner, attempt, status = RUNNING)`,
`finish`/`fail`/`markCancelled` by `(id, attempt, status = RUNNING)`, `release` by all four. `attempt` increments on every
claim, so a stale run of the SAME worker (its lease expired, the job was reclaimed, it is still unwinding) matches zero
rows; `status = RUNNING` additionally stops a stale run from touching a row that was closed WITHOUT a new claim (the claimer's
inline `RETRIES_EXHAUSTED`/`CONFIG_CHANGED` closes do not bump `attempt`) and a shutdown `release` from reopening a row whose
`finish` already committed. A fenced-out write returns `false` and changes nothing: the heartbeat answers lost, and for the
others the worker logs a WARN (a stale `onSucceeded` records no sync outcome, no chained DERIVE and no audit). A renewal that affects
zero rows (the lease was reclaimed, or the job left RUNNING under it) means the lease is lost: the ticker throws
`LeaseLostException`, and the run stops without touching cursors or the connection's sync-status columns — the job is left
RUNNING under whoever now holds the lease, or re-claimable once that lease itself expires.

**A renewal that THROWS or HANGS** (a transient pool-acquire timeout, a dropped connection, a stalled database) is
not a lost lease, and is tolerated — but only while renewal can still be stopped strictly BEFORE `lease_until`, because past it another
claimer may take the row. With `L` the lease, `slack = max(500 ms, L/10)` and `E` the monotonic time
since the START of the last successful renewal (taken before the call, like the `now` the lease is computed from; initially
the mark taken in `claimAvailable` just before the `clock()` read the claim's `lease_until` comes from): every attempt is bounded by `withTimeoutOrNull(slack)` on the client and, in the database, by
`lock_timeout` + the transaction's `queryTimeout` of about `slack` (`renewLease`'s `boundMillis`; not a `SET LOCAL statement_timeout`,
which Exposed overwrites — `persistence.md`) — a timeout is a failure, and the attempt ends at most `slack` after it began — the renewal runs in a detached worker scope and is AWAITED, because a coroutine blocked inside a database
statement ignores cancellation until the statement ends (exposed-r2dbc 1.5.0, measured: the transaction then rolls back and its
connection returns, nothing leaks, but only after the stall), so a timeout around the call itself would not return on time; after a failure at `E` the ticker WARNs and retries after `max(1s, interval/2)` only if
`E + retry + 2 × slack < L`. A tolerated retry starts at `E + retry` and its failure is observed by `E + retry + slack < L − slack`,
so **renewal stops and the job's cancellation is requested at least `slack` before `lease_until`** (the allowance for the stop itself
and for clock skew against the reclaimer's wall clock). Otherwise the failure is rethrown at once (the run FAILS `RUN_FAILED`).
**What this does not promise:** the lease bounds a stale run, it does not exclude it. Cancelling the job scope only REQUESTS the
body to stop — a body inside a database statement unwinds when that statement returns (its open transaction then rolls back), and
a frozen worker (a long GC pause, a stopped container) does not unwind at all. Every `sync_jobs` write of such a stale run is a
no-op through the fences above, but its stream DATA writes are not fenced, so it can overlap a reclaimed run in that window (the
streams are re-runnable by design — cursors, per-scope replace — but nothing excludes the overlap). Abandoned renewals are not
added to the pool-size bound (`requirePoolFitsWorkerSlots`): they end server-side within about a slack and are spaced further apart than
that, so at most one per slot is outstanding; a stall that eats the headroom makes the next renewal fail, which the budget counts —
the job stops, the fail-closed outcome. Which failure is the fatal
one depends on how long failures take: at the defaults (300 s lease, 100 s interval, 50 s retry, 30 s slack) the threshold is
`E < 190 s`, so fast failures (observed at 100 s and 150 s) are tolerated and a third, at 200 s, is fatal, while failures that
each use the whole slack (observed at 130 s, then 210 s) make the second one fatal. A renewal that returns `LOST` stays fatal at once. Cancellation is
never swallowed (`catchingFailures`). `IngestWorkerTest` pins the outcomes (one failure tolerated, repeated failures and hung
attempts fatal before the lease edge, lease-lost fatal at once, a cancel request via the renewal, a stale success writing
nothing) through the `internal` `renewLease` and `timeSource` seams of `IngestWorker` (a `TestTimeSource` makes the budget arithmetic exact, whatever the CI load); `SyncJobQueueTest` pins the stale-write fence at the
service. A stream's OWN per-page `context.heartbeat()` is a separate, unbudgeted call: an exception from it fails the run as before.

**Cancel.** `SyncJobsService.requestCancel`: a PENDING job is cancelled immediately
(`CancelOutcome.CANCELLED_NOW`); a RUNNING job gets `cancel_requested_at` stamped
(`CancelOutcome.CANCEL_REQUESTED`) for the worker to honour cooperatively — the ticker's
`renewLease` outcome `CANCEL_REQUESTED` makes it throw `JobCancelRequestedException`, which `runJob` catches by calling
`markCancelled` (status `CANCELLED`, lease cleared); a job already in a terminal status answers
`CancelOutcome.ALREADY_TERMINAL` (`409` from the route). Soft-deleting a connection
(`cancelOpenForConnection`) applies the same PENDING→CANCELLED / RUNNING→`cancel_requested_at`
pair to every open job on that connection.

**Scheduling.** `IngestWorker.tick` (every `ingest.schedulerTickSeconds`) calls `enqueueDue` —
`DataSourceService.dueForSync` (`DataSourceSchedule.kt`: enabled, active, not paused ("Ceiling trips and the pause") connections whose `next_sync_at` is null or past),
`dueForReconcile` (`reconcileDue` in `ingest/SchedulePolicy.kt`: past today's `reconcile_hour_utc` UTC
boundary since `last_reconcile_at` — a worker outage spanning the boundary still catches up the same
day, and a job already run today isn't re-enqueued — not inside a failure back-off, below, and not RECONCILE-paused: `DataSourceSchedule.kt`'s
`dueForReconcileRows` skips `reconcile_paused_at IS NOT NULL`, "Ceiling trips and the pause") and `dueForPurge` (soft-deleted, never-purged connections past
`ingest.purgeGraceDays` since `updated_at` — plan §0 A2: a mistaken delete can be undone within the
grace period) — before pruning old rows and claiming up to `ingest.workerSlots` free jobs. A slot (semaphore permit) belongs to a job only once
it is launched: a `claim` that returns nothing or THROWS (pool timeout, database restart) gives its permit back, the tick fails and is logged, and the
next tick claims again (`IngestWorkerTest` "a claim that throws does not consume a slot"). The launch is `ATOMIC`, so a scope cancelled right after
the claim still starts the body and `runJob`'s cancellation handler releases the row to PENDING; `runJob` does its start write (`recordSyncStarted`) and
`sync_job.started` audit INSIDE its `try`, so a failure there takes the normal failure path (audit, back-off) instead of leaving the row RUNNING. A claim
that committed but whose reply was lost is reclaimed when its lease expires.

**A job whose database connection is lost is closed promptly** (`ingest/TerminalWriteRetry.kt`). A Postgres backend killed for memory makes the postmaster reset EVERY session:
the job body fails with a connection error and the pool is dead or refused for seconds, so the write that closes the row hits the same outage. Before the retry
that write threw out of `onFailed` (and out of `runJob`), nothing repeated it, and the row sat `RUNNING` until its lease expired (~5 min, then the claim's
re-claim or `RETRIES_EXHAUSTED` close). Now the five fenced writes the worker makes at the end of a run - `SyncJobsService.finish`, `fail`, `failTripped`,
`markCancelled` and the shutdown/cancellation `release` - go through a `TerminalWriteRetry`: on a connection-class failure they repeat after 0.5 s,
doubling to 8 s, until 30 s have passed since the first failure (the clock starts when that failure is caught; the last pause is cut to the budget; no config key - the figures are the class's defaults), then the last
failure is rethrown. `isConnectionLoss` matches, anywhere in the cause chain (Exposed wraps the driver's exception): an `R2dbcNonTransientResourceException` with no
SQLSTATE or class `08` (`PostgresConnectionClosedException` - a dead pooled connection not yet evicted, `08006` - and "Cannot connect", `08003`), the server
states `57P01` (`pg_terminate_backend`/admin shutdown), `57P02` (the crash reset), `57P03` (starting/recovery) and `53300` (too many clients), an
`R2dbcTransientResourceException`, an `R2dbcTimeoutException` (the pool's acquire timeout) and an `IOException`. Every other SQLSTATE - a constraint, a data
or syntax error, a lock or statement timeout - and a shut-down pool are thrown at once, never retried. **Why a repeat is safe:** each write is fenced by
`(id, attempt, status = RUNNING)`, so a first attempt that DID commit before its reply was lost leaves the row terminal and the repeat matches no row (`false`/`null`:
the worker logs the stale WARN and skips the follow-ups it would have run - for `finish` the connection's sync outcome and the chained DERIVE, which the
next scheduled SYNC supplies); a `fail`'s connection back-off is in the same transaction as its status change, so the two commit together or not at all. When the
budget is spent, `IngestWorker` (`closeBestEffort`) logs ONE WARN with ids and exception class names only and returns: the row is left to the lease expiry exactly as
when the worker JVM dies, and a failed `release` no longer replaces the cancellation it ran under. A `finish` that exhausts its budget on a connection loss is NOT a failure of the job (its body succeeded and every page committed): `IngestWorker` (`finishOrLeaveToLease`)
logs the same ONE WARN and leaves the row to the lease expiry, which re-runs the idempotent job - no `fail` write, no back-off, no `sync_job.failed`; a `finish` that
throws anything else (a refused statement) still takes the failure path as before. The budget is checked between attempts and an attempt itself is not cut (a pool acquire
can wait ~20 s), so one write can spend up to ~70 s: the first attempt, the 30 s, and one last attempt. `claim` is NOT retried (a claim that committed whose reply was lost would be taken twice, above).
`TerminalWriteRetryTest` pins it with a real worker over a pool of its own whose connection factory can be switched down (the pooled backends are terminated with
`pg_terminate_backend`, new connections refused by the driver): a lost-connection failure, success and release each end within seconds with the recorded back-off, an
exhausted budget leaves the row `RUNNING` with one WARN (a success write that exhausts included: no failure is recorded), and a refused statement is not retried.

`DataSourceService.recordSyncOutcome` (body in `DataSourceOutcomes.kt`) applies a finished SYNC's result: success resets
`consecutive_failures` to 0 and schedules `next_sync_at = now + syncIntervalMinutes`; a failure
backs off `next_sync_at = now + syncIntervalMinutes × 2^consecutiveFailuresBeforeThisOne`, capped
at `MAX_BACKOFF_MILLIS` (6 hours, the one cap for both SYNC and RECONCILE) so a persistently-failing
connection never waits longer than that between retries.
A failed RECONCILE backs off the same way (V21): only a success stamps `last_reconcile_at`, so without it the
job stays due and is re-enqueued on every tick. The failure path sets
`next_reconcile_at = now + backoffMillis(RECONCILE_RETRY_BASE_MILLIS, reconcile_failuresBeforeThisOne)` and
increments `reconcile_failures` — 15 min, 30 min, 1 h, 2 h, 4 h, then the 6 h cap — and `reconcileDue` holds
the connection back until `next_reconcile_at` has passed. A retry that falls past midnight UTC waits for the next
day's boundary (with a late `reconcile_hour_utc` a failing day may get no further retry), and `reconcile_failures`
carries over until a success, so a connection that failed yesterday starts today further up the back-off. `recordReconcileSucceeded` resets both columns. A manual RECONCILE is not gated by the back-off.

**Where the failure back-off is written (one transaction with the job's terminal write).** The connection-row
writes live in `ingest/ScheduleBackoff.kt` as extensions on the CALLER's transaction (`recordFailureBackoff`; DERIVE,
REPROCESS and PURGE have no schedule on the connection and write nothing). The row is locked `FOR NO KEY UPDATE`.
Two paths call it, each inside the transaction that closes the job, so no other worker's tick can see "job closed,
connection still due" and enqueue an extra retry in between:

- **`SyncJobLeases.fail`** / **`failTripped`** (the fenced `RUN_FAILED` / ceiling-trip write `IngestWorker.onFailed` makes; `failTripped` also
  records the trip counter and pause, or the resumable clock stop, "Ceiling trips and the pause"): the job row goes FAILED and the
  back-off is recorded together; a fenced-out (stale) write returns `false` and records no back-off.
- **`SyncJobLeases.claim`'s inline `RETRIES_EXHAUSTED` close** (a job at `max_attempts`, e.g. a worker crash loop —
  it never reaches `onFailed`): the same back-off is recorded for an exhausted SYNC/RECONCILE, so the next tick does not
  enqueue a fresh job at once. It is applied after the scan, in ascending connection id (see "Claiming"). The close is
  logged at WARN after the claim transaction commits (no audit event: the row's `status`/`error_code` and the
  connection's `last_sync_error_code`, `RETRIES_EXHAUSTED` for SYNC, carry it).
  **`error_detail` of that close.** A job closed while still `RUNNING` (its lease expired on the last attempt — the worker JVM
  died mid-job, e.g. an OOM under `-XX:+ExitOnOutOfMemoryError`) gets the fixed text `Lease expired on attempt N of M (the worker
  stopped mid-job — restarted or out of memory?)` (`leaseExpiredDetail`: two integers, never exception text, so no stack trace,
  URL or token). A `PENDING` job at `max_attempts` (a shutdown `release` gave it back) lost no lease and records no detail. An
  expired attempt that is re-claimed with attempts left records nothing (the job may still succeed, and `finish` does not clear
  `error_detail`); only the terminal close writes it. A normal failure keeps its own `<ExceptionClass>: <message>` from
  `onFailed` (a terminal row is never touched by a later claim), with any U+0000 in the message replaced by U+FFFD
  (`replaceNul`, `persistence.md` "jsonb") — a database exception quotes the offending value, and a NUL in a `text` column
  would make this very write fail and hang the job RUNNING until its lease expired. A `StackOverflowError` (an `Error`, so the `catch (Exception)` never sees it) takes the same path as a `JobStackOverflowException` with constant text (`ingest/JobStackOverflow.kt`; the Jira client refuses a too-deep body before it can happen, `jira-integration.md` "Node and depth ceiling"); no other `Error` is caught. The data source details page's jobs table shows the detail under the `error_code` (dimmed, two-line clamp, the full text in the cell's `title`, plain text).

**Only a job of the connection's current `config_revision` records a failure back-off** (compared under the lock): a job that
started before a config PUT and fails, or is found exhausted, afterwards must not re-impose the back-off the PUT just
cleared. The job itself is still closed FAILED.

**The success path is still two transactions** (`finish`, then `recordSyncSuccess`/`recordReconcileSucceeded`). That is
harmless: the window between them can only let another worker's tick enqueue one early extra job (the connection looks
due for a moment), never lose the success or the back-off reset.

**A config PUT lifts the back-off.** `DataSourceService.update` (every successful PUT bumps `config_revision`, which
also cancels the connection's open jobs `CONFIG_CHANGED`) clears an existing failure back-off in the same UPDATE
(`clearBackoff`): `consecutive_failures` → 0 and `next_sync_at` → null for SYNC, `reconcile_failures` → 0 and
`next_reconcile_at` → null for RECONCILE — so the operator's fix (a rotated token, a corrected scope) is retried on the
next scheduler tick instead of after up to 6 h. `last_sync_error_code` stays as history; it is a closed vocabulary (`RUN_FAILED`, `RETRIES_EXHAUSTED`, `CONNECTOR_UNAVAILABLE`, `TOKEN_SCOPE_TOO_BROAD` / `TOKEN_INACTIVE` (a GitLab job refused for a token with a non-`read_` scope, or an inactive one: `JobRefusedException`, `JobRunExceptions.kt`) and the ceiling-trip codes `LIMIT_EXCEEDED`, `TIME_EXCEEDED`, "Ceiling trips and the pause"; `closedErrorCode` stores anything else as `RUN_FAILED`), since the telemetry panel and the status view show it to ADMINs. A connection with no
back-off keeps its schedule (a rename triggers no extra sync). Soft delete and the metrics-config PUTs do not touch the back-off.

**Idle heap give-back.** `claimAvailable` counts the jobs it launches in a `BusyTracker` (`ingest/IdleHeapGiveBack.kt`); when
the count falls back to zero (the last running job, not just one of a pair) it runs ONE explicit full collection
(`giveHeapBackWhenIdle`, `System.gc()`), so the heap a DERIVE burst grew can shrink (the baked `-XX:-ShrinkHeapInSteps`
and `MaxHeapFreeRatio=30` let it; `build-times.md` WHY 26). It fires once per idle transition, never during shutdown, a
failing hook is logged and swallowed, and `-XX:+DisableExplicitGC` must never be set. Only the `configureIngestWorker`
wiring passes the hook; every other `IngestWorker` (tests) defaults to a no-op. `BusyTrackerTest` pins the trigger,
`IngestWorkerTest` pins the wiring through a real tick; the one `System.gc()` is exempted in `config/detekt/detekt.yml`.

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
`.released` (shutdown). A SYNC whose start write fails emits `.failed` with no `.started`. Web-side: `sync_job.requested` (`POST .../sync-jobs`, with `coalesced`) and
`.cancel_requested` (`POST .../sync-jobs/{jobId}/cancel`).

A SYNC job runs REFERENCE → ISSUES → CHANGELOGS → WORKLOGS → PROCESS → PROFILE
(`jira/JiraConnector.kt`'s `runSync`, see "Streams" below, "Normalized layer" further down and "Data
profile" below). A RECONCILE job runs its own `reconcile` stream followed by PROCESS (`runReconcile`,
see "RECONCILE stream" below) — it does NOT join the SYNC ordered list, ends the SAME way SYNC does
so a RECONCILE tombstone is mirrored into `norm.work_items` in the same job (not on the next
scheduled SYNC), and never runs PROFILE — a daily drift check is not itself a reason to recompute
the whole data profile. A REPROCESS job flags every raw issue `needs_processing`
(`JiraRawStore.markAllNeedsProcessing`) then runs PROCESS → PROFILE (`runReprocess`) — a version
bump or a manually requested full rebuild, never touching Jira. `JiraConnector.run` dispatches on
`context.claim.kind` (`SYNC`/`RECONCILE`/`REPROCESS`/`PURGE` each run their own connector method).
PURGE's connector-owned cleanup step (`purgeSteps`) drains `raw.jira_issues`/`raw.jira_entities`
(V10, plan §0 A2), `raw.jira_changelogs`/`raw.jira_worklogs` (V11) AND `norm.*`'s work items/
intervals/changes/worklogs/reference rows (V13, plan §0 A3, see "Normalized layer" below), in that
order — `source_connections.profile`/`profile_at` are left untouched by PURGE (the connection's last
computed profile stays visible until it either resyncs or is deleted outright). **A generic,
connector-agnostic PURGE step runs AFTER the connector's own `purgeSteps`** (v0.3.0 M1 commit 4,
`ingest/IngestWorker.kt`'s `runJob`, gated on `claim.kind == PURGE`, running the `PurgeStep`s
`metrics/` registered in `ingest/JobHandlers.kt`'s registry):
`MetricsConfigService.purgeConnectionConfig` drains this connection's per-connection
`metrics.*` configuration tables (the six) (V15, `.claude/docs/persistence.md` "The `metrics` schema —
configuration (V15)", `.claude/docs/metrics.md` "PURGE and the metrics config") — small tables,
rebuilt wholesale on every config PUT already, cleared outright rather than batched. Deliberately
NOT part of `JiraConnector.purgeSteps`: the config it drains holds no Jira-specific shape, so it
lives beside the worker's OTHER connector-agnostic PURGE work instead of being duplicated per
connector kind (a future GitLab connection's PURGE job runs the exact same step).

## Ceiling trips and the pause (V37)

A SYNC or RECONCILE can stop on purpose at a bound instead of breaking: a paging/run/answer ceiling (`LIMIT_EXCEEDED`,
`jira-integration.md` "Paging ceilings") or the job's wall clock (`TIME_EXCEEDED`, below). Both fail the job, are counted, and a
connection that trips the same bound run after run is stopped instead of left to repeat itself every up-to-6 hours forever. SYNC and
RECONCILE each have their OWN counter and pause (V37 / V38); neither touches the other job. Code:
`ingest/SyncPause.kt` (the SYNC writes, as extensions on the caller's transaction like `ScheduleBackoff.kt`),
`ingest/ReconcilePause.kt` (the RECONCILE twin), `ingest/SyncPauseAnnouncement.kt` (the ERROR line and audit events, both jobs),
`jira/JiraCeilingTrip.kt` (typing).

**The per-job wall clock (`ingest.maxJobMinutes`, `INGEST_MAX_JOB_MINUTES`, 5..1440, default 60).** The worker gives every SYNC and
RECONCILE run a `SyncJobRunContext.maxRunMillis` (a duration; the other kinds are bounded by their own work; each ATTEMPT gets a fresh
budget). The `StreamContext` turns it into a deadline on ITS OWN clock when it is built (one clock for the start and every check; the
injectable `now` of the connector, so a test drives it with a fake). It is checked in ONE place, `StreamContext.heartbeat()`: once
`clock() >= deadline` the heartbeat first flushes the progress and the lease, then throws `CeilingTripException(TIME_EXCEEDED)`. Heartbeats
sit between pages (after a page's transaction; two come before a write, below), so the graceful stop is BETWEEN pages and the next run resumes
from the stream cursors exactly like an ISSUES run-ceiling trip (RECONCILE's pass is the exception: a new job starts a fresh sweep,
"RECONCILE stream"). The WORKLOGS stream stores the issues it had already fetched before it rethrows. **The graceful stop is between pages; the hard stop
backs it up, so the overrun is bounded by the grace.** A Jira call already in flight runs to its own deadline (`jira.requestDeadlineSeconds`,
180 s), a 429 tenant-pause wait is at most 120 s, and a page's transaction follows: left alone, those let a run overrun its budget by
~5 minutes. So `JiraConnector.withTally` runs the whole run inside `StreamContext.withHardStop`: a watchdog cancels the run IN FLIGHT once
`ingest.hardStopGraceSeconds` (`INGEST_HARD_STOP_GRACE_SECONDS`, 10..900, default 60) have passed after the budget (the worker hands the
connector `SyncJobRunContext.hardStopMillis` = budget + grace). The bound is therefore budget + grace (+ the close), not budget + ~5 min.
A healthy run never meets it: a page takes seconds, so the graceful check at the next heartbeat wins. What the cancellation does:
(1) the call or page in flight is abandoned; a page's transaction rolls back (`CeilingHardStopTest` pins that its locks are gone), and
a cursor only moves INSIDE the page transaction that wrote its rows, so cursors stay on the last committed page and the next run resumes
there, redoing at most that page (the WORKLOGS rows fetched ahead but not yet flushed are dropped and re-fetched; their issues are not yet
stamped synced); (2) the stop surfaces as the same `TIME_EXCEEDED` `CeilingTripException`, now with `hardStop = true`, and `madeProgress`
read the usual way (`changedInRun`), so `onFailed` closes the job through the NORMAL failure path (`SyncJobLeases.failTripped`: FAILED /
`TIME_EXCEEDED`, lease cleared, the heartbeat ticker cancelled with the job scope, the same back-off, trip counting, pause and
resumable-stop rules) and the job is never left `RUNNING` until its lease expires; (3) it is told from the graceful stop by the
`error_detail` (it names the grace and "mid-page"), by the INFO/WARN line (the resumable INFO says "hard stop: cancelled in flight" or
"stopped between pages") and by `hardStop = true` on the `sync_job.failed` audit event; the closed code vocabulary is unchanged. **It is not a
worker shutdown:** the watchdog cancels only the run's inner child, and `withHardStop` converts the cancellation into a trip only when it
fired the stop itself and the worker's own coroutine is still active (also when the cancelled work ends in an ordinary failure, e.g. a transport reporting its cancelled call as an I/O error: the trip is thrown with that failure suppressed); a cancellation from outside (shutdown), or one the stop did not cause, propagates untouched, so
`runJob`'s `CancellationException` handler still releases the job to `PENDING`. The watchdog runs on real time (coroutine delay), not on the
injected clock the graceful check reads (a frozen or fake clock cannot model a call that never returns), and is disarmed for good the
moment the run enters a `LOCAL_STREAMS` stream, like the graceful check (so no Jira-calling stream may follow `process`/`profile`). Only SYNC and RECONCILE are bounded (the other kinds get no
`maxRunMillis`); a non-Jira connector ignores `hardStopMillis`. The whole PROCESS and PROFILE streams never call Jira and are exempt (`LOCAL_STREAMS`), so a run that finished every
Jira-calling stream is never recorded `TIME_EXCEEDED` for the local work after it. **Why 60:** the slowest full backfill measured on the real tenant took
~22 min before the fetch-ahead work and ~5 min for the largest project after it; the stub's SYNC takes seconds. 60 is ~3x the worst figure,
so a healthy run never meets it, while a run that grinds for an hour is an outage or a bug worth stopping (every committed page is kept).
The floor of 5 keeps the REFERENCE pass (about a minute at most) from eating the whole budget.

**Typing.** `JiraConnector.withTally` wraps a failure carrying a `LIMIT_EXCEEDED` `FetchException` (anywhere in its causes, so the
run ceilings, the answer ceilings and `SkipBudget` all count) in a `CeilingTripException(code, madeProgress, underlying)`; the clock's trip
is one already. `IngestWorker.onFailed` closes the job with the trip's code as `sync_jobs.error_code` (and the connection's
`last_sync_error_code`) instead of `RUN_FAILED`, and the `error_detail` of the failure it wraps; the audit `sync_job.failed` carries it.

**What counts: a stored CHANGE.** A trip made progress when the run STORED something new, not merely when it committed a page.
`StreamContext` keeps two counts, `changedInStream` (reset when `currentStreamName` is set) and `changedInRun`, bumped by
`heartbeat(changed = true)` and `markChanged()`. The flag has no default and is derived from the write's own outcome
(`RawUpsertOutcome`: anything but `UNCHANGED` is a change): REFERENCE (`JiraReferenceWrites.storeEntities` for a page, the single
`upsertEntity` outcome for the per-key steps; PROJECT_FIELDS' touch-only skip is none), ISSUES (`writePage`'s batch or one-by-one
outcomes; the moved-out-of-scope tombstoning is a `markChanged()`). A stream that cannot re-commit identical pages
keeps `true`: CHANGELOGS (marks the claimed issues synced, draining the stale set), WORKLOGS (the same, or a monotonic time cursor), PROCESS,
PROFILE and RECONCILE. Three heartbeats pass `false` (they renew the lease and enforce the clock, nothing more): the in-scope boards pages
of REFERENCE (`fetchInScopeBoards`), the per-issue fetch of the WORKLOGS backfill (`settleFetch`, before `flushBackfill`) and the connector's
last best-effort heartbeat after a failure. Rows a stream flushes between heartbeats count too (`markChanged()` from `flushBackfill`), and
`keepFetched` rebuilds a clock trip after flushing the already-fetched issues, so a stop that stored them reads as progress.
`StreamContext.ceilingTrip` reads the right count by the trip: a **wall-clock stop reads the whole run** (`changedInRun`: the clock does
not blame the stream it happened to stop in, so a late stream that stored nothing after an early one stored a lot is progress); a
**`LIMIT_EXCEEDED` reads the tripping stream's own changes** (`changedInStream`), deliberately not the run's, because REFERENCE changes
something most days and would mask a stuck ISSUES. So a `LIMIT_EXCEEDED` on the first page of its stream (an oversized single issue, a
boards answer ceiling, a `SkipBudget` run that settled nothing), and a hard-cap or restarted loop that re-stores identical pages every run
(REFERENCE's all-boards SPRINT pass, an ISSUES restart from `backfillFrom`), are no progress and pause after N runs. Only a SYNC trip
WITHOUT progress counts toward the SYNC pause. **A RECONCILE trip counts toward its OWN pause (V38), by the same rule**: only a trip that stored NO durable
change counts (counting it into the SYNC counter would instead pause a healthy SYNC). What is durable in a RECONCILE: the tombstones
(`reconcileMissing`: a 404 or a moved-out issue leaves the next run's candidate set, `deletedAt`/`movedOutAt IS NULL`) and the index-gap upserts
(`reconcileIndexGap`, an outcome that `isChange`); both heartbeat `changed = true`. The id sweep's pages heartbeat `changed = false`: its
seen-set is scratch keyed by the job id, so a stop inside the sweep leaves the next run exactly where this one began. A big project
deleted or moved therefore tombstones a few thousand candidates per run, each such trip resets the count, and the set shrinks to a run that
finishes instead of pausing before it converges. A trip WITH a durable change is still not "resumable" (only a SYNC's is, `failTripped`), so
its failure back-off (15 min to 6 h) keeps working beside the counter. Manual and scheduled RECONCILE runs count alike.

**A wall-clock stop WITH progress is not a failure.** A SYNC stopped by `TIME_EXCEEDED` after any stream of the run stored a change is a normal
"continue next run": `SyncJobLeases.failTripped` closes the job `FAILED` with `error_code = TIME_EXCEEDED` (the record; no new terminal state),
but `recordResumableStop` sets `next_sync_at = now + syncInterval`, resets `limit_trips` to 0 and touches neither `consecutive_failures` nor
`last_sync_error_code`. The worker logs it at INFO (no stack trace) and audits `sync_job.failed` with `resumable = true`. **Remaining look-alikes:** the
job row itself is `FAILED`/`TIME_EXCEEDED`, so the job history, `lastSync`/`lastBackfill` and the telemetry panel's `sync.failures` series
(counted from `sync_jobs.status`) count it as a failure; read `error_code` to tell a clock stop from a break. A `TIME_EXCEEDED` WITHOUT progress behaves like a no-progress `LIMIT_EXCEEDED` (back-off, counted); a `LIMIT_EXCEEDED` WITH
progress keeps the back-off but is not counted. So `pause_reason` (a CHECK-enforced closed vocabulary) is either code.

**The counter (`source_connections.limit_trips`, V37) and the pause (`paused_at`, `pause_reason`)** - the SYNC half; the RECONCILE half is below. `SyncJobLeases.failTripped` writes,
in the SAME transaction as the job's FAILED write and the failure back-off (`recordFailureBackoff`, so the usual fences hold: a stale
attempt writes nothing, and a job of an older `config_revision` records neither back-off nor trip): a no-progress trip raises
`limit_trips`; when it reaches `ingest.pauseAfterLimitTrips` (`INGEST_PAUSE_AFTER_LIMIT_TRIPS`, 1..100, default 5) and the connection is
not yet paused, `paused_at` = now and `pause_reason` = the trip's code. A trip that made progress is not counted and RESETS the count to 0 (the trips must be in a row, so a still-advancing backfill cannot reach the pause through scattered no-progress trips),
an ordinary failure (`RUN_FAILED`, `RATE_LIMITED`) leaves the count alone, and a succeeded SYNC (`recordSyncSuccess`) resets it (and clears
a pause only a job already in flight could still reach). After the commit the worker logs ONE ERROR line (constant text: the data source
id, the trip code, the count and the threshold, never host text) and audits `data_source.paused` (`job = SYNC`).

**The RECONCILE counter and pause (V38: `reconcile_limit_trips`, `reconcile_paused_at`, `reconcile_pause_reason`).** The same shape and fences
as the SYNC half, written by `SyncJobLeases.failTripped` (`recordReconcileTrip`) in the job's closing transaction: every RECONCILE trip with no
durable change raises `reconcile_limit_trips` and one with a change resets it (see "What counts"), and at `ingest.pauseAfterLimitTrips` (the one threshold for both jobs) with no pause yet
`reconcile_paused_at` = now and `reconcile_pause_reason` = the trip's code. An ordinary RECONCILE failure leaves the count alone; a succeeded
RECONCILE (`recordReconcileSucceededRow`, which also clears the back-off) resets it. A stale attempt and a job of an older `config_revision`
write nothing. After the commit the worker logs the same kind of ERROR line (logger `ch.nokillswit.ingest.SyncPause`, text naming RECONCILE)
and audits `data_source.paused` with `job = RECONCILE`: ONE event name for both pauses, told apart by the `job` field (the SYNC event carries
`job = SYNC` too), so one alert rule covers both.

**Paused means:** `dueForSync` skips the connection (`paused_at IS NULL`), so no SCHEDULED SYNC is enqueued; a job already queued or
running finishes. **Scheduled RECONCILE is NOT paused by the SYNC pause**: it has its own back-off (and its own pause, below) and keeps the reports fresh through
PROCESS + DERIVE while a later SYNC stream is stuck. Symmetrically a RECONCILE-paused connection (`dueForReconcileRows` skips
`reconcile_paused_at IS NOT NULL`) keeps its scheduled SYNC; only the tick's scan-to-enqueue window can produce a RECONCILE after its pause starts (the open-per-kind unique index means none is queued or running when a pause commits). Nothing else changes: the data already synced stays, the chained DERIVE and the reports keep working
(`enabledActiveConnections`, the DERIVE bump scan, ignores the pause), a manual "Reconcile now"/"Reprocess" runs (also while paused), and PURGE of a
soft-deleted connection is unaffected. For the SYNC pause `status.state` reads `FAILED` (the trip code is the `lastSyncErrorCode`; a RECONCILE pause leaves `state` and `lastSyncErrorCode` alone); the response adds
`status.pausedAt`/`pauseReason`/`limitTrips` and, for RECONCILE, `status.reconcilePausedAt`/`reconcilePauseReason`/`reconcileLimitTrips`, the
telemetry panel's freshness carries `pausedAt`/`pauseReason` (its `nextSyncAt` never fires while paused) and `reconcilePausedAt`/`reconcilePauseReason` and shows a Paused badge for the SYNC pause and a "Reconcile paused" badge for the RECONCILE one, and the data-source pages show an orange Paused badge with the reason and count and a hint
("web-features.md" "Data sources").

**Resuming (ADMIN only, no endpoint of its own).** "Sync now" (`POST .../sync-jobs` with `SYNC`; `SyncJobRoutes` calls
`DataSourceService.resumeFromPause` before it enqueues) and any config PUT (`DataSourceService.update` clears the pause with the failure
back-off, `clearPause`) set `paused_at`, `pause_reason` and `limit_trips` back to null/null/0 and audit `data_source.resumed`
(`via` = `SYNC_NOW` | `UPDATE`, `job` = `SYNC`, `reason`, `limitTrips`) ONLY when a pause was lifted (a bare counter is cleared silently). The
RECONCILE pause is lifted the same way, by "Reconcile now" (`POST .../sync-jobs` with `RECONCILE`; `resumeFromReconcilePause`, `via` =
`RECONCILE_NOW`, `job` = `RECONCILE`) or any config PUT (`clearReconcilePause`; a PUT that lifts both audits two events, one per `job`).
Each manual request lifts ONLY its own kind's pause: "Sync now" leaves a RECONCILE pause, "Reconcile now" leaves a SYNC pause, and "Reprocess"
resumes neither. The count starts again from zero, so a still-broken connection needs N more futile runs to pause again. Tests: `CeilingPauseTest` (the SYNC rule, the pause, the reset, the resumable clock stop, the scheduler skip, the fences, the HTTP resume
and its audit), `ReconcilePauseTest` (the RECONCILE twin: only a trip without a durable change counts, the pause and its audit, the reset, the fences, the scheduler skip,
which request lifts which pause), `JiraCeilingTripTest` (the clock with a fake one, resume from the ISSUES cursor, the WORKLOGS flush, the typing),
`CeilingHardStopTest` (the hard stop: a hung page cancelled at budget + grace, the transaction rolled back, the job closed, the cursor and the resume, shutdown unaffected, local streams exempt), `PauseMigrationTest` (V37 and V38 on an existing row, SQLSTATE 23514 on the CHECKs), `NumericConfigBoundsTest`.

## Sync cursors (V9)

`sync_cursors` (`ingest/SyncCursors.kt`'s `SyncCursorsService`) is the resumable per-stream cursor
store the sync streams read from and write to (REFERENCE, ISSUES, CHANGELOGS, WORKLOGS and
RECONCILE — PROCESS and PROFILE deliberately never join it, see below): one row per
`(connection_id, stream)` (composite PK), where `stream` names a phase within a job (e.g.
`"issues"`, `"changelogs"` — the exact names are owned by each stream's implementation, not fixed
here). `cursor` is jsonb text (`infra/db/Jsonb.kt`) with a per-stream shape; `watermark_at` and
`last_completed_at` are the stream's own bookkeeping. `SyncCursorsService.put` upserts by
`(connection_id, stream)` and runs in the SAME transaction as the stream's page write (REFERENCE and
ISSUES do this as of this commit — see "Streams" below), so a crash never leaves a cursor pointing
past data that was never committed. **PROCESS is deliberately the one stream with NO `sync_cursors`
row of its own** — its resumability comes entirely from `raw.jira_issues`' own
`needs_processing`/`processing_version` columns (see "Normalized layer" below), which already double
as a durable, queryable "what's left to do" list; a parallel serialized cursor would just be a
second copy of the same position. **PROFILE has no `sync_cursors` row either, for a different
reason**: it never pages and never resumes — a single wholesale recompute pass every time, with no
partial-progress state worth persisting between attempts (see "Data profile" below).

## Streams

The REFERENCE, ISSUES, CHANGELOGS, WORKLOGS, PROCESS and PROFILE streams (v0.2.0 plan §7/§8/§9,
plan commits 6-9) are the ordered list a SYNC job runs (`jira/JiraConnector.kt`'s `runSync`):
**REFERENCE → ISSUES → CHANGELOGS → WORKLOGS → PROCESS → PROFILE**; a REPROCESS runs **PROCESS → PROFILE**. The RECONCILE stream (plan
commit 7, V12, see "RECONCILE stream" below) is a SEPARATE, single-stream job kind, not a sixth
entry in the SYNC list — a RECONCILE job runs `reconcile` then PROCESS (`runReconcile`, see
"Normalized layer" below), never PROFILE and never the other SYNC streams. Every stream implements
`ingest/Stream.kt`'s `Stream` interface and shares one `StreamContext` per job (cursor read/write
scoped to the connection, a `transaction {}` wrapper, `heartbeat()`, an injectable `clock`, plus the
progress-counter API described in "Progress counters" below) — PROCESS and PROFILE are the two
streams that never call the cursor read/write half of that contract (see "Sync cursors (V9)"
above).

**The GitLab connector's SYNC** (`gitlab/GitLabConnector.kt`; `gitlab-integration.md` "Streams") runs **PROJECTS → USERS → COMMITS → COMMIT FILES** inside the same
`ConnectorRun.withTally` scaffolding as Jira (the `CallTally` around the streams, the carried counters of a retry, the typed ceiling trip,
the hard stop; `ingest/ConnectorRun.kt`, which also holds `ceilingTripOf`). It first re-probes `/personal_access_tokens/self` and refuses
a token with a scope outside the read-only allow-list (`JobRefusedException(TOKEN_SCOPE_TOO_BROAD)`) or an inactive one
(`TOKEN_INACTIVE`) before anything is stored. RECONCILE and
REPROCESS are accepted no-ops until PR 9/10, PURGE drains `raw.gitlab_*` (commits first; their files cascade).

**Cursor shapes.**

- **`projects`** / **`users`** (`gitlab/GitLabCursor.kt`): `{passStartedAt, groups, groupIndex, page}` and `{passStartedAt, page}`, written with each
  page's rows in one transaction and cleared at the end of a completed pass (the sweep's reference instant survives a resume); a cursor with
  `groupIndex == groups` means only the sweep is left. A `projects` cursor resumes only when its `groups` equal the configured group paths;
  otherwise (an edited list, or a cursor written before the field existed) it is dropped and the pass restarts.
- **`commits`** (`CommitsCursor`): `{passStartedAt, lastCompletedProjectId, walk}` with `walk = {projectId, mode FULL|RANGE|RELIST, refName, since, page, tipSha, sinceMillis, listingDone}`;
  every listing page commits with it, a RELIST's last page sets `listingDone` (the sweep's `merge_base` calls run outside a transaction and resume from it), a
  walk for a branch other than the stored `default_branch` is dropped, cleared when the pass ends. **`commit_files`** has no cursor: the backlog (`raw.gitlab_commits`
  with `files_fetched_at` null, live, non-merge) is its state.
- **`reference`** (`jira/JiraReferenceStream.kt`'s `ReferenceCursor`): `passStartedAt` (fixed for
  the whole pass, preserved across a resume), `step` (a `JiraEntityKind` — doubles as
  `raw.jira_entities.kind`, in the FIXED order the enum declares: `FIELD`, `STATUS`,
  `STATUS_CATEGORY`, `PROJECT`, `PROJECT_STATUSES`, `ISSUE_TYPE`, `PROJECT_FIELDS`, `RESOLUTION`,
  `ISSUE_LINK_TYPE`, `USER`, `BOARD`, `BOARD_CONFIGURATION`, `SPRINT`), an optional `boardId`
  (diagnostic only, for the two board-scoped steps) and `startAt` (a real Jira `startAt` for the
  `startAt`-paged steps, or an index into the in-scope project-key/board list for the per-item
  steps). `PRIORITY` is still a `JiraEntityKind` value but is no longer fetched (2026-10-08; its
  endpoint has no granular OAuth scope and nothing reads the raw rows): the value is kept for
  persisted `raw.jira_entities` rows and in-flight cursors, a resumed cursor naming it restarts the
  pass at step 0 (idempotent), and the end-of-pass sweep tombstones a connection's old `PRIORITY`
  rows. The rationale is in `jira-integration.md`.
- **`issues`** (`jira/JiraIssuesStream.kt`'s `IssuesCursor`): `watermarkAt` (null until a first run
  completes), `jql` (the exact query driving the CURRENT run, computed once and reused for every
  page — a real tenant's paging must reuse the same query text), `nextPageToken` (Jira's opaque
  cursor) and `runStartedAt` (this run's own start time, becoming the new watermark once the run's
  LAST page is written). Four nullable scope fields (defaults, no migration — the cursor JSON ignores
  unknown keys) record which scope the watermark stands for: `coveredProjectKeys`/
  `coveredBackfillFrom` (what a COMPLETED run downloaded — the watermark is only valid for these) and
  `runProjectKeys`/`runBackfillFrom` (what the CURRENT run's `jql` was built for). Both pairs move
  together on the last page: covered := run, watermark := `runStartedAt`. See "Watermark, overlap and
  relative JQL" below.
- **`changelogs`** (`jira/JiraChangelogStream.kt`'s `ChangelogsCursor`): a SINGLE field,
  `bulkUnavailableUntil` (null by default) — see "CHANGELOGS stream" below for what sets/reads it.
  The stream needs no page-position cursor of its own: an interrupted batch's issues are simply
  still stale on the next run (nothing was marked synced, and `insertChangelog`'s `ON CONFLICT DO
  NOTHING` makes a re-fetched history a no-op).
- **`worklogs`** (`jira/JiraWorklogStream.kt`'s `WorklogsCursor`): `updatedSince`/`deletedSince` —
  see "WORKLOGS stream (A1)" below for their initialization and advance rule. The per-issue backfill
  half of the stream needs no cursor of its own either, for the same reason as CHANGELOGS: a stale
  issue (`worklogs_synced_at IS NULL`) simply stays stale until backfilled.
- **`reconcile`** (`jira/JiraReconcileStream.kt`'s `ReconcileCursor`): `passStartedAt` (fixed for the
  whole pass), `nextPageToken` (the id-sweep's own resume point), `jql` (the exact sweep query of
  this pass, computed once — Jira ties a page token to its query text), `windowStartMillis` (where
  the sweep window starts, which the anti-join reuses) and `jobId` (the job that started the pass)
  — see "RECONCILE stream" below. A pass is resumed only by that same job and only while it has a
  page token; a cursor from another job, a legacy cursor written before the sweep was windowed
  (its token belongs to the old unbounded query) or a finished sweep a crash left behind is
  dropped and a FRESH pass starts (`passStartedAt` = now, the scratch ids drained).

- **`trim`** (removed in 1.26.2; see "Upgrade path"): no stream writes or reads it any more. An install that ran the old TRIM step keeps its `sync_cursors` row (`stream = 'trim'`, `phase: DONE`); it is
  inert (the status endpoint lists it like any other cursor, and nothing parses it), so no migration removes it.

**Per-page transaction with the cursor advance.** Every page (or, for REFERENCE's single-shot
steps, every whole step) writes its raw-store rows and its own `sync_cursors` row inside the SAME
`context.transaction {}` block, so a crash between them is impossible: a committed cursor always
describes a committed page. The one deliberate exception is ISSUES' skip-a-bad-issue fallback ("ISSUES: a bad issue is skipped, loudly" below): it stores the surviving issues and then writes the cursor in separate transactions, but the cursor still only advances after every issue it covers is stored or skipped. `context.heartbeat()` is called once per page, AFTER that transaction
commits — a lost lease is caught here, never before the write.

**Resume semantics.**

- REFERENCE resumes at the `step`/`startAt` its last-written cursor named: single-shot steps
  (`FIELD`, `STATUS_CATEGORY`, `ISSUE_TYPE`, `ISSUE_LINK_TYPE`) have no cursor of their own and
  simply restart from scratch (idempotent, hash-diffed); `startAt`-paged steps
  (`STATUS`/`PROJECT`/`RESOLUTION`/`BOARD`), the per-project-key `PROJECT_STATUSES` and `PROJECT_FIELDS` loops,
  the per-board `BOARD_CONFIGURATION` loop and the per-board-then-per-sprint-page `SPRINT` loop all
  resume mid-list/mid-page from their persisted `startAt`/index. `users/search` (`USER`) has no
  `total` in its response, so its own "last page" detection compares each page's size against the
  FIRST page size seen, not a resumed one — a resume recomputes that from its own first fetched
  page. Users are keyed by `accountId`, every other single/paged step's entity id is the payload's
  own `id` field.
- REFERENCE's `USER` step runs only when the users are stale (since 1.17.4, `jira-integration.md` "Users refresh"): a pass
  that finds every live `USER` row seen within `jira.usersRefreshHours` skips it, records `usersSkipped: true` on every
  cursor it writes after that (a resumed pass reads it back) and leaves `USER` out of its end-of-pass tombstone sweep.
- ISSUES resumes a persisted `nextPageToken` AS-IS (same `jql`/`runStartedAt`/token) if one exists —
  meaning a prior run was interrupted mid-page — AND its `runProjectKeys`/`runBackfillFrom` still equal
  the configured scope. If the admin changed the scope while the run was interrupted, the token (tied to
  a query for the old scope) is discarded and a fresh run starts; the covered scope and watermark stay as
  the interrupted run left them. Without a token it starts a fresh run from the last completed
  watermark.

**Entity tombstoning at pass end.** Once every REFERENCE step completes, `JiraReferenceStream.run`
tombstones every `JiraEntityKind` in one final transaction
(`JiraRawStore.markEntitiesDeletedNotSeenSince`, per kind, entities whose `last_seen_at` predates
`passStartedAt`) and clears the `reference` cursor (`context.clearCursor`) — a fresh pass starts
clean next time. The one exception to "unseen means tombstoned" is the optional `PROJECT_FIELDS` step: a project whose
call was skipped (any Jira-side failure but a blocked host, a bad shape, no inputs) `touch`es its previous entity (`last_seen_at` only) so
the sweep leaves the last known field scheme live (`jira-integration.md` "Project field schemes"). The second: a pass that SKIPPED
its `USER` step (the users were fresh, `jira-integration.md` "Users refresh") leaves `USER` out of the sweep. An entity or issue tombstoned in an earlier pass that reappears is resurrected
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

**Covered scope and the catch-up clause.** The watermark only says "everything updated since then has
been fetched" for the scope the run that set it searched, so the cursor records that scope
(`coveredProjectKeys`, `coveredBackfillFrom`; the current run's own pair is `runProjectKeys`/
`runBackfillFrom`). A fresh run compares it with the configured scope:

- **Retained** projects (covered ∩ configured) are searched since the watermark plus the overlap — or,
  when `backfillFrom` moved EARLIER than `coveredBackfillFrom`, since the NEW `backfillFrom`.
- **Added** projects (configured − covered, which includes a project that was removed and added back)
  are searched since `backfillFrom`. That re-search is also what resurrects a re-added project's locally
  tombstoned rows (`upsertIssue`'s tombstoned branch clears `moved_out_at`).
- The two windows go into ONE query, `JiraJql.incremental(clauses)`:
  `(project in (R) AND updated >= "-Na") OR (project in (A) AND updated >= "-Nb") ORDER BY updated ASC`.
  With only one clause (no project added, or none retained) the text is exactly the single-clause
  `incremental(projectKeys, sinceMinutes)` form — an unchanged scope sends today's query byte for byte.
  The very first run (no watermark) is one clause since `backfillFrom`.
- **Narrowing** (fewer projects, or a LATER `backfillFrom`) downloads nothing and keeps the watermark.
  The covered KEYS shrink to covered ∩ configured at the START of the run, in the same transaction as
  `markOutOfScopeProjects`'s tombstones (`shrinkCoveredScope`; `last_completed_at` and everything else in
  the cursor stay) — so a run that fails before its last page cannot leave a removed project "covered":
  re-adding it is then an ADDED project (full catch-up), not a retained one. The covered `backfillFrom`
  and the watermark move on the last page as usual.
- **An earlier `backfillFrom` re-reads the whole retained range once.** The retained projects are
  searched from the NEW date, not just the missing slice; issues already stored come back unchanged
  (`upsertIssue` → UNCHANGED, cheap), only the missing history is new.
- **Renaming a key in the connection** (OLD → NEW, e.g. after a Jira key rename) makes NEW an ADDED
  project: one full re-read of that project from `backfillFrom`; OLD drops out of the scope.
- **Legacy cursor.** A cursor written before scope tracking (no `covered*` fields, watermark set) is read
  as covering the CURRENT scope (`IssuesCursor.normalizedFor`), so deploying this re-downloads nothing;
  the fields are filled in by the next run.
- The one decision "has a completed run downloaded the configured scope" is
  `IssuesCursor.coversScope(projectKeys, backfillFromMillis)` — a watermark exists, no configured key is
  uncovered and `coveredBackfillFrom` is not later than the configured date. It is built from the same two
  helpers `freshRun` splits its clauses with (`uncoveredKeys`, `backfillMovedEarlier`), and RECONCILE's
  index-gap guard calls it.

**`CURSOR_EXPIRED` restart.** `HttpJiraClient.searchJql` (`jira/JiraClient.kt`) maps a 400/410
response to `CURSOR_EXPIRED` only when the request carried a `nextPageToken` (a first page's own
400/410 is a genuine `INVALID_RESPONSE` — there was no token to expire). `JiraIssuesStream` catches
exactly that code and starts a fresh run from the last completed watermark (`freshRun`), up to
`MAX_CURSOR_RESTARTS` (5) restarts within one stream invocation before letting the exception
propagate — a bound against a pathologically misbehaving upstream, not an expected real-world count.

**Oversized issue page (`LIMIT_EXCEEDED`).** A `search/jql` page over `jira.maxResponseBytes` (or over the node/depth ceiling of `jira-integration.md` "Node and depth ceiling"; a page of heavy
issues, even without the four excluded fields) used to fail the same page on every run. `JiraIssuesStream` now re-requests the SAME page (same token and JQL)
with `maxResults` halved, down to 1, and logs a WARN per halving (data source id, old and new size; no payload). The
smaller size is a local variable of the stream invocation: it holds for the rest of that run, is NOT written to the
cursor, and the next run starts at `jira.pageSize` again. One issue still over the cap fails the run with
`LIMIT_EXCEEDED` as before. Assumption and the sibling streams: `jira-integration.md` "Oversized issue page (`LIMIT_EXCEEDED`)". A `CURSOR_EXPIRED` right after a halving restarts without using up the restart budget.

**Paging ceilings (the MEDIUM loops of the 2026-10-25 security audit).** REFERENCE's paged steps, the sprint pass, the USER step, the in-scope
boards it holds, PROJECT_FIELDS' `projects/fields` fetch, one ISSUES run and one RECONCILE pass each carry a page ceiling and, where they
write or hold rows, a row ceiling (ISSUES and RECONCILE also a bytes-per-run ceiling), sized against the real tenant with wide headroom: `jira-integration.md` "Paging ceilings" has the figures,
the sizing against the production target (~10 projects x ~6.4k issues) and the per-loop rule. In short: PROJECT_FIELDS skips the project (its
endpoint is optional); every other loop FAILS the job `LIMIT_EXCEEDED` after the check but before the page's transaction, so the cursor
stays on the token or `startAt` that asks the refused page. **Resumable:** the REFERENCE paged steps and ISSUES continue from the cursor with
a fresh budget. **Hard scope caps:** RECONCILE (a new job never resumes a pass), the SPRINT pass and the in-scope boards (re-paged from 0
each run) never complete past their ceiling, and **a failed REFERENCE step fails the whole SYNC of the connection** (ISSUES and the rest do
not run). ISSUES and RECONCILE also refuse a repeated or over-long `nextPageToken` (`INVALID_RESPONSE`). A resumed ISSUES run counts
afresh, but a `CURSOR_EXPIRED` on the stored token restarts it from `backfillFrom`, so a scope past one ceiling may then never complete.
The ceilings are per run; across runs growth is paced by the failure back-off, not bounded (BACKLOG).

**ISSUES: a bad issue is skipped, loudly (decision 2026-10-15).** The first real SYNC stopped at the page holding an issue
with a NUL in its text: the page's one transaction failed, the cursor never moved past it, and every retry failed the same way
(the connection could never complete a SYNC). The NUL itself is now replaced on the way in (`persistence.md` "jsonb"), but the
class of failure must not pin a stream forever either. Two classifiers, both in `jira/JiraDataErrors.kt` and shared with PROCESS:
`isBadValueError` (SQLSTATE class 22, or Exposed's client-side `varchar(n)` check such as an `issue_key` over 20 characters — retrying
the same value cannot succeed) and the wider `isDataError` (that plus integrity violations, class 23). When a page's transaction
fails with a **data error** (the trigger), `JiraIssuesStream.writePage` redoes the page issue by issue, each in its own transaction:
- an issue that fails with a **bad value** is SKIPPED: one WARN with the issue key (cut at 40 characters, control characters
  replaced), id and data source id, plus the exception class and SQLSTATE or "client-side length/value check", **never the payload or
  the exception message**; counted in the `issuesSkipped` progress counter (absent when zero);
- a **class-23 failure that survives the isolated retry is rethrown, not skipped**: a PK race heals on the retry, and a NOT NULL or
  CHECK failure is a writer bug that must fail loudly; any other failure (an outage such as SQLSTATE 08xxx, a parse error) is
  rethrown too, and a page failure that is not a data error is rethrown at once, without the redo;
- the others land, and the cursor advances in its own transaction exactly as the page transaction would have written it
  (`issuesUpserted` counts what was stored);
- **if EVERY issue of a page of more than one issue is skipped, the run FAILS** (`IllegalStateException`, the cursor does not
  advance): that is systemic (a wrong column, a migration gap), not one bad row. A page of one bad issue is skipped normally.

Lease loss and cancellation are never swallowed (`catchingFailures`). A skip emits no audit event: like every stream-level signal
it lives in `sync_jobs.progress` plus the log (`observability.md`).
*The trade-off:* a skipped issue is a DATA GAP (it is absent from `raw.jira_issues` and so from every report) against a
connection that never completes a SYNC and is stuck on its first bad issue; the gap is the lesser evil, and it is loud (a WARN per
issue, a counter on the job). *Self-healing:* a skipped issue that was never stored is re-fetched whenever Jira lists it again —
the next SYNC's `updated`-ordered window returns it as soon as it is next edited, and a full resync (a fresh connection or an
earlier `backfillFrom`) returns it regardless; it stays missing for as long as the data error persists. **RECONCILE's index-gap
phase applies the same rule**: it fetches an id the sweep sees but the raw store lacks, and an issue that fails there with a bad value
is skipped with the same WARN shape and counted as `indexGapSkipped` (a persistently bad issue that ISSUES skipped would otherwise
fail every RECONCILE, never stamping `last_reconcile_at`); any other failure there still fails the pass. **When an issue that is
ALREADY stored is the one whose update fails**, the old row simply stays — stale, not missing — and RECONCILE sees no gap (the id is
stored), so only a later edit of the issue or a full resync heals it. The other FETCH streams do NOT get this rule: CHANGELOGS,
WORKLOGS and REFERENCE still fail the page on a database error (their only per-row skips are the `LIMIT_EXCEEDED` ones in
`jira-integration.md`). `JiraIssuesSkipTest` pins it (incl. the RECONCILE case, an outage-class and a class-23 failure being
rethrown, and the all-skipped page).

**A stored item over its ceiling is refused, loudly (decision 2026-10-28, 1.24.1).** `jira.maxResponseBytes` bounds a RESPONSE, not what the
raw writers store, and everything stored is later re-read into trees (PROCESS, the data profile, PROJECT_FIELDS, the inspector). So every
raw writer refuses an item over a stored-size ceiling (`jira/JiraStoredCeilings.kt`, `jira-integration.md` "Stored-size ceilings"):
`RawUpsertOutcome.REFUSED`, in the single-row and the batch form alike (the batch partitions BEFORE its diff and returns the outcomes in input
order). **Nothing is written** for a refused item; **an existing row is only touched** (`fetched_at` for an issue or worklog, `last_seen_at`
for an entity, so REFERENCE's end-of-pass sweep never tombstones it) and keeps its old payload, hash, `changed_at` and `needs_processing`.
The ceilings: an issue or reference entity over `jira.maxStoredPayloadBytes` (default 2 MiB, 256 KiB to 8 MiB) as canonical text; a history
over 512 KiB and a worklog over 16 KiB (constants) measured on the text as it arrived, BEFORE any parse. Each is measured as its RENDERED size
(`String.renderedBytes`): the text plus the plain digits PostgreSQL writes for an exponent number (`payload::text` of `[1e131071]` is 131,074 bytes) and the space it writes after every `,` and `:` outside strings, so what
PROCESS's claim and the inspector measure with `octet_length(payload::text)` is bounded too; an exponent alone refuses nothing. Startup fails unless
`jira.processPageBytes >= jira.maxStoredPayloadBytes + 512 KiB`, which guarantees a payload at the ceiling plus one history fits a page (an issue with many histories or worklogs can still total more and is skipped by PROCESS: stale, not absent). One WARN per refusal
(`Stored-size ceiling: refused <what> <id> of data source <id>: <bytes> bytes over the <n>-byte ceiling`, never the payload) and a counter
per stream: ISSUES `issuesSkipped` (and `issuesUpserted` excludes it), RECONCILE `indexGapSkipped`, REFERENCE `entitiesSkipped`, CHANGELOGS
`historiesSkipped`, WORKLOGS `worklogsSkipped` (the same counter as a `worklog/list` worklog over the response cap). A refusal is **not a
change** for the pause's progress signal (`heartbeat(changed)`, `RawUpsertOutcome.isChange`: REFUSED is neither a change nor an absence of one - the
row simply did not move, so it does not set `needs_processing` either). The cursor advances and the rest of the page,
batch or answer lands.
*The trade-off*, as for a bad issue: the gap is the lesser evil next to a stream that could never finish. A **new** refused issue is absent
from the raw store, so from every report, until a later run sees it updated (and it is then under the ceiling) or a full resync; a refused
**update** of a stored issue leaves the OLD payload (stale, not missing; its old `norm` rows stay as they were, a pending tombstone included, until the issue is
processed again, and a PROCESS-oversized issue likewise keeps its old `norm` rows: stale, not absent); a refused history is simply never stored (the issue is still
stamped synced, so it is not re-fetched until it changes); a refused entity keeps its previous row live. Real data is far below every figure,
and `SyncedStubFixture` fails if the full stub SYNC fires one (`JiraStoredCeilingTest`, with a padded item per page for each writer).

**Lease loss.** Every stream (REFERENCE, ISSUES, CHANGELOGS, WORKLOGS, PROCESS, PROFILE) lets
`context.heartbeat()`'s `LeaseLostException` propagate uncaught after every committed
page/step/transaction/batch — none of them catches it, matching `StreamContext`'s contract
(`.claude/docs/ingestion.md` "Lease and heartbeat" above): the job stops exactly where its last
committed cursor (or, for PROCESS, its last committed page) left it, safe to reclaim and resume by
any worker — every issue PROCESS hasn't yet reached is still `needs_processing = true` (or still
`processing_version`-stale), so the next PROCESS pass simply claims it again. PROFILE's single
`heartbeat()` call comes AFTER `updateProfile` already committed the recomputed profile, so a lease
lost right there still leaves the connection with its freshly recomputed profile in place — there is
nothing left for a resumed PROFILE pass to redo.

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
calls). Any OTHER `FetchException` status propagates rather than triggering the fallback.

**Oversized batch.** A bulk answer over `jira.maxResponseBytes` or its node/depth ceiling (`LIMIT_EXCEEDED`) halves the batch (claimed afresh at the
smaller run-local size, never persisted); a batch of one issue goes to the per-issue endpoint, and an issue over the cap
there too is skipped for the run (`changelogsSkipped`, one WARN per issue, stays stale, retried next run) — unless the run
skips more than `max(10, 10% of the issues it attempted)`, which fails the stream `LIMIT_EXCEEDED` (a too-small
`jira.maxResponseBytes` must not SUCCEED having skipped everything). **PROCESS does not wait for a skipped issue**: with no
history it normalizes as one open interval in its CURRENT status since creation (no anomaly flagged: it looks as if it never
changed status, skewing cycle time/flow efficiency); with an outdated history it tiles the old chain
(`STATUS_MISMATCH_WITH_CURRENT` only if the current status differs). A later run that fetches the changelog re-flags and
rebuilds it. The batch size only shrinks within a run (no regrow) and is not persisted — `jira-integration.md` "Oversized
changelog/worklog batch".

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

**Trimmed payload.** A history is stored with its `author` reduced to `{accountId, displayName, accountType}` (the rest of the embedded
Jira user - `emailAddress`, `avatarUrls`, `self`, `timeZone`, `active` - is a denormalized copy of the `USER` entity) and without the `items` of a
description edit or a remote link (`jira/JiraPayloadTrim.kt`), all before the payload is canonicalized and stored; the `author_account_id` column
is unaffected and the history row is never dropped, even with `items: []` (the backlog report counts every history as a touch). These are the
exceptions to the raw store's "exactly as Jira returned it" rule; the kept-keys lists, the reasons and the equivalence property are in
`persistence.md` "The Jira changelog/worklog raw store (V11)". Rows stored before the trim existed are covered by "Upgrade path" below.

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
   deleted) — first ingested, or resurrected (`upsertIssue` clears the stamp when it un-tombstones a row,
   because the incremental feed skips tombstoned issues and its cursor moves on) — are read via `GET /issue/{id}/worklog`
   (`startAt`-paged), the issues of a claimed batch fetched concurrently - at most `jira.maxConcurrentRequests` calls in flight, the
   client's own semaphore, `jira-integration.md` "Batched raw writes" - but settled in claim order with a heartbeat per issue, then written in ONE transaction per claimed
   batch of 100 (`upsertWorklogs`, whose INSERT is split into statements of at most 1 MiB of payload text, `MultiRowInsert.kt`, + the list `markWorklogsSynced`; a failure anywhere in the batch stores and stamps nothing of
   it, so the next run re-fetches at most 100 issues — `jira-integration.md` "Batched raw writes", `build-times.md` WHY 21). This is the only step that ever reaches back before the stream's own start —
   but it only ever asks Jira about issues Flow already knows are in scope (a row in
   `raw.jira_issues`), so it can never surface an out-of-scope worklog.
2. **Then the instance-wide incremental feed, scoped to THIS connection's own lifetime, not
   `backfillFrom`.** `updatedSince`/`deletedSince` (the `WorklogsCursor`) are initialized to THIS
   RUN's own start time on a connection's very first WORKLOGS run — never `backfillFrom` — so
   `/worklog/updated`/`/worklog/deleted` only ever cover time since Flow started watching this
   connection, not history from before it existed.

**Trimmed payload.** A worklog's `author` and `updateAuthor` are reduced to `{accountId, displayName, accountType}` and its `self` and `comment` are
dropped (`jira/JiraPayloadTrim.kt`) BEFORE the payload is canonicalized and hashed (`persistence.md` "The Jira changelog/worklog raw store (V11)"),
so a re-sync of an unchanged worklog produces the same sha256 (`UNCHANGED`: `changed_at` and `sha256` stay put; whether the issue is flagged for
reprocessing is decided by `markWorklogsSynced` on the backfill path, whatever the outcome), and a difference only in a dropped key (an avatar URL,
an e-mail address, a time zone, the comment text) is not a change. Rows stored before the trim existed: "Upgrade path" below.

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

**Answer ceilings (since the security review of #360).** A host that never stops paging, packs a giant answer or sends an `until` that
cannot be true ends in a typed failure, never unbounded growth or a silenced feed (`jira-integration.md` "Worklog answer ceilings", the
figures and the sizing): one issue's backfill chain is bounded in characters, worklogs and pages (past it the issue is skipped like an
oversized page, `worklogsIssuesSkipped`); an incremental feed page is refused BEFORE anything of it is stored or its cursor persisted when it
has too many entries, an `until` more than 5 minutes ahead of Flow's clock (`INVALID_RESPONSE`: persisted, it would make every later run
read nothing) or, with `lastPage=false`, an `until` that does not pass `since`; its `worklog/list` details are held within a whole-answer
ceiling and a run follows at most 300 pages of each feed (and a page is at most 2,000 entries) (`LIMIT_EXCEEDED`); a last page's `until` never rewinds the cursor (`max(since, until)`, one WARN). The job ends FAILED with the typed code in
`sync_jobs.error_detail` (the data-source status page), and the cursor stays at the last page that committed, so nothing is lost.

## Upgrade path

Every write path trims on the way in (`jira/JiraPayloadTrim.kt`, `JiraIssueLinks.kt`; `persistence.md` "The Jira raw store (V10)"), but nothing rewrites a row stored earlier: the one-off **TRIM** stream that
did (1.17.1 to 1.26.1, a rewrite of `raw.jira_issues`/`jira_changelogs`/`jira_worklogs`, rules version 1 and, from 1.25.0, version 2) was removed, because every install had walked it to `DONE`. An install that
still holds rows stored before 1.17.1 (the payload trim) or before trim rules v2 (1.25.0: `fields.subtasks`/`fields.environment` dropped, `fields.issuelinks[]` slimmed) must run ONE SYNC on a build that still has the
TRIM step (1.25.0 to 1.26.1) and let its `trim` cursor reach `phase: DONE`, `version: 2` (the data source status endpoint) BEFORE upgrading past this change; skipping that leaves the older, fatter rows at rest and
re-fetched documents hash differently from them (one spurious `CHANGED` per row, never wrong data). Trimmed data does not come back by itself: getting a dropped key back takes a drop-list code change, then a
PURGE and a full re-sync (a re-sync alone strips it again, and the ISSUES stream only re-reads issues updated inside its window; REPROCESS never calls Jira).

## RECONCILE stream

`jira/JiraReconcileStream.kt` (v0.2.0 plan §7/§12 item 7, V12) is the daily drift check: a stored
issue can silently fall out of step with reality between SYNC's incremental runs (Jira deletes it,
or moves it to a project outside `projectKeys`), and the incremental ISSUES stream — which only ever
asks Jira about issues its OWN `updated >= "-Nm"` window names — has no way to notice either case on
its own. RECONCILE runs as its OWN job kind (not a fifth SYNC stream, see "Streams" above); the
scheduler enqueues it once a day, past `reconcile_hour_utc` (see "Scheduling" above), and
`IngestWorker.onSucceeded` stamps `last_reconcile_at` (`DataSourceService.recordReconcileSucceeded`)
once the stream returns — RECONCILE's OWN job-level bookkeeping, parallel to how a SYNC's
`recordSyncOutcome` works.

**The id-sweep.** `JiraJql.reconcile(projectKeys, sinceMinutes)`
(`<scope> AND updated >= "-Nm" ORDER BY id ASC`) pages `search/jql fields=id`
(`RECONCILE_PAGE_SIZE` = 5000 ids per page) into the `raw.jira_reconcile_seen` scratch table (V12,
`.claude/docs/persistence.md` "The Jira RECONCILE scratch table (V12)") — every in-scope issue id
Jira reports for THIS pass inside the window, nothing else. **The window** is the one the ISSUES
stream's first run covers: `N` = WHOLE minutes (rounded down, so the sweep never starts before
`backfillFrom`) from the connection's `backfillFrom` (UTC midnight, `ingest/BackfillFrom.kt`'s
`backfillFromEpochMillis`, shared with the SYNC runner and the GitLab connector) to `passStartedAt`. Without the bound the
sweep would list every older in-scope issue, and each one would be fetched by the index-gap path one
`GET /issue/{id}` at a time. **Stored rows last updated before `backfillFrom` are kept but NOT
re-checked** — their Jira deletions and moves are not detected. The JQL and the window start are
computed ONCE per pass and stored in the cursor, so a resumed pass pages with the SAME query text and
anti-joins with the SAME window. A `CURSOR_EXPIRED` response restarts the pass the way the ISSUES
stream does (fresh `passStartedAt`/window/JQL, the abandoned sweep's scratch ids drained; bounded by
`MAX_CURSOR_RESTARTS`, then the error propagates). Each page's rows and its own `sync_cursors` row
(the `ReconcileCursor`) commit in the SAME transaction, then `context.heartbeat()` — the same
per-page-transaction-then-heartbeat shape every other stream uses.

**The anti-join, once the sweep completes.** Two disjoint checks decide what changed while Flow
wasn't looking, both keyed off the now-complete `raw.jira_reconcile_seen` set for this job:

- **`JiraRawStore.issuesMissingFromSeen`** — a non-tombstoned `raw.jira_issues` row of an IN-SCOPE
  project whose `issue_updated_at` is at or after the window start plus FIVE MINUTES
  (`RECONCILE_CANDIDATE_SLACK_MILLIS`) that this pass never saw. Scope is decided by project ID (see
  "Out-of-scope projects" below), never by the stored `project_key` text — a Jira project rename
  changes the key but not the id; when the scope cannot be resolved the project restriction is
  dropped (and a WARN logged). The window restriction is what makes "absent from the sweep" mean
  "gone"; the five minutes of slack only cover the relative bound's drift while the pass runs (Jira
  evaluates `-Nm` per request) so a row on the window's edge is not a false-positive probe — a small
  permanent blind spot, while a pass longer than that costs only no-op probes (a 200, still in
  scope). Probed individually via `GET /issue/{id}?fields=project,key` (`RECONCILE_ISSUE_FIELDS` — only
  what the decision needs, never the full document): a **404** means Jira deleted it
  (`JiraRawStore.markIssueDeleted` sets `deleted_at`); a **200** whose `fields.project.id` differs from the row's stored `project_id` AND is not an
  in-scope project id (by key only if the scope is unresolved) means it moved out of scope — an
  unchanged id is a rename, never a move (`markIssueMovedOut` sets `moved_out_at` AND
  refreshes `issue_key`/`project_id`/`project_key` to their new value in the same update — the issue
  really did get a new key when it moved projects). Either way `needs_processing` is flagged, so the
  PROCESS step that ends this SAME job (see "Normalized layer" below) has a real reason to look at
  the row again, mirroring the tombstone onto `norm.work_items` immediately rather than waiting for
  the next scheduled SYNC. A 200 whose project is STILL in scope is a no-op (a transient
  sweep/anti-join mismatch — the id-sweep should have listed it; not expected in practice, so
  nothing is written).
- **`JiraRawStore.seenButUnknownIds`** — an id the sweep saw that `raw.jira_issues` never stored (an
  index gap: a missed ISSUES page, most likely). Fetched via `GET /issue/{id}` in full and written
  through `JiraRawStore.upsertIssue` — the SAME write path the ISSUES stream itself uses, flagged
  `needs_processing` by that path's own insert branch. **Guarded:** this phase is skipped while the
  `issues` cursor has no completed run covering the current scope (`coversScope`: no watermark yet, a
  configured project not covered, or `coveredBackfillFrom` later than `backfillFrom` — a first backfill
  or a scope catch-up is pending). Then "seen but not stored" just means the ISSUES stream has not
  downloaded that part yet, and fetching it one `GET /issue/{id}` at a time would duplicate its bulk
  work slowly; the skip is logged at INFO and counted as the `indexGapSkipped` progress counter (the
  number of ids left alone). A fetched issue the database rejects as a bad value is skipped and counted in the same counter ("ISSUES: a bad issue is skipped, loudly"). The deleted/moved-out phase above always runs. When no `PROJECT` reference
  entities exist at all (no SYNC has completed), the scope-not-resolved message is INFO too, not the
  rename/mistype WARN.

**Out-of-scope projects (local tombstone, no HTTP).** A project an admin REMOVES from the
connection's `projectKeys` is handled outside RECONCILE: `JiraIssuesStream.run` begins EVERY SYNC
with `JiraRawStore.markOutOfScopeProjects` — one `UPDATE` setting `moved_out_at = now` and
`needs_processing = true` on every stored, not-yet-tombstoned row whose `project_id` is not an
in-scope project id (key/project columns unchanged; counted as the `movedOutOfScope` progress
counter). The in-scope ids are `JiraRawStore.resolveProjectIds`: each configured key resolved
against the live `PROJECT` entities REFERENCE refreshed earlier in the same SYNC. **Scope is by id,
not by the `project_key` text on an issue row** (a rename would otherwise tombstone a live project's
issues every SYNC) and **fails safe**: if ANY configured key does not resolve to a current
(non-tombstoned) `PROJECT` entity — a renamed, mistyped or deleted project — the step is skipped
entirely with a WARN and nothing is tombstoned. The next PROCESS mirrors a tombstone onto
`norm.work_items` like any other moved-out issue. A re-added project's rows are resurrected by the
next SYNC's scope catch-up (the re-added project is searched again from `backfillFrom`;
`upsertIssue`'s tombstoned branch clears `deleted_at`/`moved_out_at`). Without this step RECONCILE
would probe every stored issue of the removed project one by one.

**Idempotent by construction.** `markIssueDeleted`/`markIssueMovedOut` are guarded on
`deletedAt.isNull()`/`movedOutAt.isNull()`, so a second RECONCILE pass over the SAME drift (e.g. a
retry after a lease loss) is a no-op rather than re-flagging `needs_processing` or re-stamping the
tombstone timestamp. The scratch table itself is scoped by `(connection_id, job_id)`, so a resumed
pass under the SAME job re-inserts idempotently (`ON CONFLICT DO NOTHING`) rather than duplicating.

**Scratch cleanup.** Once both anti-join checks complete, `JiraRawStore.clearReconcileSeen` drains
every scratch row for the connection and `context.clearCursor("reconcile")` clears the cursor, in
ONE final transaction — a crash before that point simply leaves the scratch table for the NEXT run's
own anti-join to work from once the sweep re-completes (the ids are re-collected, never lost, and
the PK's idempotent insert means re-collecting is always safe).

## Normalized layer

`jira/JiraProcessStream.kt` (v0.2.0 plan §7/§8, plan commit 8a, V13) is the PROCESS stream: it
rebuilds `norm.*` reference rows once per run, then replaces every stale raw issue's normalized
rows batch by batch. `norm/Tiling.kt` is the pure interval math; `norm/Normalization.kt` is the
connector-agnostic glue; `jira/JiraNormalizer.kt` is the Jira-specific parser that feeds it (see
`.claude/docs/persistence.md` "The normalized layer (V13)" for the schema/table side).

**Facts only — no interpretation.** The normalized layer stores what happened (a status was "3"
from this millisecond to that one, an issue was assigned to this account, a field changed at this
time), never what it MEANS for a flow metric. Stage grouping (which statuses count as "active" vs.
"waiting"), active-vs-wait time, and team attribution are all Phase 3 concerns (the domain model
built on top of this layer, `CLAUDE.md`'s roadmap) — PROCESS never guesses at any of them.

**Status-interval tiling** (`Tiling.statusIntervals`, plan §8) builds one issue's status timeline
from its creation time, its current status id and its changelog status events, satisfying four
invariants on every issue, always:

1. The FIRST interval always starts at `created_at`, `source = CREATED`.
2. Every later interval is CONTIGUOUS with the one before it — interval `k`'s `to_at` equals
   interval `k+1`'s `from_at`.
3. EXACTLY ONE interval is open (`to_at IS NULL`) — always the last one.
4. Zero-length intervals are ALLOWED (two changelog events at the same millisecond, or one exactly
   at `created_at`) — never merged or dropped.

`TilingTest` proves these hold over 200 random chain-consistent event sequences (property-style),
plus dedicated same-millisecond and empty-changelog cases; `NormalizationPipelineTest` re-asserts
the same four invariants over the PERSISTED rows for all 1,201 in-scope issues in the sample
dataset, not just `Tiling`'s in-memory guarantees.

**Anomalies are flagged, never corrected.** A chain that doesn't add up is recorded on
`norm.work_items.anomalies` and left there — the tiling result it comes from is never silently
"fixed" to make the anomaly disappear. Three codes (`TilingAnomaly`, `norm/Tiling.kt`):

- **`STATUS_CHANGE_BEFORE_CREATED`** — the very first changelog event predates `created_at`;
  clamped to `created_at` (producing a zero-length first interval), the anomaly is the flag, not a
  correction.
- **`STATUS_CHAIN_BROKEN`** — consecutive events don't chain (`events[i].fromStatusId !=
  events[i-1].toStatusId`); the computed interval always trusts the `to` chain, never the
  disagreeing `from`.
- **`STATUS_MISMATCH_WITH_CURRENT`** — the last computed status disagrees with the issue's current
  status; the stored interval keeps what the changelog chain computed, never the current value.

**A status item with no id (a status deleted in Jira).** A changelog status item can lack `from` or
`to` (absent, JSON null or blank) once Jira deleted that status — only `fromString`/`toString`
survive. Such a side gets a stable synthetic id from its display name, exactly as Jira gave it
(`jira/JiraDeletedStatus.kt`'s `deletedStatusId`): `deleted:<name>` when that fits in 50 characters
(every `status_id` column is `VARCHAR(50)`), else `deleted:<first 33 chars>~<8 hex of SHA-256 of the
full name>`, and `deleted:?` when there is no name either. The id matches no `norm.statuses` row, so
the status lookup resolves it to `(id, UNKNOWN)`: the interval lands in the UNMAPPED stage (flagged,
never guessed) and the name shows up as e.g. `deleted:Third Line` among the profile's unmapped
statuses and in the Data Quality report. Two sides lacking ids with the same name derive the same id,
so the chain stays continuous; a mismatch with the next event's real `from` is `STATUS_CHAIN_BROKEN`.
The event is deliberately NOT dropped — the time spent in the deleted status would silently merge into
its neighbour. No `PROCESSING_VERSION` bump: only issues that previously failed to normalize change.

**Field intervals** (`Tiling.fieldIntervals`, the same four-invariant construction rule minus the
status-specific anomaly checks) tile four fields into `norm.work_item_field_intervals`
(`TrackedField`): **ASSIGNEE**, **SPRINT** (`value_id` is the LAST id of a possibly multi-valued
carry-over set; `value_text` is Jira's own comma-joined `toString`, never recomputed from ids — a `TEXT` column since V18, the name list being unbounded),
**FLAGGED** (a boolean carried as the string `"true"`/`"false"` in `value_id`, `value_text` always
null) and **PARENT** (v0.3.0 M1 commit 2, V14 — tiles exactly like ASSIGNEE: `value_id` is the
parent issue id, `value_text` its key, `null` = unparented). A null `value_id` (unassigned,
unflagged, unparented) is preserved, never coerced to a sentinel.

**Parent-change detection (PARENT tiling, v0.3.0 M1 commit 2).** A changelog item counts as a
parent move (`JiraNormalizer.isParentChangeItem`) when its `fieldId` is `parent` (the sample
stub's own spelling and Jira Cloud's current field, replacing Epic Link), OR its `fieldId` matches
the REFERENCE stream's discovered legacy Epic Link custom field (`gh-epic-link`, `JiraFieldIds
.epicLinkFieldId` — defensive, no consumer in the sample dataset), OR its `field` display name is
one of `Parent`/`IssueParentAssociation`/`Epic Link` (a fallback for a real tenant whose changelog
uses a different `fieldId` than expected). **All three spellings need confirming against a real
tenant** (`.claude/docs/jira-integration.md` "Parent field spellings") — the sample stub only
exercises the `fieldId == "parent"` path. The first PARENT interval seeds from the first parent
move's `from` value (else the issue's current parent) — `Tiling.fieldIntervals`' own construction
rule needs no special-casing for this.

**Issue-key changes (A47, `PROCESSING_VERSION = 3`).** Real Jira sends a key change (a move to another
project) NAME-ONLY: `{"field":"Key","fieldtype":"jira","from":null,"fromString":"OLD-1","to":null,"toString":"NEW-2"}`, no
`fieldId`, the keys in the `*String` texts only; the stub spells it `fieldId: "issuekey"`. `fieldChangesFromHistory` keeps both
(a name-only match is the English display name `Key` with `fieldtype` `jira` or absent, never another present `fieldtype`, like the parent one otherwise), stores them with `field_id = 'issuekey'`, and
collapses a pair with the same `fromString`/`toString` seen under both spellings in one history to one row (only the texts are compared: the twin may fill `from`/`to` as well). The metrics domain history reads the keys
from `from_text`/`to_text` when the value columns are null. Version 3 reprocesses every issue so already-synced histories gain the rows.

**Field changes.** `norm.work_item_field_changes` (`jira/JiraNormalizer.kt`'s
`fieldChangesFromHistory`) keeps EVERY tracked changelog item verbatim, never tiled: status,
assignee, Sprint, Flagged, Rank, priority, resolution, issuetype, project, Key, story points,
`duedate`, EVERY `customfield_*` item (v0.3.0 M1 commit 2, V14 — never filtered, so a field the
metrics layer is later configured to read needs no REPROCESS to have its history already captured)
and every parent move. Each row also carries the changelog item's own `field_id` (V14's
`work_item_field_changes.field_id`, `WorkItemStore.fieldChangesByFieldIds` — the metrics layer's
per-field replay key; `field` alone is a display name only, unreliable across a field rename). This
is the only normalized record for the fields with no interval table of their own (priority,
resolution, issuetype, project, Key, story points, Rank, `duedate` and every other `customfield_*`
never get tiled — only status/assignee/Sprint/Flagged/PARENT do).

**Custom-field discovery by schema.** `JiraNormalizer.discoverFieldIds` resolves a real tenant's
own `customfield_NNNNN` ids ONCE per PROCESS run, from the REFERENCE stream's `FIELD` entities —
never hardcoded. Sprint/Rank/Team are matched by their UNIQUE `schema.custom` plugin key
(`gh-sprint`, `gh-lexo-rank`, `atlassian-team`); Story points and Flagged have no unique
`schema.custom` of their own on a real tenant (Story points is plain `...:float`, shared with any
other numeric custom field), so those two are matched by NAME instead (case-insensitive, "story
point"/"flagged" substring match). The legacy Epic Link field (`gh-epic-link`,
`JiraFieldIds.epicLinkFieldId`, v0.3.0 M1 commit 2) is discovered the same schema-custom way, for
PARENT tiling's fallback above.

**Custom-field current-value capture and `hierarchyLevel` (v0.3.0 M1 commit 2, V14,
`.claude/docs/domain-model.md` "Gaps in `norm`").** `JiraNormalizer.normalizeIssue` collects
EVERY non-null `customfield_*` key on the issue's current `fields` — never filtered by which ones
are "known" — canonicalizes the resulting object (`infra/json/CanonicalJson.kt`) and stores it
verbatim as `norm.work_items.custom_fields`; the metrics layer picks the configured fields at
DERIVE time, so a re-pointed estimate/work-category field re-derives without a REPROCESS. The
system `duedate` field's current value becomes `due_at` (epoch millis at start of day UTC — Jira's
plain `YYYY-MM-DD`, not a full timestamp). `hierarchyLevel` (an epic is level 1, never "type name =
Epic") is resolved from the REFERENCE stream's `ISSUE_TYPE` entities
(`JiraNormalizer.issueTypeHierarchy`, keyed by issue-type id) — **never from the issue document
itself**: a real tenant's issue-search response does not carry `hierarchyLevel` on
`fields.issuetype` (confirmed against the stub — `sample-data/jira-stub/__files/issuetype.json` is
the only fixture carrying it), the same reason `norm.statuses`' category lookup is resolved once
per run rather than trusted from the issue payload. The stored level is bucketed by `hierarchyBucket` (`norm/Hierarchy.kt`): PROCESS keeps every issue whatever its level, and
DERIVE leaves out the ones above the epic level (domain-model A31, `metrics.md` "Hierarchy levels").

**Unset fields are JSON `null`.** A real tenant sends an unset object- or array-shaped field (assignee, resolution,
parent, the Team/Sprint/Flagged custom fields, labels, components, fixVersions) as a literal JSON `null`, not absent
or `[]`. `JiraNormalizer` reads every such field through `orNullObject()`/`orNullArray()` (`jira/JiraJsonFields.kt`),
never a bare `.jsonObject`/`.jsonArray` cast; the first real SYNC failed every issue without a sprint on exactly that.

**Status category mapping.** `JiraNormalizer.statusRefs` maps each Jira status category to
`StatusCategory` (`norm/Tiling.kt`), in either shape: the object's `key` (`new → TODO`, `indeterminate → IN_PROGRESS`,
`done → DONE`) or the plain string enum real `statuses/search` returns (`TODO`/`IN_PROGRESS`/`DONE`, verbatim);
anything else (including absent, `UNDEFINED` or a wrong JSON type) `→ UNKNOWN`, never a failure. `norm.statuses` (rebuilt wholesale every PROCESS run)
is the lookup `Normalization.normalize` uses to attach a `(name, category)` pair to every tiled
status interval and to the issue's own current status.

**Tombstone mirroring.** A raw issue's `deleted_at`/`moved_out_at` (set by the RECONCILE stream, and
`moved_out_at` also by the ISSUES stream's local out-of-scope step at the start of every SYNC)
become the SAME columns on `norm.work_items`, on that issue's next ordinary PROCESS replace — no
separate code path (`TombstoneKind.DELETED`/`MOVED_OUT`/`NONE`, `jira/JiraProcessStream.kt`'s
`processOneIssue` reads the raw row's own tombstone columns before calling
`JiraNormalizer.normalizeIssue`). Because RECONCILE ends its own job with PROCESS (see "Sync-job
queue (V9)" above), the mirror lands in the SAME job the tombstone itself was set in, not the next
scheduled SYNC — `NormalizationPipelineTest`'s "RECONCILE's tombstone is mirrored onto norm
work_items in the same job" proves this against the `jira-day2` scenario's deleted/moved issue ids.

**PROCESS batching, failure isolation and progress.** `issuesToProcess` claims stale raw issues
(`needs_processing`, or a stale `processing_version`) ascending issue id, pages of 50
(`PROCESS_BATCH_SIZE`), keyset-paged (`afterIssueId` = the previous page's last id): every stale
issue is visited ONCE per run, so an issue that fails stays flagged for the NEXT PROCESS pass
instead of being re-claimed by the same loop forever (before the keyset, a permanently failing issue
made `run` spin). **One transaction per PAGE** (`context.transaction { }`): the page is read in three
queries (`issuesForProcessing`, `changelogPayloadsForIssues`, `worklogPayloadsForIssues` — all
`issue_id IN (…)`), each issue is normalized in memory (pure), then ONE
`WorkItemStore.replaceWorkItems` (a delete and ONE multi-row `insertRows` per `norm` table plus one
`upsertRows` — a multi-row `ON CONFLICT (connection_id, issue_id) DO UPDATE` — for `work_items`) and ONE
`JiraRawStore.markProcessedBatch` write the whole page. Plan §8 says "one tx per batch, per-issue
failure isolated" and PostgreSQL aborts a transaction on its FIRST failing statement, so isolation
is kept two ways: an issue that fails NORMALIZATION is skipped in memory (no statement ran — the
transaction stays valid, the other issues of the page land) and a DB error in the page write makes
the page fall back to the old per-issue path — each issue in its OWN `context.transaction { }`
(`processOneIssueSafely`, the shape `jira/JiraChangelogStream.kt`'s fallback uses too). Either way a
failing issue is simply left `needs_processing = true` — never rethrown, never aborting the rest;
the failing issue ids and the first failure's message are logged (`log.warn`), not threaded through
`sync_jobs.progress`. **Outcomes of a page-level DB error:** a PARTIAL failure (some issues land in
the fallback) ends the run normally — `issuesFailed` counts the rest and the next PROCESS pass retries
them. If EVERY issue of the page fails in the fallback too, the failures decide: when at least one is
a BAD-ROW error (a PostgreSQL data exception, SQLSTATE class `22`, or integrity violation, class
`23`, or Exposed's CLIENT-side `varchar(n)` length check — an `IllegalArgumentException` whose message
starts "Value can't be stored to database column because exceeds length", thrown before any SQL is
sent, so it carries no SQLSTATE; all found by walking the cause chain — `isDataError`; the four child tables are written by `insertRows` and `work_items` by `upsertRows`, which skip Exposed's client-side length check, so an over-long child or `work_items` value (e.g. `status_id` 50 on status intervals, `value_id` 200 on field intervals, `field_id` 100 on field changes, `author_account_id` 100 on worklogs) is PostgreSQL's own SQLSTATE 22001 — pinned by `NormalizationPipelineTest`'s "CHILD table column" case) the run still ends normally, so a single
permanently unwritable row alone on its page (however many issues the page holds) only counts
`issuesFailed` and is retried by the next pass; when NONE is a data error (a connection loss, a
timeout — an outage, not a row) the original database error is RETHROWN, the job fails, and a
transient outage cannot end SUCCEEDED and chain a DERIVE over stale `norm`. A failed SYNC is
rescheduled with backoff, a failed RECONCILE retried with its own ("Scheduling" above); a failed REPROCESS needs a manual re-run. **Locking:** the page's
raw issue read — and the per-issue fallback's — is `FOR UPDATE` (the page's ordered by `issue_id`),
held until the transaction commits (~50 rows, ~30 ms), so a concurrent re-flag of an issue (a changed payload
from ISSUES) waits and lands after the commit instead of being overwritten by the page's
`needs_processing = false`; the mark stamps `processed_hash` with the sha256 that was READ (a per-row
`CASE`), not the column's value at update time. **Memory:** a page holds its issues' payloads,
changelogs and worklogs at once, so it is CLAIMED BY STORED BYTES (1.24.1): `JiraRawProcessReads.issuesToProcessWithin` selects up to 50
stale ids after the keyset with `octet_length(payload::text)` of the issue plus correlated sums of its histories and its live worklogs (one
statement, only numbers in the SQL text) and claims them in order while the running sum stays within `jira.processPageBytes` (default 8 MiB,
2 to 64 MiB; the `payload::text` rendering is evaluated only for the <= 50 LIMITed ids, never for every stale row); the first issue that fits is always claimed. An issue whose OWN total is over the budget is **skipped, loudly**: one WARN with
its id and byte count (never the payload), the `issuesOversized` counter, left `needs_processing` (every run examines it again) and no
`norm` row written for it (an issue that already has `norm` rows keeps them: stale, not absent). The page's `FOR UPDATE` read, its heartbeat and
`issuesProcessed`/`issuesFailed` are unchanged. The reference kinds PROCESS rebuilds from (STATUS, USER, BOARD, BOARD_CONFIGURATION, SPRINT, and FIELD / ISSUE_TYPE for
the run's lookups) are read **in keyset chunks of at most `jira.processPageBytes` stored bytes, one kind at a time** (`JiraEntityReads`, `jira-integration.md`
"Stored-size ceilings"): each payload is parsed, reduced to its small row and dropped before the next, BOARD_CONFIGURATION is reduced to `(board id, columns)`
first and joined to its boards by id, so no kind size refuses, skips or stalls PROCESS (a whole-kind budget did, falsely, for a big tenant's USER kind, and
was removed). The same chunked reads serve the data profile, PROJECT_FIELDS' stored inputs and RECONCILE's `resolveProjectIds`. A BOARD_CONFIGURATION is parsed only when its board is live (boards are read first). **Read order is now ascending `entity_id`** (it was the heap's physical order): `FieldIdsCollector` takes the FIRST `FIELD` matching a marker, so when two fields match the same name marker (`story point`, `flagged`) the chosen id is now deterministic and may flip ONCE relative to a previous run; no `PROCESSING_VERSION` bump is needed (the next ordinary reprocess of an issue picks the new id up). The boot-time heap fit budgets the page (`jira-integration.md` "Stored-size ceilings"). Pinned by
`JiraProcessBudgetTest`.
`context.heartbeat()` and the `issuesProcessed`/`issuesFailed` counters are flushed once PER PAGE.
Why not per issue: measured on the stub (`.claude/docs/build-times.md` "PROCESS"), one issue cost
~13 statement round trips (three reads, four deletes, four inserts, a select, an upsert, a mark)
plus a transaction each — 14 ms per issue against the unpooled test database, 5-6 ms pooled; a page
costs ~13 round trips in total, and the 1,200-issue stub processes in ~2.5-3 s.
`NormalizationPipelineTest` pins both failure shapes and the one-issue page.

**Reference-row robustness (V19).** The reference rebuild (statuses, people, boards + columns, sprints) runs
once per PROCESS run BEFORE the page loop, outside the per-issue bad-row classifier, so an over-long Jira
value there used to throw Exposed's client-side `varchar(n)` check out of `run()` and fail EVERY PROCESS
(and so every SYNC chain, and no DERIVE). The rule now has three layers:
1. Every Jira-supplied free-text NAME column is `TEXT` (V19, the V18 pattern): the reference names, display
   names and e-mail, `metrics.dim_sprint.name`, AND the per-issue copies of the same names — the status name
   in `norm.work_items`/`norm.work_item_status_intervals` (filled from the `norm.statuses` lookup, so widening
   only the reference column would have turned a loud run failure into issues failing quietly), the issue type,
   resolution and priority names, the changelog field name (`norm.work_item_field_changes.field`: a custom field's
   DISPLAY name, up to 255 characters), what DERIVE copies the issue type into (`metrics.dim_task.issue_type`, the
   activity type that defaults to it, `metrics.activity_type_map`) and the work-category option id/label the metrics
   config stores (`metrics.work_category_map.value_id`/`value_name`; for a primitive-valued field the value text IS
   the id). The config PUT passes the user-entered activity type through `sanitizeSingleLine` (control characters
   are a 400) like the other display names; the Jira-sourced `issueType`/`valueId` it matches verbatim. Jira's own limits (a status or issue type
   name is capped at 60) already exceed some of the old columns (`issue_type` 50).
2. The bounded identifiers/enums (`status_id` 50, `account_id` 100, `board_type`/`project_key`/`state` 20) are
   checked by `WorkItemStore.replaceStatuses`/`replacePeople`/`replaceBoards`/`replaceSprints` BEFORE the write —
   a row whose bounded value overflows is SKIPPED (never truncated: a cut key would join to the wrong thing),
   logged once per table by `NormReferenceStore` (under the `ch.nokillswit.norm.WorkItemStore` logger name; `norm.<table> rebuild skipped N row(s)…`, the first five ids except
   for people, whose account ids stay out of the log) and returned as a count.
3. A table whose rebuild still fails with a bad VALUE (`isBadValueError`: SQLSTATE class 22 or Exposed's length
   check) keeps its previous rows — its transaction rolled back — logged by
   `JiraProcessStream.rebuildReferenceTable` and counted as ONE skipped row, however many rows the table had.
   Anything else still ends the run: a connection loss (an outage), a payload that does not parse (a connector
   bug) and an INTEGRITY violation (class 23: NOT NULL, duplicate key). The last is deliberately NOT tolerated
   here, unlike on the per-issue path (`isDataError` = bad value OR class 23): a per-issue row can collide with
   stale state, but the wholesale delete-then-insert rebuild has no legitimate collision, so class 23 there is
   a writer bug that must be loud.

The run's total lands in the PROCESS progress counter `referenceRowsSkipped` (absent when zero). Per-issue
columns that are still bounded (`issue_key`/`project_key` 20, `status_id` 50, account ids 100, `rank` 100, …)
are identifiers, not names: a bad value there is a counted bad row (`issuesFailed`) that stays `needs_processing`.
`ProcessReferenceRowsTest` pins all of it (clone-based, one flagged issue per run).

The REFERENCE stream's own raw write (`raw.jira_entities`) has no per-row skip, deliberately: its only bounded
value is `entity_id`, `VARCHAR(255)` since V20 (an accountId reached 76 characters on the first real tenant and
failed every SYNC at the USER step while the column was 50), which no Jira entity id approaches. A skip there
would silently drop a user or status that later joins fail on; a loud failure is the better signal for a shape
that should never occur.

**Worklog timestamps and sprint completion (v0.3.0 M1 commit 2, V14).**
`norm.work_item_worklogs` gains `created_at`/`updated_at` (`raw.jira_worklogs.payload` already
carried them; only `started_at` was kept here until now) — report 14's late-logging measure needs
when a worklog was actually entered/last edited, not just the time it claims to describe.
`norm.sprints` gains `complete_at` (`completeDate`) alongside the reference rebuild — the metrics
layer keys sprint periods on completion, not `end_at`.

**REPROCESS and version bumps.** `PROCESSING_VERSION` (`norm/Normalization.kt`, currently `2` —
bumped from `1` by V14 above) is bumped on ANY change to the tiling/write-shape rules;
`issuesToProcess` claims any issue whose stored `processing_version IS DISTINCT FROM` the current
constant automatically, so a version bump reprocesses the whole connection on its next PROCESS pass
with no separate migration step. A
REPROCESS job (`JiraConnector.runReprocess`) does the same thing on demand: it flags EVERY raw
issue `needs_processing` (`JiraRawStore.markAllNeedsProcessing`) then runs PROCESS, never
touching Jira. `NormalizationPipelineTest`'s "REPROCESS leaves the normalized digest unchanged"
proves REPROCESS rebuilds byte-for-byte identical `norm.work_item_status_intervals` rows (an MD5
digest over every issue's ordered intervals) — REPLACE is idempotent, not merely re-run-safe.

**Job orders.** A SYNC job runs REFERENCE → ISSUES → CHANGELOGS → WORKLOGS → **PROCESS** →
**PROFILE**; a RECONCILE job runs `reconcile` → **PROCESS** (never PROFILE); a REPROCESS job flags
every issue then runs **PROCESS** → **PROFILE** (see "Sync-job queue (V9)" above for the full
per-kind breakdown, and "Data profile" below for what PROFILE itself computes).

## Progress counters

`ingest/Stream.kt`'s `StreamContext.incrementProgress(key, by = 1)` accumulates a small per-stream
counter map in memory; `currentStreamName` (set by the job runner — `jira/JiraConnector.kt`, before
each stream's `run`) names which stream is currently active. Both are flushed onto
`sync_jobs.progress`/`sync_jobs.current_stream` by `context.heartbeat()` on every call
(`SyncJobsService.heartbeat`'s `progress`/`currentStream` parameters, written only when non-null —
the ticker's own lease-only heartbeat in `ingest/LeaseHeartbeat.kt` omits them so it never blanks out
the last value a stream's own heartbeat flushed). This is a lightweight, best-effort sync-progress
signal for the status endpoint below, not itself part of any correctness invariant — a page that
commits its raw-store write and cursor advance but then crashes before its heartbeat simply leaves
`progress` one page stale, exactly as safe to resume as the cursor it describes.

The counter names in use today (all stream-local, not enumerated anywhere — a new stream is free to
name its own): `pages` (REFERENCE per-step-or-page, ISSUES per page, RECONCILE per id-sweep page),
`entities` (REFERENCE, per entity upserted), `issuesUpserted` (ISSUES per page — the issues actually stored — and RECONCILE's own
index-gap fetch), `issuesSkipped` (ISSUES: issues the database rejected as a bad value or row, skipped with a WARN so the cursor can
advance — absent when zero, see "ISSUES: a bad issue is skipped, loudly"; also the issues the stored-size ceiling refused, "A stored item over its ceiling is refused, loudly"), `entitiesSkipped` (REFERENCE: entities over `jira.maxStoredPayloadBytes`), `historiesSkipped` (CHANGELOGS: histories over 512 KiB), `issuesOversized` (PROCESS: issues whose own stored text is over `jira.processPageBytes`, left unprocessed), `movedOutOfScope` (ISSUES, once per run: rows tombstoned because their project
left `projectKeys`), `changelogs` (CHANGELOGS, per history inserted), `worklogs` (WORKLOGS, per worklog
upserted), `worklogsOutOfScope` (WORKLOGS, A1's scope-filter drop count), `changelogsSkipped` (CHANGELOGS, an issue over `jira.maxResponseBytes` (or its node/depth ceiling) even alone, left stale for the next run), `worklogsSkipped` (WORKLOGS, one `worklog/list` worklog over the cap, or a worklog over 16 KiB refused by the stored-size ceiling) and `worklogsIssuesSkipped` (WORKLOGS, a backfilled issue whose per-issue page is over the cap, left unsynced) — see `jira-integration.md` "Oversized changelog/worklog batch", `tombstoned` (RECONCILE,
per issue flagged `deleted_at`/`moved_out_at`), `indexGapSkipped` (RECONCILE: ids left unfetched because the
ISSUES stream has not yet covered the scope, or whose fetched issue the database rejected as a bad value or the stored-size ceiling refused), `projectFieldsSkipped` (REFERENCE, one per project whose optional `PROJECT_FIELDS` step was skipped), `usersSkipped` (REFERENCE, `1` in the attempt that found the users fresh and did not fetch them),
`issuesProcessed`/`issuesFailed` (PROCESS, per issue
in a batch — see "Normalized layer" above), `referenceRowsSkipped` (PROCESS, once per run: reference rows left out, see
"Reference-row robustness"), `profileComputed` (PROFILE, always `1` — a single
recompute pass, not a per-row counter, see "Data profile" below).

GitLab's (`gitlab/`): `pages` and `projects`/`projectsSkipped` (PROJECTS: archived or outside-the-group projects the defensive filters left out, and projects whose
`default_branch` or path is longer than its column; a skipped project that was stored before is not seen, so the end-of-pass sweep
counts it as moved out),
`pages`/`users`/`usersEmailsSkipped` (USERS: users whose secondary emails were not fetched - not an admin token, or a 403/404 on the emails
call)/`usersSkipped` (the whole stream skipped: every live user was seen within `gitlab.usersRefreshHours`; "Sync now" never skips),
`commits` (COMMITS: commits inserted)/`commitsRelisted`/`commitsTombstoned`/`commitsSweepKept`/`commitsSweepUnconfirmed`/`commitsSweepDeferred` (a re-list and the confirmations of its
sweep)/`commitsProjectsSkipped` (no default branch, or the branch or project is gone), `commitFiles`/`commitFileRows`/`commitFilesCapped`/`commitFilesOversized`/
`commitFilesMissing` (COMMIT FILES: commits read, their file rows, the 1,000-file cap, the terminal errors) and `commitFilesPending` (the backlog left when the run stopped,
non-zero only; `gitlab.commitFilesPerRun` is the call budget). `gitlab-integration.md` "Streams" has each.

**Counters the connector adds (not a stream's):** `StreamContext(extraProgress = …)` is a `() -> Map<String, Long>` merged into every heartbeat's progress JSON (and
into `progressSnapshot()`). `JiraConnector` passes the job's `CallTally` (`jiraRateLimited`, `jiraBackoffMs`, `jiraTenantPausedMs`, `jiraNearLimitPauses`; definitions
in `jira-integration.md` "Rate limits and backoff"), only the NON-ZERO keys, so an unthrottled job carries none. **`backfill`** (value 1, set by the ISSUES stream at the
start of its run) marks a SYNC whose ISSUES run is a first download or widens the covered scope - `!IssuesCursor.coversScope(projectKeys, backfillFrom)` on the stored cursor
("Covered scope and the catch-up clause": no completed run, a configured project not covered, or `coveredBackfillFrom` later than the configured date); an incremental run, a
narrowing, and the resumption of an interrupted incremental run lack it, while an interrupted first run reads as a backfill again when it resumes. The telemetry panel takes the
latest job with the key (`JiraBackfillMarkerTest`) as "last backfill" and its `started_at`..`finished_at` as the duration; the sticky keys (`backfill` and the four `jira*` counters) survive a retry:
`JiraConnector` seeds a retried attempt (attempt > 1) from `SyncJobClaim.priorProgress` (the previous attempt's `progress` as the claim read it), because the retry's
first heartbeat replaces the job's `progress` wholesale; the tally's counters then add to the carried ones, `backfill` stays, and a stream's own counters restart. An
attempt that fails flushes one last heartbeat before it propagates (`jira-integration.md` "Rate limits and backoff").

## Sync status endpoint

`GET /api/v1/data-sources/{id}/status` (v0.2.0 plan §9/§12 item 7, `ingest/SyncStatusRoutes.kt`'s
`configureSyncStatusRoutes`, `ingest/SyncStatus.kt`'s `SyncStatusResponse`) is ADMIN-only and
read-only — a diagnostic view assembled over state every other endpoint already owns, not a new
source of truth:

- **`connection`** — the ordinary `DataSourceResponse`, with `status.runningJobId` refreshed to
  the RUNNING job's id when `currentJob` below is RUNNING (and null when it is PENDING — the same
  RUNNING-only field the data-sources list/get responses carry, kept consistent here rather than
  duplicated).
- **`cursors`** — every persisted `sync_cursors` row (`SyncCursorsService.getAll`), one
  `SyncCursorSummary{stream, watermarkAt, position, lastCompletedAt}` per row. `position` is the
  stream's own raw cursor JSON verbatim, never reparsed (each stream owns its own cursor shape — see
  "Cursor shapes" above). A stream with nothing left to resume (REFERENCE/RECONCILE's cursor cleared
  at pass end) simply has no row, so it's absent from this list, not present with a null `position`.
  PROCESS is always absent here, since it never writes a `sync_cursors` row at all (see "Sync
  cursors (V9)" above).
- **`counts`** (`SyncCounts`, `ingest/SyncStatusRoutes.kt`'s `counts` helper, backed by
  `JiraRawStore`) — `rawIssues` (total `raw.jira_issues` rows), `tombstonedDeleted`/
  `tombstonedMovedOut` (the two tombstone kinds, counted separately — `moved_out_at` is set by RECONCILE
  and by the ISSUES stream's out-of-scope step), `changelogs`/`worklogs`
  (worklogs excludes tombstoned rows — "live" worklogs), `entitiesByKind` (per `JiraEntityKind`,
  `raw.jira_entities` grouped by `kind`), `needsProcessing` (the PROCESS backlog RECONCILE and the
  CHANGELOGS/WORKLOGS streams all flag into). For a GITLAB connection these Jira integers are 0 and
  **`counts.gitlab`** (`GitLabSyncCounts`, read from `GitLabRawStore`, V41/V42) carries `projects` (live), `projectsMovedOut`,
  `projectsDeleted`, `users` (live), `usersDeleted` and the commits: `commits` (live, merges included), `commitsMerge` (live, `parent_count > 1`),
  `commitsDeleted` (tombstoned) and `commitFilesPending` (the COMMIT FILES backlog: diff unread, not a merge, not tombstoned). All are database-side
  `count(*)`s (`GitLabRawReads.counts`); it is null when this process has no GitLab store (GitLab off here).
- **`lastJobs`** — the most recently REQUESTED job of each `SyncJobKind`, keyed by name
  (`SyncJobsService.lastJobsByKind`) — terminal or not, and a kind never requested is simply absent
  from the map (not present with a null value).
- **`currentJob`** — the connection's open job in full, if any (`SyncJobsService.openJob`): the
  RUNNING job, else the PENDING one `claim` would take first (`priority`, `requested_at`, then id, in
  one query — so a manual job outranks an earlier scheduled one; just requested, or released back to
  the queue), so a client sees and polls a job that has not been claimed yet; null when nothing is open. This is where `progress`/`currentStream` above surface to an operator (a PENDING
  job has none yet).

## Connector kinds

A second connector kind (GitLab, `.claude/docs/gitlab-integration.md`) shares the table, queue and worker; this is the seam that keeps it
out of every Jira-only path (V40 + `ingest/`, pinned by `ConnectorSeamTest` and `KindLeakTest`).

- **`DataSourceKind(derivesMetrics)`** (`DataSource.kt`): `JIRA_CLOUD(true)`, `GITLAB(false)`. `derivesMetrics` is the ONE flag a
  metrics-only path gates on; `Connections.derivesMetrics()` / `Connections.isJira()` (`DataSourceKinds.kt`) are the SQL predicates, and
  the V40 CHECK (`source_connections_kind_check`, V8's unnamed inline CHECK) admits both kinds.
- **The connector registry** (`ConnectorRegistry.kt`): `Application.connectorRegistry()` creates it on first use (the
  `jobHandlerRegistry()` shape); each connector module calls `register(connector)` from its `configureXxx` (`configureJira` registers
  `JIRA_CLOUD`; `configureGitLab` follows it in `application.yaml` and registers `GITLAB` only when GitLab is enabled: a host listed, or the
  development stub). Additive: registering a kind twice is a boot error, nothing is
  replaced. `connectorFor(kind)` / `connectors()` (a snapshot) read it; `configureIngestWorker` builds the worker from `connectors()` at
  `ApplicationStarted` (the `IngestConnectorOverrideKey` test seam wins). **A kind with no registered connector** (GitLab switched off, not
  built here) fails its job with `ConnectorUnavailableException` -> job error code `CONNECTOR_UNAVAILABLE`, one of the closed
  `last_sync_error_code` vocabulary; the worker loop carries on, and a PURGE of such a connection fails too instead of stamping it purged
  with its raw rows in place. **The scheduler (`IngestWorker.enqueueDue`: scheduled SYNC, RECONCILE and PURGE) skips connections whose
  kind has no registered connector** (`DueConnection.kind`), because a failed job is terminal and would otherwise be re-enqueued every
  tick; only a MANUAL job (Sync now) is still queued, and fails with `CONNECTOR_UNAVAILABLE`. Such a connection's PURGE waits until its
  connector is registered again.
- **`Connector.testConnection(target: ConnectionTarget)`**: `ConnectionTarget` is a sealed type (`Connector.kt`), `Jira(siteUrl, email,
  apiToken, projectKeys, authScheme)` and `GitLab(baseUrl, token, groupPaths)`; both mask the secret in `toString`. A connector answers
  its own case only (`JiraConnector` `check`s: an internal error, never reachable from a route).
- **Settings by kind** (`DataSourceSettings.kt`): `ConnectionSettings` is a sealed interface with `JiraConnectionSettings` (stored JSON
  unchanged since V8, no discriminator) and `GitLabConnectionSettings(baseUrl, groupPaths, pathExclusions?)` (null = follow `DEFAULT_PATH_EXCLUSIONS`; `effectivePathExclusions(stored)` is the one read rule).
  `GET /api/v1/data-sources/kinds` carries `defaultPathExclusions` per kind (GitLab: the 14 built-ins, Jira: empty).
  `decodeSettings(kind, raw)` picks the shape from the row's `kind`; `ResultRow.jiraSettings()` is the Jira-only read and throws for any
  other kind, so a missed gate fails loudly instead of reading GitLab JSON as Jira.
- **Kind-leak guards** (a non-metrics / non-Jira row never reaches these): the scope rules (`DataSourceScope.kt`) read Jira rows only;
  `IngestWorker.onSucceeded` chains DERIVE only when `claim.connectorKind.derivesMetrics`, and a DERIVE job for another kind fails in
  `runJob` (backstop); `enabledActiveConnections()` (the `bumpRevision` fan-out) lists metrics-deriving connections only; the report
  scopes (`resolveConnectionScope`, `/reports/filters`, Home's connections, and the report ETag stamp) list `derivesMetrics`
  connections only, and an explicit `connectionId` of another kind is the same `400` as an unknown one; the metrics-config routes answer
  `409 data-source-kind-unsupported` (`DataSourceKindUnsupportedException`, type `urn:flow:problem:data-source-kind-unsupported`) after
  the admin guard, and so does the raw-issue inspector (`requireMetricsConnection` / `requireJiraConnection`). The data profile route
  decodes no settings and serves a GITLAB row's stored (empty) profile: it becomes per-kind with the code layer.
- **The data-source API serves both kinds** (PR 6): `DataSourceResponse` carries `jira` or `gitlab` (the other null) and `list`/`read`/
  `update`/`readForTest` are kind-agnostic (`active()` only, branching on the row's kind); `persistCloudId` and `readForSync` stay
  Jira-only (`readGitLabForSync` is GitLab's). `delete`, the scheduler scans (`dueForSync`/`dueForReconcile`/`dueForPurge`) and
  `connectionHealth` are kind-agnostic on purpose: every kind syncs, reconciles and purges through its own connector. The backfill floor
  (history horizon) applies to GitLab sources too (it is the COMMITS stream's first-run floor, PR 7). The sync-job and status routes are
  kind-agnostic (a GitLab source accepts REPROCESS and RECONCILE requests, which are no-op successes until PR 9/10; the SPA does not offer
  them). `uq_source_connections_name_active` spans kinds. With GitLab switched off after sources exist, list/read/delete keep working,
  a PUT is `400 gitlab-disabled`, the stored Test connection `409 data-source-kind-unsupported`, and scheduled jobs are skipped (above).

## Data sources

`source_connections` (V8, `ingest/DataSourceService.kt`) is the generic connector registry: `kind`
(`JIRA_CLOUD`, `GITLAB` since V40 - "Connector kinds"), typed common columns (schedule, sync status, `config_revision`) and a
`settings` jsonb holding the per-kind shape (Jira's: `siteUrl`, `email`, `projectKeys`, `authScheme`,
`cloudId`; GitLab's: `baseUrl`, `groupPaths`, `pathExclusions?` - null = the built-in defaults, on the wire since PR 7, applied by PROCESS in PR 10) — the GitLab connector reuses this table, its
queue and its worker outright. The per-kind half of a write (which block a body carries, the settings it stores, the PUT merge with the
identity/kind 409s, the ad-hoc test target) is `DataSourceKindWrites.kt`; the GitLab wire and validation is `GitLabDataSource.kt`. The service keeps the
table, the CRUD and the PUT/scope locking; the rest sits beside it as verbatim moves taking an explicit `database`/`cipher`, the service
delegating by the same public names: `DataSourceReads.kt` (row -> response/status/decrypted `StoredJiraConnection`), `DataSourceSchedule.kt`
(the due scans), `DataSourceOutcomes.kt` (the job-outcome writes); already beside it: `DataSourceScope.kt` (the scope rules), `ScheduleBackoff.kt` (the back-off). It
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
on `name`, and two **scope rules** sit on top (`ingest/DataSourceScope.kt`, domain-model invariant 13): **every
active data source has the same `jira.siteUrl`** (a create naming another site is `409`, problem `type`
`urn:flow:problem:data-source-other-site`) and **no project key sits in two active data sources** (a create or
update whose `projectKeys` overlap another active source is `409`, `urn:flow:problem:data-source-project-overlap`).
Both problems carry the conflicting source's path as `instance` and name it (and the shared keys) in `detail`; the
lowest-id conflict is reported; an update checks keys only, the site being the immutable identity. A soft-deleted
source holds nothing (its site and keys are free). **GitLab sources have rules of their own** (`GitLabScope.kt`, GitLab rows only, never
mixed with Jira's): **every active GitLab source has the same canonical `gitlab.baseUrl`** (a create naming another origin is `409`
`urn:flow:problem:data-source-other-instance`) and **no group path equals, sits under or contains another active GitLab source's**
(`urn:flow:problem:data-source-group-overlap`, create and update; `detail` names the source and the clashing paths). Within one request a
path equal to or nested in another is a `400`. The request body carries exactly one of `jira`/`gitlab` (else `400`), which sets the kind
(immutable: the other block on a PUT is `409`, as is a different canonical `baseUrl`). `gitlab.baseUrl` is canonicalised and checked by the
operator's allow-list through the `GitLabBaseUrlPolicy` seam (`JobHandlers.kt`; `configureGitLab` registers it ALWAYS, so a disabled
GitLab answers `400 gitlab-disabled`; a host off the list `400 gitlab-host-not-allowed`; the detail carries the code, never the URL);
`gitlab.token` is write-only like `jira.apiToken` (`hasToken` in responses, `data_source.token_rotated` on a rotation).
`GET /api/v1/data-sources/kinds` (ADMIN, `{items:[{kind, enabled, allowedOrigins}]}`) is what the editor reads to offer only kinds that can
work: Jira always; GitLab when a host is listed or the dev stub is set, with the origins the validator accepts. `create` and `update` take the transaction-scoped advisory lock
`DATA_SOURCE_SCOPE_LOCK_KEY` FIRST (`.claude/docs/persistence.md`), so two concurrent writes cannot both pass the
check; a rejection audits `data_source.scope_rejected`. Data that predates the rules is left alone. **The off-switch**
is `INGEST_SCOPE_RULES=off` (`ingest.scopeRules`, default `enforced`): e2e-only (the isolated stack's specs create
sources on their own sites with overlapping keys; `e2e/global-setup.ts`, `e2e.yml`, `docker-compose.yaml`), and
REFUSED at boot outside development mode, as is any value but `enforced`/`off` (`configuredScopeRules`, called first
in `configureDatabase`; pinned by `DataSourceScopeBootTest`). The server tests build their direct fixture service with
`ScopeRules.OFF` (`SyncedStubFixture.dataSources()`).
The response's `status.state` (`NEVER_SYNCED`/`CURRENT`/`STALE`/`FAILED`/`DISABLED`) is derived
from the sync-status columns; `runningJobId` names the connection's RUNNING sync job, if any (see
"Sync-job queue (V9)"). Test connection lives at `POST /api/v1/data-sources/test` and
`…/{id}/test` (see `.claude/docs/jira-integration.md`), the job API at `…/{id}/sync-jobs`, the
diagnostic status view at `…/{id}/status` (see "Sync status endpoint" above), the raw issue
inspector at `…/{id}/raw-issues/{issueKey}` (see "Raw issue inspector" below) and the data profile
at `…/{id}/profile` (see "Data profile" below).

**Narrowing a connection's scope.** What an admin edit of `projectKeys`/`backfillFrom` does to data
already stored (nothing is ever deleted; the raw/norm rows stay until a PURGE):

- **Removing a project from `projectKeys`** — at the start of the next SYNC's ISSUES stream every
  stored issue of that project is tombstoned locally as moved out (`moved_out_at`, no HTTP call;
  `JiraRawStore.markOutOfScopeProjects`, "RECONCILE stream" → "Out-of-scope projects" — by project id,
  skipped with a WARN if a configured key no longer resolves to a live project), and PROCESS mirrors
  it onto `norm`. RECONCILE never probes those rows. Adding the project back resurrects them through
  the next SYNC's scope catch-up: the re-added project counts as ADDED and is searched again from
  `backfillFrom` ("Streams" → "Covered scope and the catch-up clause").
- **Adding a project to `projectKeys`** — the next SYNC's ISSUES run downloads it from `backfillFrom` in
  the same query that keeps the other projects incremental; RECONCILE's index-gap phase waits for that
  run to complete instead of fetching the new project's issues one by one.
- **Moving `backfillFrom` LATER** — the data already downloaded stays, but RECONCILE's sweep window
  (`backfillFrom` → now) shrinks with it: stored issues last updated before the new `backfillFrom`
  are kept and NOT re-checked, so their Jira deletions/moves are not detected (the covered scope
  shrinks to the new date). Moving it EARLIER widens the window and the next SYNC catches up: the
  ISSUES stream re-reads the whole retained range from the new date once (bulk, paged; already-stored
  issues come back unchanged and are cheap, only the missing history is new), and RECONCILE's
  index-gap phase is skipped until that run completes. Clearing the field on edit resets it to the
  default (below), which may therefore re-read as well.

**The backfill default and floor (A48, `.claude/docs/domain-model.md`).** `backfillFrom` is bounded below by the reporting history
horizon (`metrics.settings.history_horizon_months`, `.claude/docs/metrics.md` "Configuration model"), applied at WRITE time only - never
a sliding window, because the stored date is a running backfill's identity (`runsCurrentScope`, `freshRun`) and an "effective" date
moving daily would discard every interrupted run at midnight. `ingest/DataSource.kt`'s `BackfillPolicy(defaultFrom, floor)`:
`floor` = the later of the horizon's first day and the legacy structural floor (10 years back, never before 2000-01-01);
`defaultFrom` = today minus 24 months (`DEFAULT_BACKFILL_MONTHS`) RAISED to the floor when the floor is later (a 9-month horizon: the
horizon's first day; a 36- or 120-month horizon or none: 24 months back) - the horizon only floors, it never widens the default, so a
blank date on a new source or a full-replace PUT cannot silently start a multi-year backfill. `validateDataSource` keeps only the structural rules (ISO shape, not in the future, the legacy floor); `DataSourceService`
enforces the horizon floor because it reads another feature's setting and, on update, the stored date: **create** refuses a date before
the floor (`400`, "backfillFrom must not be before <floor> (the history horizon)"), **update** checks it ONLY when the date changes
(an unchanged stored date older than the floor stays legal, otherwise every edit of a source older than one day would 400 - the stored
default is yesterday's today-minus-24-months), and a blank date becomes `defaultFrom` in the route's `sanitizedDataSourceRequest` (a blank on a PUT therefore RESETS the date to
the default, which always passes the floor). The route reads `backfillPolicy()` ONCE per request and passes that policy to
`create`/`update`, so the default and the floor cannot come from two horizons across a midnight (a direct service caller omits it and
the service reads it).
The floor reaches `ingest/` through the registry seam (`ingest/JobHandlers.kt`'s `BackfillFloorSource`, read by
`DataSourceService.backfillPolicy()`; `metrics/MetricsJobHandlers.kt` registers it from `readWithCalendar()`, today taken in the metrics
time zone) - `ingest/` never imports `metrics/`. A `DataSourceService` built without a registry (`registry` defaults to an empty one;
`SyncedStubFixture.dataSources()` and every direct test construction) has no floor source and keeps the 24-month default and the
10-year floor. A horizon that SHRINKS changes nothing for an existing source (its stored window still rules SYNC/RECONCILE; the
reports hide the old rows); one that GROWS changes nothing automatically - the admin may then move `backfillFrom` earlier, which takes
the widened-backfill path above. The streams, `JiraIssuesCursor` and RECONCILE are untouched.

**`infra/db/Jsonb.kt` + `infra/json/CanonicalJson.kt`** (this commit's supporting infra, detailed
in `.claude/docs/persistence.md` "Data sources (V8)"): the repo-local `jsonb` column binding
`source_connections.settings` uses, and the canonical-JSON/sha256 helper the Jira raw store (V10)
will hash payloads with.

## Raw issue inspector

`GET /api/v1/data-sources/{id}/raw-issues/{issueKey}` (v0.2.0 plan §9/§12 item 8b,
`ingest/RawIssueInspectorRoutes.kt`'s `configureRawIssueInspectorRoutes`, `ingest/RawIssueInspection.kt`)
is ADMIN-only and read-only — a per-issue debugging view over rows every stream above already
writes, assembled here rather than duplicated:

- **Lookup.** `issueKey` an all-digits string ([`ISSUE_ID_PATTERN`]) is ALWAYS an id lookup — the
  stable Jira issue id, never an (impossible) numeric issue key — through `JiraRawStore.issueById`;
  anything else must match [`ISSUE_KEY_PATTERN`] (`^[A-Z][A-Z0-9_]{1,9}-[0-9]{1,10}$`, e.g.
  `ENG-123`) or is `400`, and is looked up by the CURRENT `issue_key` (`JiraRawStore.issueByKey`) —
  a project move rewrites this column (see "The Jira raw store (V10)",
  `.claude/docs/persistence.md`), so an old key from before a move 404s, not the new one.
- **Response shape.** The raw payload as stored - the TRIMMED document, canonicalized (no comment, description, attachment or embedded
  worklog copy, users reduced to their id, name and type: `persistence.md` "The Jira raw store (V10)") -, `fetchedAt`/
  `changedAt`/`sha256`, the tombstone columns (`deletedAt`/`movedOutAt`) and `needsProcessing` —
  straight off `raw.jira_issues` — plus every stored changelog history and every non-tombstoned
  worklog for the issue, oldest first (`JiraRawStore.changelogPayloadsForIssue`/
  `worklogPayloadsForIssue`). If the issue has been through PROCESS at least once, `workItem` (the
  `norm.work_items` current snapshot), `statusIntervals`, `fieldIntervals` and `anomalies` are
  populated too (`WorkItemStore.workItemView`/`statusIntervalsForIssue`/`fieldIntervalsForIssue`) —
  `workItem` (and the interval lists) stay empty/absent for an issue this connection has fetched but
  never yet processed.
- **A stored-size cap, and no tree (1.24.1).** BEFORE reading any of the issue's text the route sums its stored bytes in the database
  (`JiraRawStore.storedBytesForIssue`: the payload, the histories and the live worklogs, `octet_length` of the jsonb text; plus
  `WorkItemStore.customFieldsBytes`: the work item's `custom_fields` text, `norm/NormInspectorReads.kt`) and answers
  **413** `application/problem+json` with the constant detail "The stored issue is too large to inspect" when the raw sum is over
  `jira.processPageBytes` - the very figure PROCESS refuses an issue at, so the inspector never serves what PROCESS would not process
  (the web page shows `dataSources.inspect.tooLarge`) - or when the whole (raw + `custom_fields`) is over the web heap share over
  `INSPECTION_BYTES_PER_STORED_BYTE` (8): `capacityBytes / 8` = 14 MiB at the defaults, so an inspection always fits the share and there is no boot
  pin (no config that booted before refuses now). The route answers it itself (`respondProblem`): `ErrorHandling`'s
  `PayloadTooLargeException` mapping is the REQUEST-body text. An id is resolved first (`issueIdByKey` for a key), so a 404 still precedes
  the cap. Under the cap the stored text - the payload, the histories, the worklogs AND the work item's `customFields` - is embedded in the
  response AS TEXT (`JsonUnquotedLiteral`, Postgres's jsonb output - valid JSON by construction), never parsed, so the `web` role builds no
  tree of any document; the wire shape is the objects they always were (`RawIssueInspectorTest` pins the verbatim embedding, both 413s and
  the headers). At most TWO issues are read, built and serialized at once per process
  (a `Semaphore`, `MAX_CONCURRENT_INSPECTIONS`); further requests wait for a permit. Under the permit, an inspection is also admitted against the
  web role's Jira heap share (`jira/JiraHeapShare.kt`, `JiraHeapShareKey`: one FIFO weighted budget of the single response tree the web heap fit
  counts, 112 MiB at the defaults): it holds `INSPECTION_BYTES_PER_STORED_BYTE` (8) x the issue's stored bytes from the first read until the
  response is ENCODED to a `ByteArray` (`Json.encodeToStream` with Ktor's own `DefaultJson`, so the wire bytes and `Content-Type` match
  ContentNegotiation's); the share is released BEFORE the bytes are written (`respondBytes`), so a slow-reading client pins no share (only a
  semaphore permit). It waits (FIFO) when the share is taken, so the inspector is inside the web heap fit (`jira-integration.md` "The boot-time heap fit
  check"; `RawIssueInspectorTest` holds the share in full and sees the request wait, then answer byte-identically). `ingest/` reads the key from
  `jira/` like it already reads `JiraRawStoreKey`. A Test connection (`DataSourceRoutes.kt`) holds the WHOLE share around its connector call, so
  an inspection and a Test connection never run together (`DataSourceTestConnectionTest`; the wait it can cost: `jira-integration.md` "Test connection").
- **A tombstoned issue is still returned, never `404`.** `deletedAt`/`movedOutAt` being set is
  itself part of the answer a debugging view exists to show; only a genuinely unknown id/key 404s.

## Data profile

`GET /api/v1/data-sources/{id}/profile` (v0.2.0 plan §8/§9/§12 item 9,
`ingest/DataProfileRoutes.kt`'s `configureDataProfileRoutes`, `ingest/DataProfile.kt`) is ADMIN-only
and read-only — SQL aggregates over a connection's `raw.*`/`norm.*` rows, computed by the PROFILE
step (`jira/JiraProfileStream.kt`) after every successful SYNC/REPROCESS PROCESS pass (never
RECONCILE — see "Sync-job queue (V9)" above) and stored verbatim on
`source_connections.profile`/`profile_at` (`ingest/DataSourceService.kt`'s `updateProfile` (body in `DataSourceOutcomes.kt`) /
`readProfile`, `.claude/docs/persistence.md` "Data sources (V8)"). `computedAt` is `null` before the
connection's first successful PROCESS pass — every section then carries its own empty default
rather than the endpoint `404`ing or omitting fields (`DataProfileSections`/`withComputedAt`,
`ingest/DataProfile.kt`). **No paging, no resume, unlike every other stream**: a PROFILE that
crashes mid-run simply leaves the connection's PREVIOUS profile in place (or `null`, before a first
success) — the next successful SYNC/REPROCESS recomputes it wholesale, exactly like PROCESS's own
reference-row rebuild. `JiraProfile.compute` (`jira/JiraProfile.kt`) is pure aggregation over
already-normalized/already-raw data — it makes no outbound Jira call of its own.
The custom-field fill rate is counted in Postgres (`JiraRawStore.customFieldFill`, `jsonb_each` over `raw.jira_issues.payload`), so no
issue payload enters the JVM heap and PROFILE's memory is bounded by the number of distinct field keys, not the project size (the
first real-site SYNC — 2,105 issues, 59 MB of JSON — OOMed at `-Xmx256m` before this).

**What each section measures (facts only — interpretation, e.g. which statuses count as "active"
vs. "waiting", is a Phase 3 concern, see "Facts only — no interpretation" under "Normalized layer"
above):**

- **`range`** — the connection's live (non-tombstoned) work items' earliest `created_at` and latest
  `updated_at`.
- **`projects`** — issue counts by issue type, one entry per project key.
- **`workflows`** — per (project, issue type): every status id ACTUALLY OBSERVED in that
  project/type's own tiled status intervals (name, category, and a transition count — how many
  `CHANGE`-sourced intervals landed on that status), alongside `referenceStatusNames` — that
  project's OWN configured workflow for that issue type, read straight off the REFERENCE stream's
  `PROJECT_STATUSES` entity payload (`GET /project/{key}/statuses`, `JiraRawStore.entityRowsByKind`,
  parsed by `JiraProfile.parseProjectStatuses`) — a status name present in `referenceStatusNames` but
  absent from `observedStatuses` is a status this project's issues have simply never visited yet;
  the reverse (observed but not in the reference list) would flag a REFERENCE/ISSUES mismatch worth
  a second look.
- **`boards`** — one entry per `norm.boards`/`norm.board_columns` row (already rebuilt by PROCESS
  from the REFERENCE stream's `BOARD`/`BOARD_CONFIGURATION` entities, see "Normalized layer" above):
  each column's mapped status names, plus `unmappedStatusNames` — statuses observed on the board's
  OWN project's issues that map to no column on this board at all (a real-tenant board almost always
  has some: a resolution/workflow status the board's filter hides, or a status added to the
  project's workflow after the board's column mapping was last edited).
- **`customFields`** — every field REFERENCE's `FIELD` entity marks `custom: true`: its name, its
  Jira `schema.type`, a fill rate (the fraction of live issues whose `fields[fieldId]` is non-null
  and, for an array-shaped field like Sprint/Flagged, non-empty) and a detected `role`
  (`SPRINT`/`RANK`/`TEAM`/`STORY_POINTS`/`FLAGGED`/`OTHER`) — the SAME schema-then-name discovery
  `JiraNormalizer.discoverFieldIds` already runs for tiling (see "Custom-field discovery by schema"
  under "Normalized layer" above), reused here rather than re-implemented.
- **`estimates`** — story-point and original-estimate coverage: how many live issues carry each,
  and the resulting fill percentage.
- **`worklogs`** — total worklog count and hours logged, how many DISTINCT issues carry at least one
  worklog (and its percentage of live issues), and how many distinct worklog authors this connection
  has ever seen.
- **`reopens`** — how many live issues have at least one DONE→non-DONE status transition
  (`Normalization.reopenCount`, the SAME pure check `JiraSyncPipelineTest` asserts against — see
  "sample-data/README.md"'s `reopens.inScopeCount`, the in-scope-reachable figure a real profile can
  actually match, as opposed to `reopens.count`'s whole-dataset total).
- **`sprints`** — every `norm.sprints` reference row's own state (`stateCounts`), how many live issues
  carry a Sprint field value at all, and `carryOverCount`/`carryOverPercent` — issues whose SPRINT
  field interval history names **two or more distinct sprint ids** over the issue's lifetime (moved
  from one sprint into the next without closing).
- **`people`** — how many distinct accounts are currently assigned to a live issue, and what
  percentage of live issues are unassigned.
- **`workflowStatusIds`** — the sorted, distinct union of status ids across every `PROJECT_STATUSES` entity
  (every in-scope project's reference workflow, all issue types), parsed by the same `parseProjectStatuses` that feeds
  `referenceStatusNames`. The metrics-config options endpoint turns it into each status's `inWorkflow` flag (the
  Statuses tab's default filter, `.claude/docs/metrics.md`); a profile stored before the field existed decodes it as
  empty and refreshes on the next PROFILE run.
- **`epicWorkflowStatusIds` / `taskWorkflowStatusIds`** — the same union split by the issue type each `PROJECT_STATUSES` entry
  belongs to (its `id`, mapped to a hierarchy level through the stored `ISSUE_TYPE` entities, `hierarchyBucket` in `norm/Hierarchy.kt`): level 1
  is an epic type; level 0 and -1 (sub-task) are task types; an entry of unknown level counts as a task type, a level above 1
  counts for neither. Both are EMPTY while no `ISSUE_TYPE` entity exists (and on a profile stored before they existed) —
  empty means "unknown", never "no statuses"; `workflowStatusIds` stays the union. On the real tenant epics run their own
  workflow. The options endpoint turns them into `inEpicWorkflow`/`inTaskWorkflow` (`.claude/docs/metrics.md`).
- **`schemeFieldIds`** — the sorted, distinct union of field ids across the live `PROJECT_FIELDS` entities of the
  connection's CURRENT project keys (the optional, experimental `projects/fields` REFERENCE step — it is skipped, keeping
  the previous entity, on any Jira-side failure but a blocked host: `.claude/docs/jira-integration.md` "Project field
  schemes"); `null` = unknown unless EVERY current project has an entity. The options endpoint turns it into each custom field's
  `inScheme` (the Fields tab's default filter, `.claude/docs/metrics.md`). `JiraProfile.compute` takes the project keys from
  `DataSourceService.read` in `JiraProfileStream`.
- **`schemeEpicFieldIds` / `schemeTaskFieldIds`** — the same union restricted to the work types at hierarchy level 1 / level 0
  and below (the split stored in each `PROJECT_FIELDS` payload, `jira-integration.md` "Project field schemes"); a project whose
  entity predates the split contributes its whole scheme to both; `null` under the `schemeFieldIds` rule (unknown unless EVERY
  current project has an entity). The options endpoint turns them into `inEpicScheme`/`inTaskScheme`.
- **`anomalyCounts`** — every `TilingAnomaly` code (`STATUS_CHANGE_BEFORE_CREATED`/
  `STATUS_CHAIN_BROKEN`/`STATUS_MISMATCH_WITH_CURRENT`, see "Anomalies are flagged, never corrected"
  under "Normalized layer" above), counted across every live issue that carries it — never per-status
  or per-project, since an anomaly is a fact about ONE issue's own tiling, not a workflow-wide one.

## Reading the data profile after the first real sync

The phase-2 exit criterion for a real Jira Cloud tenant: create the connection with the
service-account token, confirm it can actually reach the tenant, run a first sync, then read what
Flow learned about the tenant's own data shape.

1. **Create the connection** — Data sources → New (see the README's "Connecting Jira" section for
   what the admin needs before starting: the service account, its scoped read-only API token, the
   site URL, the project keys in scope).
2. **Test connection** — confirms auth, scopes and reachability against every probe in
   `.claude/docs/jira-integration.md`'s table BEFORE committing to a full sync; a required probe
   failing here means the sync itself would fail the same way.
3. **Sync now** — enqueues a manual (`priority = 0`) SYNC job; watch it via
   `GET …/{id}/status` (or the Data sources page) until `currentJob` is null and the connection's
   `status.state` reads `CURRENT`.
4. **Open the profile** (`GET …/{id}/profile`, or the Data sources page's own profile view) once the
   job is `SUCCEEDED`, and look at:
   - **Workflows and statuses per project** (`workflows`) — does every project's `referenceStatusNames`
     roughly match what `observedStatuses` actually shows across its issues? A status that never
     shows up as observed is one this project's real backlog hasn't exercised yet — expected on a
     young project, worth a second look on an old one.
   - **Unmapped board statuses** (`boards[].unmappedStatusNames`) — a non-empty list here means the
     board's column mapping is out of date with the project's own workflow; Phase 3's active/waiting
     stage grouping will need to know about these statuses from SOMEWHERE if they matter.
   - **Custom fields in use and their detected roles** (`customFields`) — confirm Sprint/Rank/Team/
     Story-points/Flagged were actually discovered (`role` other than `OTHER`) rather than silently
     falling through un-mapped; a real tenant's own `customfield_NNNNN` ids are tenant-specific, so
     this is the first real confirmation the discovery-by-schema logic found the right fields.
   - **Worklog/estimate coverage** (`worklogs`, `estimates`) — low coverage here is a real signal
     about how this specific team uses Jira (skips estimation, doesn't log time), not a bug; Phase 3's
     flow-efficiency math will need to account for whichever gaps show up.
   - **Reopen rate** (`reopens`) — how often a DONE issue comes back; a data point Phase 3's
     bottleneck diagnosis will want as a baseline.
   - **Anomalies** (`anomalyCounts`) — any non-zero count here is worth opening the raw issue
     inspector for one of the affected issues (`GET …/{id}/raw-issues/{issueKey}`) to see the actual
     changelog chain that produced it, before assuming it is noise.

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
sync could actually produce — i.e. one computed over the four in-scope projects alone.
`sample-data/jira/generate.mjs` computes both kinds and names them accordingly:
`changelog.inScopeHistories` and `worklogs.inScopeCount`/`inScopeIssueCount` are the in-scope-reachable
totals a single backfill SYNC actually stores (every in-scope issue's own changelog/worklog history,
`worklogs.inScopeCount`/`inScopeIssueCount` additionally filtered to the `REFERENCE_MS` backfill
cutoff so day2's later additions are correctly excluded) — these are what `JiraSyncPipelineTest`
asserts against. The whole-dataset figures (all five projects, including the out-of-scope `SEC`
project's own changelog/worklog rows — a number no correct sync can ever reach, since `SEC` issues
are never fetched via `search/jql` and so never become a `raw.jira_issues` row) are named
`changelog.allProjectsHistories` and `worklogs.allProjectsIssueCount`/`allProjectsTotalCount` (the
latter two ALSO fold in the day2 scenario's own in-scope worklog additions, unlike every other
`allProjects*` figure) — a test must never compare a raw-store count against one of these.

The Jira HTTP client, the outbound guard and the REFERENCE/ISSUES/CHANGELOGS/WORKLOGS/RECONCILE
streams all read this stub (`docker-compose.yaml`'s `app` service's `JIRA_STUB_BASE_URL` points at
it); PROCESS reads no Jira data of its own (it only reads back `raw.*` rows the other streams
already stored, see "Normalized layer" above) — and neither does PROFILE, which aggregates
the stored `raw.*`/`norm.*` rows (see "Data profile" above).

## GitLab stub

The GitLab connector (`.claude/docs/gitlab-integration.md`, 1.34.0 onwards) has the same kind of fixture, built first so the
connector PRs code against a fixed contract: a deterministic synthetic **self-managed GitLab** world (2 groups + a subgroup, 4
in-scope projects, 1 archived, 1 out of scope, ~300 default-branch commits incl. merge commits, ~60 merge requests with notes,
approvals and reviewer states, one commit of 1,040 files, a `too_large` and a `collapsed` file) generated by
`sample-data/gitlab/generate.mjs` into `sample-data/gitlab-stub/`, with the figures in `sample-data/gitlab/expected.json`.
The same mappings are served by the compose `gitlab-stub` (`127.0.0.1:8095`) and, in tests, the in-JVM
`GitLabStubServer` (`server/src/test/kotlin/GitLabStubServer.kt`, `JiraStubServer`'s twin). Its `gitlab-day2` WireMock scenario
(`PUT /__admin/scenarios/gitlab-day2/state`) is the GitLab counterpart of `jira-day2`: a force-push, a default branch renamed
`master` to `main`, a deleted merge request, a project moved out of the configured groups, a new project, and new commits. The
Jira stub's people are the identity anchors: most GitLab users' emails equal the stub's `user<n>@flow-sample.example`
(`expected.json` `users.jiraAlignment`). Endpoint contract, scale flags and the day-2 deltas: `sample-data/README.md` "GitLab stub".
`SyncedGitLabFixture` (PR 7: one SYNC of it per fork, warmed through `warmGitLabFixture()`; `test-fixtures.md`) is read by `GitLabCommitsStreamTest`;
`GitLabStubServerTest` pins the contract over plain HTTP.
