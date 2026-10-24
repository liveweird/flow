package ch.nokillswit

import ch.nokillswit.ingest.DataSourceService
import ch.nokillswit.ingest.SyncJobStatus
import ch.nokillswit.ingest.SyncJobsService
import ch.nokillswit.metrics.MetricsTables
import ch.nokillswit.telemetry.HOURLY_RESOLUTION
import ch.nokillswit.telemetry.MetricDef
import ch.nokillswit.telemetry.SampleRow
import ch.nokillswit.telemetry.TelemetryStore
import ch.nokillswit.telemetry.insertSampleRows
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

internal const val TELE_RAW_SECONDS = 300
internal const val TELE_MINUTE = 60_000L
internal const val TELE_HOUR = 3_600_000L
internal const val TELE_DAY = 24 * TELE_HOUR

/** A raw (sample-interval) row of [metric]; `n` 1, `max` defaults to [avg]. */
internal fun rawRow(
    pid: Int,
    ts: Long,
    metric: MetricDef,
    avg: Double,
    label: String = "",
    max: Double = avg,
    resolution: Int = TELE_RAW_SECONDS,
) = SampleRow(resolution, ts, pid, metric.name, label, 1, avg, max)

/** An hourly rollup row of [metric] folding [n] raw samples. */
internal fun hourlyRow(pid: Int, ts: Long, metric: MetricDef, avg: Double, max: Double, n: Int = 12, label: String = "") =
    SampleRow(HOURLY_RESOLUTION, ts, pid, metric.name, label, n, avg, max)

internal suspend fun TelemetryStore.put(rows: List<SampleRow>) {
    transaction { insertSampleRows(rows) }
}

internal suspend fun TelemetryStore.put(vararg rows: SampleRow) = put(rows.toList())

/** Registers a process of [role] last seen at [lastSeen] (a unique instance unless named), with an optional container limit. */
internal suspend fun TelemetryStore.seedProcess(
    role: String,
    lastSeen: Long,
    startedAt: Long = lastSeen - TELE_HOUR,
    limitBytes: Long? = null,
    instance: String = "test-${java.util.UUID.randomUUID()}",
): Int = registerProcess(testIdentity(role = role, instance = instance, startedAt = startedAt), lastSeen, limitBytes)

/** Inserts one `sync_jobs` row of [connectionId] straight into the shared database (the queue is never driven by a worker here). */
@Suppress("LongParameterList") // a row fixture: every column of the table a test varies
internal suspend fun seedJob(
    connectionId: UInt,
    status: SyncJobStatus,
    requestedAt: Long,
    startedAt: Long? = null,
    finishedAt: Long? = null,
    kind: String = "SYNC",
    progress: String? = null,
): UInt = suspendTransaction(sharedDatabaseForTests()) {
    val jobs = SyncJobsService.Jobs
    jobs.insert {
        it[jobs.connectionId] = connectionId
        it[jobs.kind] = kind
        it[jobs.status] = status.name
        it[jobs.priority] = 10
        it[jobs.configRevision] = 1L
        it[jobs.requestedAt] = requestedAt
        it[jobs.startedAt] = startedAt
        it[jobs.finishedAt] = finishedAt
        it[jobs.attempt] = 1
        it[jobs.maxAttempts] = 3
        it[jobs.progress] = progress
    }[jobs.id].value
}

/** Cancels the connection's open jobs, so the shared queue's depth is left as found. */
internal suspend fun cancelOpenJobs(connectionId: UInt) {
    suspendTransaction(sharedDatabaseForTests()) {
        val jobs = SyncJobsService.Jobs
        jobs.update({ (jobs.connectionId eq connectionId) and (jobs.status inList listOf("PENDING", "RUNNING")) }) {
            it[jobs.status] = SyncJobStatus.CANCELLED.name
        }
    }
}

/** Inserts one ended `metrics.derive_runs` row of [connectionId]. */
internal suspend fun seedDeriveRun(connectionId: UInt, startedAt: Long, finishedAt: Long, status: String, rowCounts: String?) {
    suspendTransaction(sharedDatabaseForTests()) {
        val runs = MetricsTables.DeriveRuns
        runs.insert {
            it[runs.connectionId] = connectionId.toInt()
            it[runs.configRevision] = 1L
            it[runs.processingVersion] = 1
            it[runs.startedAt] = startedAt
            it[runs.finishedAt] = finishedAt
            it[runs.status] = status
            it[runs.rowCounts] = rowCounts
        }
    }
}

/** A data source of the shared test site (scope rules off, switched off so no worker touches it). */
internal suspend fun seedConnection(prefix: String): Pair<UInt, DataSourceService> {
    val dataSources = SyncedStubFixture.dataSources()
    return SyncedStubFixture.createConnection(dataSources = dataSources, namePrefix = prefix, enabled = false) to dataSources
}
