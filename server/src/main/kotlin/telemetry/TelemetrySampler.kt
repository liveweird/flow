package ch.nokillswit.telemetry

import ch.nokillswit.infra.catchingFailures
import ch.nokillswit.infra.db.PoolSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import kotlin.time.TimeSource

/** The first tick lands this long after start, so a fresh process shows up on the panel at once instead of after up to an interval. */
internal const val FIRST_TICK_DELAY_MILLIS = 5_000L

private const val MAX_FAILURE_TEXT = 300

/** One savepoint at a time (collectors run in sequence): one fixed identifier, since a collector's name may not be a valid one. */
private const val SAVEPOINT_NAME = "telemetry_collector"

/** A per-process collector (the JVM, the cgroup, the connection pool; request timers later). Its readings are this process's own. */
class ProcessCollector(val name: String, val collect: suspend () -> List<Reading>)

/**
 * A cluster collector (queue depth, storage sizes, the per-connection estimate - later commits): runs INSIDE the cluster
 * transaction, on the process that holds the lock, so it needs no dedupe of its own; its readings are written attributed to
 * that process. A failure rolls back only this collector's statements (a savepoint) and the others still run.
 */
class ClusterCollector(val name: String, val collect: suspend R2dbcTransaction.(now: Long) -> List<Reading>)

/**
 * One failure line per collector, not one per tick: the first failure is a WARN (the exception's `toString`, never a stack),
 * further failures DEBUG, and the first success after a failure an INFO "recovered".
 */
internal class FailureLog(private val log: org.slf4j.Logger) {
    private val failing = ConcurrentHashMap.newKeySet<String>()

    fun failed(name: String, e: Throwable) {
        val text = e.toString().take(MAX_FAILURE_TEXT)
        if (failing.add(name)) {
            log.warn("Telemetry collector {} failed: {}", name, text)
        } else {
            log.debug("Telemetry collector {} failed again: {}", name, text)
        }
    }

    fun ok(name: String) {
        if (failing.remove(name)) log.info("Telemetry collector {} recovered", name)
    }
}

/**
 * The per-process sampler (`.claude/docs/observability.md` "Telemetry"): every [TelemetryConfig.sampleIntervalSeconds] it writes
 * this process's own figures (JVM, cgroup, connection pool) to `ops.telemetry_samples`, and - on whichever process takes
 * the cluster lock for the interval - the cluster section (the hourly rollup and prune today).
 *
 * It is a best-effort observer: every collector runs in [catchingFailures] and is isolated from the others, the whole tick runs
 * under `interval / 2`, and nothing it does touches readiness, request handling or the ingest worker.
 * [clock] and the probes are the test seams (`TelemetryOverridesKey`), the collector lists are constructor parameters.
 */
