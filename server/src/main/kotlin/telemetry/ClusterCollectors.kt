package ch.nokillswit.telemetry

import ch.nokillswit.ingest.SyncJobsService
import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction

/**
 * The cheap cluster collectors this process runs inside the cluster lock's transaction (`TelemetrySampler`): the sync queue's depth
 * every tick. [syncJobs] is the ingest package's own service (telemetry reads another package's tables only through its services).
 * The heavy ones (storage sizes, the per-connection estimate) are `HeavyCollectors.kt`, run after that transaction.
 */
internal fun clusterCollectors(syncJobs: SyncJobsService): List<ClusterCollector> = listOf(
    ClusterCollector("queue") {
        val counts = syncJobs.statusCounts()
        listOf(
            Reading(TelemetryMetrics.QUEUE_PENDING, counts.pending.toDouble()),
            Reading(TelemetryMetrics.QUEUE_RUNNING, counts.running.toDouble()),
        )
    },
)

/** Runs a SELECT and maps each row; the rows are read whole (every use is a handful of catalog rows or one grouped count). */
internal suspend fun <T : Any> R2dbcTransaction.query(sql: String, map: (Row) -> T): List<T> =
    exec(sql, explicitStatementType = StatementType.SELECT) { row -> map(row) }?.toList().orEmpty().filterNotNull()
