package ch.nokillswit.ingest

import ch.nokillswit.audit.audit
import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.config.requireConfigLong
import ch.nokillswit.infra.time.MILLIS_PER_DAY
import ch.nokillswit.plugins.runsWorker
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.config.ApplicationConfig
import io.ktor.util.AttributeKey
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Test seam for the connector [IngestWorker] dispatches jobs to — `IngestWorkerTest`'s fake
 * connector. Defaults to `jira/Jira.kt`'s real registry.
 */
val IngestConnectorOverrideKey = AttributeKey<Map<DataSourceKind, Connector>>("IngestConnectorOverride")

/**
 * Test seam for the worker's clock (`IngestWorkerTest`) — set BEFORE `startApplication()` so it
 * is visible when [ApplicationStarted] fires.
 */
val IngestClockKey = AttributeKey<() -> Long>("IngestClock")

/** Published once the scan loop has actually started — `RoleTest`'s "worker started" seam. */
val IngestWorkerStartedKey = AttributeKey<Boolean>("IngestWorkerStarted")

private const val SHUTDOWN_JOIN_TIMEOUT_MS = 5_000L
private const val MIN_HEARTBEAT_INTERVAL_MS = 1_000L
/** Floor of the heartbeat slack — only a test lease below 5 s ever reaches it (a configured lease is at least 30 s, so slack >= 3 s). */
private const val MIN_HEARTBEAT_SLACK_MS = 500L

data class IngestConfig(
    val schedulerTickSeconds: Long,
    val workerSlots: Int,
    val leaseSeconds: Long,
    val jobRetentionDays: Long,
    val purgeGraceDays: Long,
    val workerId: String,
)

private fun readIngestConfig(config: ApplicationConfig): IngestConfig {
    val tick = requireConfigLong(config, "ingest.schedulerTickSeconds", min = 5, max = 300)
    val slots = requireConfigInt(config, "ingest.workerSlots", min = 1, max = 16)
    val lease = requireConfigLong(config, "ingest.leaseSeconds", min = 30, max = 3600)
    val retention = requireConfigLong(config, "ingest.jobRetentionDays", min = 1, max = 3650)
    val grace = requireConfigLong(config, "ingest.purgeGraceDays", min = 0, max = 90)
    val configuredWorkerId = config.propertyOrNull("ingest.workerId")?.getString()?.trim().orEmpty()
    return IngestConfig(tick, slots, lease, retention, grace, configuredWorkerId.ifBlank { defaultWorkerId() })
}

/**
 * A running DERIVE holds TWO pooled connections at once — its own big transaction plus the short
 * `MetricsStore.ensureDimDate` transaction opened from inside it — so `workerSlots` concurrent derives
 * can pin `2 × workerSlots` connections. At exactly that many the derives still fit, but nothing is left
 * for requests, job heartbeats or the claim scan, so the pool must be strictly larger. Fail-closed at
 * boot, only for a role that runs the worker (`.claude/docs/persistence.md` "Connection pool").
 *
 * Abandoned lease renewals are deliberately NOT added to this bound: an attempt the ticker gave up on (at its slack) is
 * bounded server-side by about a slack too, and attempts are spaced by `retry = max(1s, interval/2) > slack`, so at most
 * one abandoned renewal per slot is outstanding at a time. If a stall ever eats the headroom, the next renewal's
 * pool-acquire fails or times out, which the lease budget counts as a failed renewal — the job stops before its lease
 * runs out, the fail-closed outcome. Raise `postgres.pool.maxSize` rather than the formula.
 */
private fun requirePoolFitsWorkerSlots(config: ApplicationConfig, workerSlots: Int) {
    val poolMaxSize = requireConfigInt(config, "postgres.pool.maxSize", min = 1, max = 1000)
    check(poolMaxSize > 2 * workerSlots) {
        "postgres.pool.maxSize ($poolMaxSize) must be greater than 2 x ingest.workerSlots ($workerSlots): a running DERIVE " +
            "holds two pooled connections at once (its transaction plus the dim_date ensure), leaving no headroom for " +
            "requests and heartbeats"
    }
}

private fun defaultWorkerId(): String {
    val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("worker")
    return "$host-${UUID.randomUUID().toString().take(8)}"
}