class TelemetrySampler internal constructor(
    private val store: TelemetryStore,
    private val config: TelemetryConfig,
    private val identity: ProcessIdentity,
    private val clock: () -> Long,
    private val jvm: JvmProbe,
    private val cgroup: CgroupProbe,
    private val poolStats: () -> PoolSnapshot?,
    extraProcessCollectors: List<ProcessCollector> = emptyList(),
    private val clusterCollectors: List<ClusterCollector> = emptyList(),
    private val firstTickDelayMillis: Long = FIRST_TICK_DELAY_MILLIS,
) {
    private val log = LoggerFactory.getLogger(TelemetrySampler::class.java)
    private val failures = FailureLog(log)
    private val timeSource = TimeSource.Monotonic

    @Volatile
    private var processId: Int? = null

    @Volatile
    private var containerLimit: Long? = null

    private val processCollectors: List<ProcessCollector> =
        listOf(
            ProcessCollector("jvm") { jvm.read() },
            ProcessCollector("cgroup") { cgroupReadings() },
            ProcessCollector("pool") { poolReadings() },
        ) + extraProcessCollectors

    private val retention = ClusterCollector("retention") { now ->
        if (rollupHourly(now, rollupSettleMillis(config.sampleIntervalMillis))) pruneTelemetry(now)
        emptyList()
    }

    /** The sampling loop's job, once [start]ed; `TelemetryRoleTest` asserts it is completed after the application stops. */
    @Volatile
    internal var loop: Job? = null
        private set

    /** The loop: the first tick [FIRST_TICK_DELAY_MILLIS] after start, then one per aligned interval. Cancelling [scope] stops it. */
    fun start(scope: CoroutineScope) {
        loop = scope.launch {
            delay(firstTickDelayMillis)
            while (isActive) {
                tickWithinBudget()
                val interval = config.sampleIntervalMillis
                delay(interval - Math.floorMod(clock(), interval))
            }
        }
    }

    /** One tick under its `interval / 2` budget; `internal` for `TelemetrySamplerTest`. */
    internal suspend fun tickWithinBudget() {
        val finished = withTimeoutOrNull(config.sampleIntervalMillis / 2) { tick() }
        if (finished == null) failures.failed("tick", TimeoutException("a tick outran half the sample interval")) else failures.ok("tick")
    }

    /** One sample tick. `internal` for `TelemetrySamplerTest`. */
    internal suspend fun tick() {
        val now = clock()
        val bucket = now - Math.floorMod(now, config.sampleIntervalMillis)
        val readings = processCollectors.flatMap { collector -> guarded(collector.name) { collector.collect() }.orEmpty() }
        val id = ensureProcess(now) ?: return
        val rows = readings.mapNotNull { it.toRow(bucket, id) }
        if (rows.isNotEmpty()) guarded("store") { store.transaction { insertSampleRows(rows) } }
        guarded("cluster") { runClusterSection(id, now, bucket) }
    }

    private suspend fun ensureProcess(now: Long): Int? {
        val known = processId
        if (known != null && guarded("process") { store.touchProcess(known, now, containerLimit) } == true) return known
        // First tick, or the row vanished (an operator pruned it): (re-)register.
        val id = guarded("process") { store.registerProcess(identity, now, containerLimit) }
        processId = id
        return id
    }

    private suspend fun runClusterSection(processId: Int, now: Long, bucket: Long) {
        store.clusterSection {
            // Another process already served this interval's cluster section (its marker row is committed with its readings).
            if ((newestRawTs(TelemetryMetrics.CLUSTER_TICK) ?: Long.MIN_VALUE) >= bucket) return@clusterSection
            val started = timeSource.markNow()
            val readings = (listOf(retention) + clusterCollectors).flatMap { collector ->
                isolated(collector.name) { collector.collect(this, now) }.orEmpty()
            }
            val marker = Reading(TelemetryMetrics.CLUSTER_TICK, started.elapsedNow().inWholeMilliseconds.toDouble())
            insertSampleRows((readings + marker).mapNotNull { it.toRow(bucket, processId) })
        }
    }

    /** Runs a cluster collector in a savepoint: its failure is recorded, undone, and leaves the transaction usable for the next one. */
    private suspend fun <T> R2dbcTransaction.isolated(name: String, block: suspend () -> T): T? {
        val connection = connection()
        val savepoint = connection.setSavepoint(SAVEPOINT_NAME)
        return try {
            block().also {
                connection.releaseSavepoint(savepoint)
                failures.ok(name)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures.failed(name, e)
            connection.rollback(savepoint)
            null
        }
    }

    private suspend fun <T> guarded(name: String, block: suspend () -> T): T? =
        catchingFailures({ block().also { failures.ok(name) } }) {
            failures.failed(name, it)
            null
        }

    private fun Reading.toRow(bucket: Long, processId: Int): SampleRow? =
        if (value.isFinite()) SampleRow(config.sampleIntervalSeconds, bucket, processId, metric.name, label, 1, value, value) else null

    private fun cgroupReadings(): List<Reading> {
        val c = cgroup.read()
        containerLimit = c.limitBytes
        return listOfNotNull(
            c.rssBytes?.let { Reading(TelemetryMetrics.RSS, it.toDouble()) },
            c.currentBytes?.let { Reading(TelemetryMetrics.CGROUP_CURRENT, it.toDouble()) },
            c.anonBytes?.let { Reading(TelemetryMetrics.CGROUP_ANON, it.toDouble()) },
            c.workingSetBytes?.let { Reading(TelemetryMetrics.CGROUP_WORKING_SET, it.toDouble()) },
            c.peakBytes?.let { Reading(TelemetryMetrics.CGROUP_PEAK, it.toDouble()) },
        )
    }

    private fun poolReadings(): List<Reading> {
        val p = poolStats() ?: return emptyList()
        return listOf(
            Reading(TelemetryMetrics.POOL_ACQUIRED, p.acquired.toDouble()),
            Reading(TelemetryMetrics.POOL_IDLE, p.idle.toDouble()),
            Reading(TelemetryMetrics.POOL_PENDING, p.pendingAcquire.toDouble()),
            Reading(TelemetryMetrics.POOL_ALLOCATED, p.allocated.toDouble()),
        )
    }
}
