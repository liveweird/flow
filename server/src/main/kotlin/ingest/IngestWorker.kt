package ch.nokillswit.ingest

import ch.nokillswit.audit.audit
import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.config.requireConfigLong
import ch.nokillswit.metrics.MetricsConfigService
import ch.nokillswit.metrics.MetricsConfigServiceKey
import ch.nokillswit.metrics.MetricsDeriver
import ch.nokillswit.metrics.MetricsDeriverKey
import ch.nokillswit.plugins.runsWorker
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.config.ApplicationConfig
import io.ktor.util.AttributeKey
import java.net.InetAddress
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
private const val MIN_HEARTBEAT_INTERVAL_MS = 1_000L

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

    monitor.subscribe(ApplicationStarted) { app ->
        val connectors = app.attributes.getOrNull(IngestConnectorOverrideKey)
            ?: app.attributes.getOrNull(ConnectorRegistryKey)
            ?: emptyMap()
        val clock = app.attributes.getOrNull(IngestClockKey) ?: System::currentTimeMillis
        val worker = IngestWorker(
            syncJobs = app.attributes[SyncJobsServiceKey],
            dataSources = app.attributes[DataSourceServiceKey],
            metricsConfig = app.attributes[MetricsConfigServiceKey],
            deriver = app.attributes[MetricsDeriverKey],
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
 * each under a ticker that heartbeats every `leaseSeconds/3` and honours `cancel_requested_at`.
 * [clock] is injectable for tests.
 */
class IngestWorker(
    private val syncJobs: SyncJobsService,
    private val dataSources: DataSourceService,
    private val metricsConfig: MetricsConfigService,
    private val deriver: MetricsDeriver,
    private val connectors: Map<DataSourceKind, Connector>,
    private val config: IngestConfig,
    private val clock: () -> Long,
) {
    private val slots = Semaphore(config.workerSlots)

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
            val claim = syncJobs.claim(config.workerId, config.leaseSeconds, clock())
            if (claim == null) {
                slots.release()
                return@repeat
            }
            scope.launch {
                try {
                    runJob(claim)
                } finally {
                    slots.release()
                }
            }
        }
    }

    /** `internal` so `IngestWorkerTest` can run a single claimed job deterministically without the tick loop's timing. */
    internal suspend fun runJob(claim: SyncJobClaim) {
        if (claim.kind == SyncJobKind.SYNC) dataSources.recordSyncStarted(claim.connectionId, clock())
        audit(
            "sync_job.started",
            "jobId" to claim.id.toLong(),
            "dataSourceId" to claim.connectionId.toLong(),
            "kind" to claim.kind.name,
            "attempt" to claim.attempt,
            "workerId" to config.workerId,
        )
        try {
            coroutineScope {
                val ticker = launch {
                    while (isActive) {
                        delay(maxOf(MIN_HEARTBEAT_INTERVAL_MS, config.leaseSeconds * 1000 / 3))
                        if (!syncJobs.heartbeat(claim.id, config.workerId, config.leaseSeconds, clock())) {
                            throw LeaseLostException(claim.id)
                        }
                        if (syncJobs.isCancelRequested(claim.id)) throw JobCancelRequestedException(claim.id)
                    }
                }
                val context = SyncJobRunContext(claim) { progress, currentStream ->
                    syncJobs.heartbeat(claim.id, config.workerId, config.leaseSeconds, clock(), progress, currentStream)
                }
                // DERIVE (v0.3.0 M3 commit 7, `.claude/docs/ingestion.md` "The DERIVE job kind") is
                // connector-agnostic — dispatched here BEFORE the connector registry, so it runs
                // regardless of which connector kind the connection is.
                if (claim.kind == SyncJobKind.DERIVE) {
                    deriver.derive(context)
                } else {
                    connectors[claim.connectorKind]?.run(context)
                }
                // The generic, connector-agnostic PURGE step (v0.3.0 M1 commit 4,
                // `.claude/docs/ingestion.md` "PURGE"): runs AFTER the connector's own
                // `purgeSteps` (which drain its `raw.*`/`norm.*` rows) — every per-connection
                // `metrics.*` config row is connector-agnostic, so it is drained here rather than
                // inside `JiraConnector.purgeSteps`.
                if (claim.kind == SyncJobKind.PURGE) metricsConfig.purgeConnectionConfig(claim.connectionId)
                ticker.cancel()
            }
            onSucceeded(claim)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                syncJobs.release(claim.id)
                audit(
                    "sync_job.released",
                    "jobId" to claim.id.toLong(),
                    "dataSourceId" to claim.connectionId.toLong(),
                    "workerId" to config.workerId,
                )
            }
            throw cancelled
        } catch (lost: LeaseLostException) {
            log.warn("Sync job {} lost its lease mid-run: {}", claim.id, lost.message)
        } catch (cancelledJob: JobCancelRequestedException) {
            syncJobs.markCancelled(claim.id, clock())
            log.info("Sync job {} cancelled on request: {}", claim.id, cancelledJob.message)
        } catch (e: Exception) {
            onFailed(claim, e)
        }
    }

    private suspend fun onSucceeded(claim: SyncJobClaim) {
        syncJobs.finish(claim.id, clock())
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
        audit("sync_job.succeeded", "jobId" to claim.id.toLong(), "dataSourceId" to claim.connectionId.toLong(), "kind" to claim.kind.name)
    }

    private suspend fun onFailed(claim: SyncJobClaim, cause: Exception) {
        val errorCode = "RUN_FAILED"
        syncJobs.fail(claim.id, errorCode, cause.message?.take(MAX_ERROR_DETAIL_LENGTH), clock())
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
