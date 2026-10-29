package ch.nokillswit.ingest

import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.ingest.DataSourceService.Connections
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/** A sync is considered STALE once this many intervals have passed with no successful run. */
private const val STALE_INTERVAL_MULTIPLIER = 3L

/** The list endpoint's `name` filter as one predicate ([Op.TRUE] when absent) - the activeness predicate is added by the caller. */
internal fun dataSourcePredicate(filter: DataSourceListFilter): Op<Boolean> {
    var op: Op<Boolean> = Op.TRUE
    filter.name?.takeIf { it.isNotBlank() }?.let { op = op and Connections.name.containsNormalized(it) }
    return op
}

/** The row as the API returns it - the stored secret is reduced to `hasApiToken`, never read out. */
internal fun ResultRow.toDataSourceResponse(): DataSourceResponse {
    val settings = decodeSettings(this[Connections.settings])
    return DataSourceResponse(
        id = this[Connections.id].value,
        kind = DataSourceKind.valueOf(this[Connections.kind]),
        name = this[Connections.name],
        enabled = this[Connections.enabled],
        syncIntervalMinutes = this[Connections.syncIntervalMinutes],
        backfillFrom = this[Connections.backfillFrom],
        reconcileHourUtc = this[Connections.reconcileHourUtc],
        configRevision = this[Connections.configRevision],
        jira = JiraConnectionResponse(
            siteUrl = settings.siteUrl,
            email = settings.email,
            hasApiToken = this[Connections.secret].isNotEmpty(),
            projectKeys = settings.projectKeys,
            authScheme = settings.authScheme,
            cloudId = settings.cloudId,
        ),
        status = toDataSourceStatus(),
        createdAt = this[Connections.createdAt],
        updatedAt = this[Connections.updatedAt],
    )
}

internal fun ResultRow.toDataSourceStatus(now: Long = nowMillis()): DataSourceStatus {
    val lastSyncErrorCode = this[Connections.lastSyncErrorCode]
    val lastSyncSucceededAt = this[Connections.lastSyncSucceededAt]
    val state = when {
        !this[Connections.enabled] -> DataSourceState.DISABLED
        lastSyncErrorCode != null -> DataSourceState.FAILED
        lastSyncSucceededAt == null -> DataSourceState.NEVER_SYNCED
        now - lastSyncSucceededAt > this[Connections.syncIntervalMinutes].toLong() * 60_000L * STALE_INTERVAL_MULTIPLIER ->
            DataSourceState.STALE
        else -> DataSourceState.CURRENT
    }
    return DataSourceStatus(
        state = state,
        lastSyncStartedAt = this[Connections.lastSyncStartedAt],
        lastSyncSucceededAt = lastSyncSucceededAt,
        lastSyncErrorCode = lastSyncErrorCode,
        consecutiveFailures = this[Connections.consecutiveFailures],
        runningJobId = null,
        pausedAt = this[Connections.pausedAt],
        pauseReason = this[Connections.pauseReason],
        limitTrips = this[Connections.limitTrips],
        reconcilePausedAt = this[Connections.reconcilePausedAt],
        reconcilePauseReason = this[Connections.reconcilePauseReason],
        reconcileLimitTrips = this[Connections.reconcileLimitTrips],
    )
}

/** The decrypted in-process shape (`readForTest`/`readForSync`): the token is decrypted here and goes nowhere but the connector. */
internal fun ResultRow.toStoredJiraConnection(cipher: FieldCipher): DataSourceService.StoredJiraConnection {
    val settings = decodeSettings(this[Connections.settings])
    return DataSourceService.StoredJiraConnection(
        siteUrl = settings.siteUrl,
        email = settings.email,
        apiToken = cipher.decrypt(this[Connections.secret]),
        projectKeys = settings.projectKeys,
        authScheme = settings.authScheme,
        backfillFrom = this[Connections.backfillFrom],
    )
}

/**
 * What the telemetry panel may know of a connection (`telemetry/`, ADMIN only): its id and name, whether it is switched off or
 * soft-deleted and awaiting its PURGE ([deleted]), and the sync freshness columns. NEVER the settings (site URL, email, project
 * keys), the secret or the profile - this is the only shape the telemetry package reads a connection through.
 */
data class ConnectionHealth(
    val id: UInt,
    val name: String,
    val enabled: Boolean,
    val deleted: Boolean,
    val lastSyncSucceededAt: Long?,
    val consecutiveFailures: Int,
    val lastSyncErrorCode: String?,
    val nextSyncAt: Long?,
    val pausedAt: Long?,
    val pauseReason: String?,
    val reconcilePausedAt: Long?,
    val reconcilePauseReason: String?,
)

/** Every connection a PURGE has not yet drained (active, or soft-deleted and awaiting it), by id, as [ConnectionHealth]. */
internal suspend fun connectionHealthRows(database: R2dbcDatabase): List<ConnectionHealth> = suspendTransaction(database) {
    Connections.select(
        Connections.id, Connections.name, Connections.enabled, Connections.markedAsDeleted, Connections.lastSyncSucceededAt,
        Connections.consecutiveFailures, Connections.lastSyncErrorCode, Connections.nextSyncAt,
        Connections.pausedAt, Connections.pauseReason, Connections.reconcilePausedAt, Connections.reconcilePauseReason,
    ).where { Connections.purgedAt.isNull() }.orderBy(Connections.id to SortOrder.ASC).toList().map {
        ConnectionHealth(
            id = it[Connections.id].value,
            name = it[Connections.name],
            enabled = it[Connections.enabled],
            deleted = it[Connections.markedAsDeleted],
            lastSyncSucceededAt = it[Connections.lastSyncSucceededAt],
            consecutiveFailures = it[Connections.consecutiveFailures],
            lastSyncErrorCode = it[Connections.lastSyncErrorCode],
            nextSyncAt = it[Connections.nextSyncAt],
            pausedAt = it[Connections.pausedAt],
            pauseReason = it[Connections.pauseReason],
            reconcilePausedAt = it[Connections.reconcilePausedAt],
            reconcilePauseReason = it[Connections.reconcilePauseReason],
        )
    }
}