/**
 * The `FLOW_ROLE=worker` scheduler (v0.2.0 plan §5, `.claude/docs/ingestion.md` "Worker
 * scheduler"): a no-op unless [ch.nokillswit.plugins.runsWorker] — `ingest.*` config is still
 * validated on EVERY boot (the `configureRole`/`jira/Jira.kt` fail-fast idiom: a bad worker
 * setting is a deploy-time config error regardless of which role reads it). Registered after
 * `configureJira` (the Connector registry) and before the feature route modules
 * (`application.yaml`).
 *
 * The scan loop itself starts on [ApplicationStarted] rather than synchronously in this function,
 * so a test's `application { attributes.put(IngestConnectorOverrideKey, fake) }`/[IngestClockKey]
 * override — which, like every user `application {}` block, runs AFTER this yaml module — is
 * already visible (the same ordering `plugins/Health.kt`'s `ReadinessProbeKey` seam relies on).
 */
fun Application.configureIngestWorker() {
    val config = readIngestConfig(environment.config)
    if (!runsWorker()) return
    requirePoolFitsWorkerSlots(environment.config, config.workerSlots)

    monitor.subscribe(ApplicationStarted) { app ->
        val connectors = app.attributes.getOrNull(IngestConnectorOverrideKey)
            ?: app.attributes.getOrNull(ConnectorRegistryKey)
            ?: emptyMap()
        val clock = app.attributes.getOrNull(IngestClockKey) ?: System::currentTimeMillis
        val handlers = app.jobHandlerRegistry()
        // Fail closed at boot, as the direct attribute reads this registry replaced did: a worker whose
        // DERIVE handler and revision source were never registered (configureMetrics missing from the module
        // list) would fail every DERIVE and, worse, run PURGE without its metrics drains.
        check(handlers.handlerFor(SyncJobKind.DERIVE) != null && handlers.configRevisionSource != null) {
            "configureIngestWorker: no DERIVE handler / config revision source registered — configureMetrics must run first"
        }
        val worker = IngestWorker(
            syncJobs = app.attributes[SyncJobsServiceKey],
            dataSources = app.attributes[DataSourceServiceKey],
            handlers = handlers,
            connectors = connectors,
            config = config,
            clock = clock,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        worker.start(scope)
        app.attributes.put(IngestWorkerStartedKey, true)
        app.monitor.subscribe(ApplicationStopping) {
            // Cancel, then join up to SHUTDOWN_JOIN_TIMEOUT_MS: each in-flight runJob's own
            // CancellationException handler releases its claim back to PENDING under
            // NonCancellable BEFORE it finishes cancelling (Covenant's ToadieRefreshCoordinator
            // shape) — so a bounded join here is enough for that release to actually land before
            // configureDatabase disposes the connection pool on ApplicationStopped.
            val rootJob = scope.coroutineContext[Job]
            scope.cancel()
            worker.close()
            if (rootJob != null) {
                runBlocking { withTimeoutOrNull(SHUTDOWN_JOIN_TIMEOUT_MS) { rootJob.join() } }
            }
        }
    }
}

/** True once the scan loop has actually started (`web` role or before `ApplicationStarted`: false). */
fun Application.ingestWorkerStarted(): Boolean = attributes.getOrNull(IngestWorkerStartedKey) ?: false

/** The lease was reclaimed by another worker (or the job left RUNNING) — the run stops without touching cursors. */
class LeaseLostException(jobId: UInt) : Exception("Lease lost for sync job $jobId")

/** `cancel_requested_at` was set against this RUNNING job — the run stops cooperatively. */
class JobCancelRequestedException(jobId: UInt) : Exception("Cancel requested for sync job $jobId")

/**
 * Claims and runs sync jobs: a scheduler tick (every [IngestConfig.schedulerTickSeconds]) enqueues
 * due SYNC/RECONCILE/PURGE jobs, then claims up to [IngestConfig.workerSlots] free jobs and runs
 * each under a ticker that heartbeats every `leaseSeconds/3` and honours `cancel_requested_at`
 * (see [heartbeatLoop] for how a failing heartbeat is budgeted against the lease).
 * [clock] is injectable for tests.
 */
class IngestWorker internal constructor(
    private val syncJobs: SyncJobsService,
    private val dataSources: DataSourceService,
    private val handlers: JobHandlerRegistry,
    private val connectors: Map<DataSourceKind, Connector>,
    private val config: IngestConfig,
    private val clock: () -> Long,
    /**
     * The ticker's lease renewal (heartbeat + cancel check) — a seam `IngestWorkerTest` replaces to make one attempt throw
     * or hang; reachable only through the `internal` constructor, production always uses [SyncJobsService.renewLease].
     */
    private val renewLease: suspend (claim: SyncJobClaim, now: Long, boundMillis: Long) -> HeartbeatOutcome,
    /** The monotonic source of the lease budget ([heartbeatLoop]); `IngestWorkerTest` passes a `TestTimeSource`. */
    private val timeSource: TimeSource,
) {
    constructor(
        syncJobs: SyncJobsService,
        dataSources: DataSourceService,
        handlers: JobHandlerRegistry,
        connectors: Map<DataSourceKind, Connector>,
        config: IngestConfig,
        clock: () -> Long,
    ) : this(
        syncJobs, dataSources, handlers, connectors, config, clock,
        { claim, now, bound -> syncJobs.renewLease(claim.id, config.workerId, claim.attempt, config.leaseSeconds, now, bound) },
        TimeSource.Monotonic,
    )

    private val slots = Semaphore(config.workerSlots)
    private val renewalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                try {
                    tick(scope)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    log.warn("Ingest scheduler tick failed", e)
                }
                delay(config.schedulerTickSeconds * 1000)
            }
        }
    }

    /** One tick: enqueue due jobs, prune old terminal rows, then claim+run up to the free slots. `internal` for `IngestWorkerTest`. */
    internal suspend fun tick(scope: CoroutineScope) {
        enqueueDue()
        syncJobs.prune(config.jobRetentionDays * MILLIS_PER_DAY, clock())
        claimAvailable(scope)
    }

    private suspend fun enqueueDue() {
        val now = clock()
        dataSources.dueForSync(now).forEach { syncJobs.enqueueScheduled(it.id, SyncJobKind.SYNC, it.configRevision, now) }
        dataSources.dueForReconcile(now).forEach { syncJobs.enqueueScheduled(it.id, SyncJobKind.RECONCILE, it.configRevision, now) }
        dataSources.dueForPurge(now, config.purgeGraceDays * MILLIS_PER_DAY)
            .forEach { syncJobs.enqueueScheduled(it.id, SyncJobKind.PURGE, it.configRevision, now) }
    }

    private suspend fun claimAvailable(scope: CoroutineScope) {
        repeat(config.workerSlots) {
            if (!slots.tryAcquire()) return@repeat
            // The budget origin: marked just BEFORE the clock() read the claim's lease_until is computed from, so it can only be early.
            val leaseStart = timeSource.markNow()
            val claim = syncJobs.claim(config.workerId, config.leaseSeconds, clock())
            if (claim == null) {
                slots.release()
                return@repeat
            }
            scope.launch {
                try {
                    runJob(claim, leaseStart)
                } finally {
                    slots.release()
                }
            }
        }
    }

    /**
     * `internal` so `IngestWorkerTest` can run a single claimed job deterministically without the tick loop's timing.
     * [leaseStart] is the budget origin of [heartbeatLoop]: the monotonic time taken just before the `clock()` read the
     * claim's `lease_until` was computed from (the default, for a caller that claimed just before, is "now").
     */
    internal suspend fun runJob(claim: SyncJobClaim, leaseStart: TimeMark = timeSource.markNow()) {
        if (claim.kind == SyncJobKind.SYNC) dataSources.recordSyncStarted(claim.connectionId, clock())
        audit(
            "sync_job.started",
            "jobId" to claim.id.toLong(),
            "dataSourceId" to claim.connectionId.toLong(),
            "kind" to claim.kind.name,
            "attempt" to claim.attempt,
            "workerId" to config.workerId,
        )
        var deriveRevisionUsed: Long? = null
        try {
            coroutineScope {
                val ticker = launch { heartbeatLoop(claim, leaseStart) }
                val context = SyncJobRunContext(claim, clock = clock) { progress, currentStream ->
                    syncJobs.heartbeat(claim.id, config.workerId, claim.attempt, config.leaseSeconds, clock(), progress, currentStream)
                }
                // DERIVE (v0.3.0 M3 commit 7, `.claude/docs/ingestion.md` "The DERIVE job kind") is
                // connector-agnostic — dispatched here BEFORE the connector registry, so it runs
                // regardless of which connector kind the connection is. Its handler (registered by
                // `metrics/MetricsJobHandlers.kt` through `JobHandlers.kt`, checkup D5 — `ingest/` never
                // imports `metrics/`) returns the `metrics.settings.config_revision` it read at its own
                // start — `onSucceeded` below compares it against the CURRENT revision, so a config
                // change that landed WHILE this run was in flight (and so coalesced into it rather than
                // getting its own job, see `uq_sync_jobs_open_per_kind`) is never silently lost (review
                // round 1 fix). A DERIVE job with no registered handler is an error, never a silent success.
                if (claim.kind == SyncJobKind.DERIVE) {
                    val handler = handlers.handlerFor(SyncJobKind.DERIVE)
                        ?: error("No handler registered for job kind DERIVE (sync job ${claim.id})")
                    deriveRevisionUsed = handler.run(context)
                } else {
                    connectors[claim.connectorKind]?.run(context)
                }
                // The generic, connector-agnostic PURGE steps (v0.3.0 M1 commit 4,
                // `.claude/docs/ingestion.md` "PURGE"): run AFTER the connector's own `purgeSteps`
                // (which drain its `raw.*`/`norm.*` rows), in registration order — `metrics/` registers
                // the per-connection `metrics.*` config drain, then the derived-star drain
                // (`MetricsStore.purgeAll`), so both are drained here rather than inside
                // `JiraConnector.purgeSteps` (review round 1 fix).
                if (claim.kind == SyncJobKind.PURGE) {
                    handlers.purgeSteps().forEach { it.purge(claim.connectionId) }
                }
                ticker.cancel()
            }
            onSucceeded(claim, deriveRevisionUsed)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (syncJobs.release(claim.id, config.workerId, claim.attempt)) {
                    audit(
                        "sync_job.released",
                        "jobId" to claim.id.toLong(),
                        "dataSourceId" to claim.connectionId.toLong(),
                        "workerId" to config.workerId,
                    )
                } else {
                    logStale(claim, "release")
                }
            }
            throw cancelled
        } catch (lost: LeaseLostException) {
            log.warn("Sync job {} lost its lease mid-run: {}", claim.id, lost.message)
        } catch (cancelledJob: JobCancelRequestedException) {
            if (!syncJobs.markCancelled(claim.id, claim.attempt, clock())) logStale(claim, "cancel")
            log.info("Sync job {} cancelled on request: {}", claim.id, cancelledJob.message)
        } catch (e: Exception) {
            onFailed(claim, e)
        }
    }

    /**
     * The ticker body: every `max(1s, leaseSeconds/3)` it renews the lease and checks `cancel_requested_at` in ONE call
     * ([renewLease], one pooled connection, one failure policy). [HeartbeatOutcome.LOST] is fatal at once
     * ([LeaseLostException]); [HeartbeatOutcome.CANCEL_REQUESTED] throws [JobCancelRequestedException].
     *
     * **A failing renewal (it throws, or hangs) is tolerated only while the renewal can still be stopped
     * strictly BEFORE `lease_until`** — a DERIVE must survive one pool hiccup, but the worker must not keep renewing
     * (and running) as if nothing were wrong until another claimer may take the row. With `L` the lease,
     * `slack = max(500 ms, L/10)` and `E` the monotonic time since [leaseStart] / the START of the last successful
     * renewal (taken before the call, like the `now` the lease is computed from, so it can only be early):
     *  - every attempt is bounded by `withTimeoutOrNull(slack)` on the client (a timeout counts as a failure) and, in the
     *    database, by `lock_timeout` + `queryTimeout` of about `slack` (`renewLease`'s `boundMillis`);
     *  - after a failure observed at `E`, the retry (after `retry = max(1s, interval/2)`) is tolerated only if
     *    `E + retry + 2·slack < L`. A tolerated retry therefore starts at `E + retry` and its failure is observed by
     *    `E + retry + slack < L - slack`: **renewal stops and the job's cancellation is REQUESTED at least `slack`
     *    before `lease_until`** (that `slack` is the allowance for the stop itself and for clock skew against the
     *    reclaimer, which compares `lease_until` with ITS wall clock). The first failure is observed by
     *    `interval + slack`, well inside that. Otherwise the failure is rethrown (`runJob` → `onFailed`) at once.
     *
     * What this does NOT promise: the lease bounds a run, it does not exclude a stale one. Cancelling the job scope
     * only requests the body to stop — a body inside a database statement unwinds when that statement returns (its
     * open transaction then rolls back), and a frozen worker (a long GC pause, a stopped container) does not unwind
     * at all. Every `sync_jobs` write of such a stale run is a no-op through the fences (`SyncJobsService`), but its
     * stream DATA writes are not fenced, so it can overlap a reclaimed run in that window (the streams are re-runnable by
     * design — cursors, per-scope replace — but nothing excludes the overlap).
     *
     * Which failure is the fatal one depends on how long the failures take: at the defaults (L = 300 s, 100 s interval,
     * 50 s retry, slack 30 s) the threshold is `E < 190 s`, so FAST failures (observed at 100 s and 150 s) are tolerated
     * and a third, at 200 s, is fatal; failures that each take the whole slack (observed at 130 s, then 210 s) make the
     * second one fatal. Cancellation is never swallowed (`catchingFailures`).
     */
    private suspend fun heartbeatLoop(claim: SyncJobClaim, leaseStart: TimeMark): Nothing {
        val leaseMillis = config.leaseSeconds * 1000
        val intervalMillis = maxOf(MIN_HEARTBEAT_INTERVAL_MS, leaseMillis / 3)
        val retryMillis = maxOf(MIN_HEARTBEAT_INTERVAL_MS, intervalMillis / 2)
        val slackMillis = maxOf(MIN_HEARTBEAT_SLACK_MS, leaseMillis / 10)
        var lastRenewalStart = leaseStart
        var waitMillis = intervalMillis
        while (true) {
            delay(waitMillis)
            val attemptStart = timeSource.markNow()
            var failure: Exception? = null
            val outcome = catchingFailures({ attemptRenewal(claim, slackMillis) }) {
                failure = it
                null
            }
            if (outcome == null) {
                val sinceRenewalMillis = lastRenewalStart.elapsedNow().inWholeMilliseconds
                if (sinceRenewalMillis + retryMillis + 2 * slackMillis >= leaseMillis) {
                    throw failure ?: TimeoutException(
                        "Heartbeat for sync job ${claim.id} timed out ($slackMillis ms) at $sinceRenewalMillis of $leaseMillis ms",
                    )
                }
                log.warn(
                    "Heartbeat for sync job {} did not complete ({} ms since the last renewal started, lease {} ms); retrying in {} ms",
                    claim.id, sinceRenewalMillis, leaseMillis, retryMillis, failure,
                )
                waitMillis = retryMillis
                continue
            }
            when (outcome) {
                HeartbeatOutcome.LOST -> throw LeaseLostException(claim.id)
                HeartbeatOutcome.CANCEL_REQUESTED -> throw JobCancelRequestedException(claim.id)
                HeartbeatOutcome.RENEWED -> {
                    lastRenewalStart = attemptStart
                    waitMillis = intervalMillis
                }
            }
        }
    }

    /**
     * One renewal attempt, bounded by [slackMillis] — `null` on timeout. The renewal runs in the worker's own [renewalScope],
     * NOT as a child of the job: a coroutine blocked inside a database statement does not react to cancellation until that
     * statement ends (exposed-r2dbc's transaction waits for its rollback — measured in `SyncJobQueueTest`), so a
     * `withTimeoutOrNull` around the call itself would not return on time, and a failing ticker could not cancel the job
     * scope either (it waits for its children). Awaiting a detached [Deferred] returns at the timeout; the abandoned attempt
     * is cancelled and rolls back whenever its statement ends — and the renewal carries `slackMillis` as its server-side
     * bound too, so that is within about a slack (`lock_timeout`/`queryTimeout`), unless the connection itself is stalled.
     */
    private suspend fun attemptRenewal(claim: SyncJobClaim, slackMillis: Long): HeartbeatOutcome? {
        val attempt = renewalScope.async { renewLease(claim, clock(), slackMillis) }
        try {
            return withTimeoutOrNull(slackMillis) { attempt.await() }
        } finally {
            attempt.cancel()
        }
    }

    /** Cancels the renewal attempts still running (shutdown); the job scopes are cancelled by the caller's own scope. */
    fun close() {
        renewalScope.cancel()
    }

    private fun logStale(claim: SyncJobClaim, write: String) {
        log.warn("Sync job {} attempt {}: {} matched no row, a newer attempt owns the job (stale run)", claim.id, claim.attempt, write)
    }

    /** `internal` (the `runJob`/`tick` precedent above) so `IngestWorkerTest` can drive the config-revision
     * re-derive check deterministically, without needing to race a real config PUT against a real DERIVE run. */
    internal suspend fun onSucceeded(claim: SyncJobClaim, deriveRevisionUsed: Long? = null) {
        if (!syncJobs.finish(claim.id, claim.attempt, clock())) {
            // A newer attempt owns the row: this run is stale, so it records nothing — no sync outcome, no chained DERIVE, no audit.
            logStale(claim, "finish")
            return
        }
        when (claim.kind) {
            SyncJobKind.SYNC -> dataSources.recordSyncOutcome(claim.connectionId, succeeded = true, errorCode = null, now = clock())
            SyncJobKind.RECONCILE -> dataSources.recordReconcileSucceeded(claim.connectionId, clock())
            SyncJobKind.PURGE -> dataSources.recordPurgeSucceeded(claim.connectionId, clock())
            SyncJobKind.REPROCESS, SyncJobKind.DERIVE -> Unit
        }
        // Chains a DERIVE after every successful SYNC/RECONCILE/REPROCESS (v0.3.0 M3 commit 7,
        // plan §2 decision 1) — coalesced by `uq_sync_jobs_open_per_kind`, so an already-pending
        // DERIVE for this connection is a no-op here.
        if (claim.kind == SyncJobKind.SYNC || claim.kind == SyncJobKind.RECONCILE || claim.kind == SyncJobKind.REPROCESS) {
            dataSources.read(claim.connectionId)?.let { connection ->
                syncJobs.enqueueScheduled(claim.connectionId, SyncJobKind.DERIVE, connection.configRevision, clock())
            }
        }
        // A config change (global settings, a team membership edit, a per-connection metrics-config
        // PUT) that landed WHILE this DERIVE was RUNNING coalesces into it instead of getting its
        // own job (`uq_sync_jobs_open_per_kind`) — so the run that just finished may have read the
        // OLD configuration. Compare the revision it recorded against the CURRENT one and enqueue a
        // fresh DERIVE if it is now stale (review round 1 fix) — coalescing again is harmless once
        // this run's own row is terminal.
        val revisionSource = handlers.configRevisionSource
        if (claim.kind == SyncJobKind.DERIVE && deriveRevisionUsed != null && revisionSource != null &&
            revisionSource.currentRevision() > deriveRevisionUsed
        ) {
            dataSources.read(claim.connectionId)?.let { connection ->
                syncJobs.enqueueScheduled(claim.connectionId, SyncJobKind.DERIVE, connection.configRevision, clock())
            }
        }
        audit("sync_job.succeeded", "jobId" to claim.id.toLong(), "dataSourceId" to claim.connectionId.toLong(), "kind" to claim.kind.name)
    }

    private suspend fun onFailed(claim: SyncJobClaim, cause: Exception) {
        val errorCode = "RUN_FAILED"
        if (!syncJobs.fail(claim.id, claim.attempt, errorCode, cause.message?.take(MAX_ERROR_DETAIL_LENGTH), clock())) {
            logStale(claim, "fail")
            return
        }
        if (claim.kind == SyncJobKind.SYNC) {
            dataSources.recordSyncOutcome(claim.connectionId, succeeded = false, errorCode = errorCode, now = clock())
        }
        audit(
            "sync_job.failed",
            "jobId" to claim.id.toLong(),
            "dataSourceId" to claim.connectionId.toLong(),
            "kind" to claim.kind.name,
            "errorCode" to errorCode,
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger(IngestWorker::class.java)
        const val MAX_ERROR_DETAIL_LENGTH = 1000
    }
}
